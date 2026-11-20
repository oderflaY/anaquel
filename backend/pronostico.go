package main

import (
	"embed"
	"encoding/json"
	"math"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// Pronóstico de demanda: cuántas piezas de cada producto se venderán en los
// próximos 14 días. Lo calculan dos modelos de árboles (LightGBM) entrenados en
// ~/AnaquelIA/demanda con 1.9 millones de casos reales de ventas (M5 / Walmart):
//   p50 = lo esperado;  p90 = lo más que razonablemente venderás (para pedir
//   sin quedarte sin mercancía 9 de cada 10 veces).
// Solo usa lo que Anaquel sabe de cada tienda (ventas diarias, familia del
// producto, quincena y festivos), así que sirve igual para una tiendita nueva.

//go:embed modelos/demanda_p50.json modelos/demanda_p90.json
var modelosFS embed.FS

const diasPronostico = 14

type nodoArbol struct {
	F int        `json:"f"`
	T float64    `json:"t"`
	I bool       `json:"i"` // si falta el dato, ¿se va a la izquierda?
	M string     `json:"m"` // tipo de faltante: None | NaN | Zero
	A *nodoArbol `json:"a"`
	B *nodoArbol `json:"b"`
	V *float64   `json:"v"`
}

type modeloArboles struct {
	Caracteristicas []string     `json:"caracteristicas"`
	Transformar     string       `json:"transformar"`
	Arboles         []*nodoArbol `json:"arboles"`
}

func (m *modeloArboles) predecir(x []float64) float64 {
	s := 0.0
	for _, n := range m.Arboles {
		for n.V == nil {
			v := x[n.F]
			falta := math.IsNaN(v)
			if n.M == "Zero" && !falta && v == 0 {
				falta = true
			}
			if n.M == "None" && falta {
				v, falta = 0, false
			}
			switch {
			case falta && n.I, !falta && v <= n.T:
				n = n.A
			default:
				n = n.B
			}
		}
		s += *n.V
	}
	if m.Transformar == "exp" {
		return math.Exp(s)
	}
	return s
}

var (
	modelosDemanda    [2]*modeloArboles
	cargarModelosOnce sync.Once
	errModelosDemanda error
)

func modelosDeDemanda() (*modeloArboles, *modeloArboles, error) {
	cargarModelosOnce.Do(func() {
		for i, n := range []string{"modelos/demanda_p50.json", "modelos/demanda_p90.json"} {
			b, err := modelosFS.ReadFile(n)
			if err != nil {
				errModelosDemanda = err
				return
			}
			var m modeloArboles
			if err := json.Unmarshal(b, &m); err != nil {
				errModelosDemanda = err
				return
			}
			modelosDemanda[i] = &m
		}
	})
	return modelosDemanda[0], modelosDemanda[1], errModelosDemanda
}

// familiaDe: 0 comida y bebida, 1 hogar y limpieza, 2 lo demás (como en M5).
func familiaDe(categoria, giro string) float64 {
	c := normalizar(categoria + " " + giro)
	for _, k := range []string{"limpieza", "higiene", "hogar", "jarceria", "farmacia", "cuidado"} {
		if strings.Contains(c, k) {
			return 1
		}
	}
	for _, k := range []string{"abarrote", "bebida", "botana", "lacteo", "enlatado", "dulce", "pan", "comida", "carne", "fruta",
		"verdura", "galleta", "reposteria", "cafe", "cereal", "semilla", "salsa", "miel"} {
		if strings.Contains(c, k) {
			return 0
		}
	}
	return 2
}

// esFestivo: días que mueven la venta en México (el arranque de cada temporada del calendario).
func esFestivo(d time.Time) bool {
	for _, e := range calendario {
		if int(d.Month()) == e.mes && d.Day() == e.dia {
			return true
		}
	}
	return false
}

type Pronostico struct {
	ProductoID  int64   `json:"producto_id"`
	Nombre      string  `json:"nombre"`
	Existencia  *int64  `json:"existencia"`
	P50         float64 `json:"p50_14d"`      // piezas esperadas en 14 días
	P90         float64 `json:"p90_14d"`      // con 90 % de seguridad no venderás más que esto
	Diario      float64 `json:"diario"`       // piezas por día (p50 / 14)
	DiasAlcanza *int64  `json:"dias_alcanza"` // con lo que tienes, cuántos días te dura
	Pedir       int64   `json:"pedir"`        // piezas para cubrir 2 semanas al 90 %
	Historia    int     `json:"dias_historia"`
	Tendencia   string  `json:"tendencia"` // sube | baja | estable
}

type serieProducto struct {
	id, tienda  int64
	nombre, cat string
	stock       *int64
	primero     int // día (índice) del primer movimiento
	ventas      map[int]float64
}

// pronosticar calcula el pronóstico de todos los productos de una tienda
// (tienda = 0: de todas, para el grafo).
func (a *App) pronosticar(tienda int64) map[int64]Pronostico {
	out := map[int64]Pronostico{}
	p50, p90, err := modelosDeDemanda()
	if err != nil {
		return out
	}
	hoy := horaMexico()
	hoy = time.Date(hoy.Year(), hoy.Month(), hoy.Day(), 0, 0, 0, 0, hoy.Location())
	diaDe := func(ms int64) int { // 0 = ayer, 1 = antier… (días completos antes de hoy)
		return int(hoy.Sub(time.UnixMilli(ms).In(hoy.Location())).Hours()/24+0.999999) - 1
	}
	filtro, args := "", []any{}
	if tienda > 0 {
		filtro, args = " WHERE p.tienda_id = ?", []any{tienda}
	}
	series := map[int64]*serieProducto{}
	giros := map[int64]string{}
	rows, err := a.db.Query(`SELECT p.id, p.tienda_id, p.nombre, p.categoria, p.stock, t.giro FROM productos p
		JOIN tiendas t ON t.id = p.tienda_id`+filtro, args...)
	if err != nil {
		return out
	}
	for rows.Next() {
		s := &serieProducto{ventas: map[int]float64{}, primero: -1}
		var giro string
		rows.Scan(&s.id, &s.tienda, &s.nombre, &s.cat, &s.stock, &giro)
		series[s.id] = s
		giros[s.id] = giro
	}
	rows.Close()
	desde := hoy.AddDate(0, 0, -400).UnixMilli()
	rows, err = a.db.Query(`SELECT m.producto_id, m.tipo, m.cantidad, m.creado FROM movimientos m
		JOIN productos p ON p.id = m.producto_id WHERE m.creado >= ? AND m.creado < ?`+strings.Replace(filtro, "WHERE", "AND", 1),
		append([]any{desde, hoy.UnixMilli()}, args...)...)
	if err != nil {
		return out
	}
	for rows.Next() {
		var id, cant, creado int64
		var tipo string
		rows.Scan(&id, &tipo, &cant, &creado)
		s := series[id]
		if s == nil {
			continue
		}
		d := diaDe(creado)
		if d < 0 {
			continue
		}
		if d > s.primero {
			s.primero = d
		}
		if tipo == "venta" || tipo == "salida" || tipo == "trato_salida" {
			s.ventas[d] += float64(-cant)
		}
	}
	rows.Close()

	festivos14, aFestivo := 0.0, 60.0
	for i := 0; i < 60; i++ {
		if esFestivo(hoy.AddDate(0, 0, i)) {
			if i < diasPronostico {
				festivos14++
			}
			if aFestivo == 60 {
				aFestivo = float64(i)
			}
		}
	}
	nan := math.NaN()
	for id, s := range series {
		hist := s.primero + 1 // días completos con historia
		if hist < 7 {
			continue // muy nuevo: no hay con qué
		}
		if hist > 400 {
			hist = 400
		}
		media := func(a, b int) float64 { // promedio diario de los días [a, b) hacia atrás
			t := 0.0
			for d := a; d < b; d++ {
				t += s.ventas[d]
			}
			return t / float64(b-a)
		}
		m := func(k int) float64 {
			if hist >= k {
				return media(0, k)
			}
			return nan
		}
		h28 := min(28, hist)
		ceros, maximo := 0.0, 0.0
		for d := 0; d < h28; d++ {
			v := s.ventas[d]
			if v == 0 {
				ceros++
			}
			maximo = math.Max(maximo, v)
		}
		sin := float64(min(112, hist))
		for d := 0; d < min(112, hist); d++ {
			if s.ventas[d] > 0 {
				sin = float64(d)
				break
			}
		}
		yoy := nan
		if hist >= 365+28 {
			if base := media(364, 392); base > 0 {
				yoy = media(350, 364) / base
			}
		}
		m28 := media(0, h28)
		x := []float64{
			m(7), m(14), m28, m(56), m(112),
			ceros / float64(h28), sin, maximo,
			float64((int(hoy.Weekday()) + 6) % 7), float64(hoy.Day()), float64(hoy.Month()),
			festivos14, aFestivo, yoy, familiaDe(s.cat, giros[id]), float64(min(hist, 365)),
		}
		if hist < 14 { // el modelo vio mínimo 14 días: con 7–13 se completa con lo que hay
			x[1] = media(0, hist)
		}
		a50 := math.Max(0, p50.predecir(x))
		a90 := math.Max(a50, p90.predecir(x))
		pr := Pronostico{ProductoID: id, Nombre: s.nombre, Existencia: s.stock, P50: math.Round(a50*10) / 10,
			P90: math.Round(a90*10) / 10, Diario: math.Round(a50/diasPronostico*100) / 100, Historia: hist, Tendencia: "estable"}
		if m28 > 0 {
			switch r := x[0] / m28; {
			case r >= 1.25:
				pr.Tendencia = "sube"
			case r <= 0.75:
				pr.Tendencia = "baja"
			}
		}
		if s.stock != nil {
			pr.Pedir = max(0, int64(math.Ceil(a90))-*s.stock)
			if a50 > 0.01 {
				d := int64(float64(*s.stock) / (a50 / diasPronostico))
				pr.DiasAlcanza = &d
			}
		}
		out[id] = pr
	}
	return out
}

// GET /api/pronostico: lo que venderás en las próximas 2 semanas, producto por producto.
func (a *App) verPronostico(w http.ResponseWriter, r *http.Request) {
	ps := a.pronosticar(tiendaDe(r))
	lista := make([]Pronostico, 0, len(ps))
	for _, p := range ps {
		lista = append(lista, p)
	}
	sort.Slice(lista, func(i, j int) bool {
		di, dj := int64(1<<40), int64(1<<40)
		if lista[i].DiasAlcanza != nil {
			di = *lista[i].DiasAlcanza
		}
		if lista[j].DiasAlcanza != nil {
			dj = *lista[j].DiasAlcanza
		}
		if di != dj {
			return di < dj
		}
		return lista[i].P50 > lista[j].P50
	})
	manejar(w, map[string]any{"dias": diasPronostico, "productos": lista,
		"modelo": "LightGBM entrenado con 1.9 millones de casos reales de ventas (M5)"}, nil)
}
