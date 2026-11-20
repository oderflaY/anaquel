package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"sort"
	"strings"
)

// Bodega 3D, al estilo de la de MatObra pero para una tienda: el plano del
// local (paredes, puerta, áreas y muebles a escala, en metros) y el lugar de
// cada producto (mueble, lado, entrepaño y orden dentro del entrepaño).
//
// Ejes: x de izquierda a derecha (0..Ancho), z del fondo (0) a la calle
// (Fondo), donde está la puerta. Un mueble con rot 0 tiene su frente hacia
// la calle (+z); rot 90 hacia la derecha (+x); 180 al fondo; 270 a la izquierda.

type Mueble struct {
	ID        string  `json:"id"`
	Tipo      string  `json:"tipo"`
	Nombre    string  `json:"nombre"`
	X         float64 `json:"x"`
	Z         float64 `json:"z"`
	Rot       int     `json:"rot"`
	Ancho     float64 `json:"ancho"`
	Fondo     float64 `json:"fondo"`
	Alto      float64 `json:"alto"`
	Niveles   int     `json:"niveles"`
	AnaquelID *int64  `json:"anaquel_id,omitempty"`
}

type Area struct {
	Nombre string  `json:"nombre"`
	X      float64 `json:"x"`
	Z      float64 `json:"z"`
	Ancho  float64 `json:"ancho"`
	Fondo  float64 `json:"fondo"`
	Color  string  `json:"color"`
}

type Puerta struct {
	X     float64 `json:"x"` // centro, sobre la pared de la calle
	Ancho float64 `json:"ancho"`
}

// Pared interior que arma el tendero (bodega, trastienda, oficina). Puede tener
// un hueco de puerta al centro (Puerta > 0) por donde se pasa caminando.
type Pared struct {
	X1     float64 `json:"x1"`
	Z1     float64 `json:"z1"`
	X2     float64 `json:"x2"`
	Z2     float64 `json:"z2"`
	Alto   float64 `json:"alto"`
	Puerta float64 `json:"puerta"`         // ancho del hueco de puerta (0 = pared corrida)
	Tipo   string  `json:"tipo,omitempty"` // muro | media (barda a media altura) | cristal
}

// Columna o pilar del local (estorba al caminar).
type Columna struct {
	X    float64 `json:"x"`
	Z    float64 `json:"z"`
	Lado float64 `json:"lado"`
}

type Plano struct {
	Ancho    float64   `json:"ancho"`
	Fondo    float64   `json:"fondo"`
	Alto     float64   `json:"alto"`
	Puerta   Puerta    `json:"puerta"`
	Piso     string    `json:"piso"` // mosaico | cemento | madera
	Muebles  []Mueble  `json:"muebles"`
	Areas    []Area    `json:"areas"`
	Paredes  []Pared   `json:"paredes"`
	Columnas []Columna `json:"columnas"`
}

// Datos de cada tipo de mueble: medidas por omisión, entrepaños, lados
// accesibles y cuántos productos distintos caben por metro de entrepaño.
type tipoMueble struct {
	nombre                 string
	ancho, fondo, alto     float64
	niveles, lados         int
	porMetro               float64
	minNiveles, maxNiveles int
}

var tiposMueble = map[string]tipoMueble{
	"gondola":    {"Góndola", 2.4, 0.9, 1.6, 4, 2, 2.2, 2, 6},
	"anaquel":    {"Anaquel de pared", 2.4, 0.45, 1.9, 4, 1, 2.2, 2, 7},
	"refri":      {"Refrigerador", 1.8, 0.75, 2.0, 5, 1, 3, 3, 6},
	"congelador": {"Congelador", 1.4, 0.75, 0.9, 1, 1, 3, 1, 1},
	"rack":       {"Rack de carga", 2.7, 1.0, 3.6, 3, 1, 0.75, 2, 5},
	"tarima":     {"Tarima en piso", 1.2, 1.0, 1.6, 1, 1, 0.9, 1, 1},
	"perchero":   {"Perchero", 1.8, 0.6, 1.6, 1, 1, 1.7, 1, 2},
	"mesa":       {"Mesa de exhibición", 1.6, 0.9, 0.8, 1, 1, 2.5, 1, 1},
	"mostrador":  {"Mostrador y caja", 2.2, 0.7, 1.0, 1, 1, 2, 1, 2},
	"botanero":   {"Exhibidor de botana", 0.7, 0.5, 1.7, 4, 1, 3, 2, 6},
	"vitrina":    {"Vitrina de cristal", 1.5, 0.6, 1.1, 2, 1, 2.5, 1, 3},
	"ganchera":   {"Panel de ganchos", 1.2, 0.35, 1.8, 5, 1, 3, 3, 8},
	"cajas":      {"Estante de cajas", 1.8, 0.5, 1.8, 5, 1, 2.5, 3, 7},
}

func numeroEn(v, min, max float64) float64 {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return min
	}
	return math.Max(min, math.Min(max, v))
}

// limpiar deja el plano dentro de límites razonables (lo que llegue del
// navegador no se confía): medidas, tipos, ids únicos y muebles adentro.
func (p *Plano) limpiar() error {
	p.Ancho = math.Round(numeroEn(p.Ancho, 2, 60)*100) / 100
	p.Fondo = math.Round(numeroEn(p.Fondo, 2, 60)*100) / 100
	p.Alto = numeroEn(p.Alto, 2.2, 8)
	p.Puerta.Ancho = numeroEn(p.Puerta.Ancho, 0.7, math.Min(6, p.Ancho-0.2))
	p.Puerta.X = numeroEn(p.Puerta.X, p.Puerta.Ancho/2+0.05, p.Ancho-p.Puerta.Ancho/2-0.05)
	if p.Piso != "cemento" && p.Piso != "madera" {
		p.Piso = "mosaico"
	}
	if len(p.Muebles) > 200 {
		return fallo(400, "Son demasiados muebles (máximo 200)")
	}
	if len(p.Areas) > 30 {
		return fallo(400, "Son demasiadas áreas (máximo 30)")
	}
	ids := map[string]bool{}
	for i := range p.Muebles {
		m := &p.Muebles[i]
		t, ok := tiposMueble[m.Tipo]
		if !ok {
			return fallo(400, "Tipo de mueble desconocido: "+m.Tipo)
		}
		m.ID = recortarTexto(strings.TrimSpace(m.ID), 24)
		if m.ID == "" || ids[m.ID] {
			m.ID = fmt.Sprintf("m%d", i+1)
			for ids[m.ID] {
				m.ID += "x"
			}
		}
		ids[m.ID] = true
		m.Nombre = recortarTexto(strings.TrimSpace(m.Nombre), 60)
		if m.Nombre == "" {
			m.Nombre = t.nombre
		}
		m.Rot = ((m.Rot % 360) + 360) % 360 / 90 * 90
		m.Ancho = numeroEn(m.Ancho, 0.3, 20)
		m.Fondo = numeroEn(m.Fondo, 0.3, 4)
		m.Alto = numeroEn(m.Alto, 0.4, math.Min(7, p.Alto))
		if m.Niveles < t.minNiveles || m.Niveles > t.maxNiveles {
			m.Niveles = max(t.minNiveles, min(t.maxNiveles, m.Niveles))
		}
		// Que quede adentro del local (con su huella girada).
		w, d := m.Ancho, m.Fondo
		if m.Rot == 90 || m.Rot == 270 {
			w, d = d, w
		}
		m.X = math.Round(numeroEn(m.X, math.Min(w/2, p.Ancho/2), math.Max(p.Ancho-w/2, p.Ancho/2))*100) / 100
		m.Z = math.Round(numeroEn(m.Z, math.Min(d/2, p.Fondo/2), math.Max(p.Fondo-d/2, p.Fondo/2))*100) / 100
	}
	if len(p.Paredes) > 80 {
		return fallo(400, "Son demasiadas paredes (máximo 80)")
	}
	if len(p.Columnas) > 60 {
		return fallo(400, "Son demasiadas columnas (máximo 60)")
	}
	paredes := p.Paredes[:0]
	for _, w := range p.Paredes {
		w.X1, w.X2 = numeroEn(w.X1, 0, p.Ancho), numeroEn(w.X2, 0, p.Ancho)
		w.Z1, w.Z2 = numeroEn(w.Z1, 0, p.Fondo), numeroEn(w.Z2, 0, p.Fondo)
		largo := math.Hypot(w.X2-w.X1, w.Z2-w.Z1)
		if largo < 0.3 {
			continue // un clic sin arrastrar
		}
		if w.Tipo != "media" && w.Tipo != "cristal" {
			w.Tipo = "muro"
		}
		altoMax := p.Alto
		if w.Tipo == "media" {
			altoMax = 1.5
		}
		if w.Alto <= 0 {
			w.Alto = altoMax
		}
		w.Alto = numeroEn(w.Alto, 0.5, altoMax)
		w.Puerta = 0 + numeroEn(w.Puerta, 0, math.Max(0, largo-0.2))
		if w.Puerta > 0 && w.Puerta < 0.6 {
			w.Puerta = 0.6
		}
		for _, v := range []*float64{&w.X1, &w.Z1, &w.X2, &w.Z2, &w.Alto, &w.Puerta} {
			*v = math.Round(*v*100) / 100
		}
		paredes = append(paredes, w)
	}
	p.Paredes = paredes
	for i := range p.Columnas {
		c := &p.Columnas[i]
		c.Lado = numeroEn(c.Lado, 0.15, 1.5)
		c.X = numeroEn(c.X, c.Lado/2, p.Ancho-c.Lado/2)
		c.Z = numeroEn(c.Z, c.Lado/2, p.Fondo-c.Lado/2)
	}
	for i := range p.Areas {
		ar := &p.Areas[i]
		ar.Nombre = recortarTexto(strings.TrimSpace(ar.Nombre), 40)
		ar.Ancho = numeroEn(ar.Ancho, 0.5, p.Ancho)
		ar.Fondo = numeroEn(ar.Fondo, 0.5, p.Fondo)
		ar.X = numeroEn(ar.X, 0, p.Ancho-ar.Ancho)
		ar.Z = numeroEn(ar.Z, 0, p.Fondo-ar.Fondo)
		if len(ar.Color) != 7 || ar.Color[0] != '#' {
			ar.Color = "#cfd8dc"
		}
	}
	return nil
}

func mueble(id, tipo, nombre string, x, z float64, rot int, ancho float64) Mueble {
	t := tiposMueble[tipo]
	if ancho <= 0 {
		ancho = t.ancho
	}
	return Mueble{ID: id, Tipo: tipo, Nombre: nombre, X: x, Z: z, Rot: rot, Ancho: ancho, Fondo: t.fondo, Alto: t.alto, Niveles: t.niveles}
}

// planoAuto arma un local típico del giro, con los anaqueles fotografiados
// colgados en la pared del fondo (así la foto del día pinta el 3D).
func planoAuto(giro string, anaqueles []Anaquel) Plano {
	var p Plano
	switch giro {
	case "ropa":
		p = Plano{Ancho: 8, Fondo: 10, Alto: 3, Puerta: Puerta{X: 4.2, Ancho: 1.8}, Piso: "madera"}
		p.Muebles = []Mueble{
			mueble("pared-izq", "anaquel", "Pared de accesorios", 0.25, 4.4, 90, 4.2),
			mueble("pared-fondo", "anaquel", "Pared del fondo", 3.2, 0.25, 0, 4.4),
			mueble("perchero-1", "perchero", "Perchero 1", 2.5, 4.6, 90, 2.4),
			mueble("perchero-2", "perchero", "Perchero 2", 4.6, 4.6, 90, 2.4),
			mueble("mesa-1", "mesa", "Mesa de novedades", 3.0, 7.6, 0, 1.8),
			mueble("mesa-2", "mesa", "Mesa de básicos", 6.2, 4.6, 90, 1.8),
			mueble("caja", "mostrador", "Caja", 6.6, 8.3, 270, 1.8),
		}
		p.Muebles[0].Niveles, p.Muebles[1].Niveles = 4, 4
		p.Areas = []Area{{Nombre: "Probadores", X: 6.0, Z: 0, Ancho: 2.0, Fondo: 2.2, Color: "#d9c7e8"}}
	case "mayoreo":
		p = Plano{Ancho: 12, Fondo: 16, Alto: 5, Puerta: Puerta{X: 9.4, Ancho: 3.2}, Piso: "cemento"}
		for i, x := range []float64{1.6, 4.4, 7.2, 10.0} {
			p.Muebles = append(p.Muebles, mueble(fmt.Sprintf("rack-a%d", i+1), "rack", fmt.Sprintf("Rack A%d", i+1), x, 0.65, 0, 2.7))
		}
		for i, x := range []float64{3.0, 5.8, 8.6} {
			p.Muebles = append(p.Muebles, mueble(fmt.Sprintf("rack-b%d", i+1), "rack", fmt.Sprintf("Rack B%d", i+1), x, 5.0, 180, 2.7))
			p.Muebles = append(p.Muebles, mueble(fmt.Sprintf("rack-c%d", i+1), "rack", fmt.Sprintf("Rack C%d", i+1), x, 6.05, 0, 2.7))
		}
		for i, x := range []float64{1.4, 3.0, 4.6} {
			p.Muebles = append(p.Muebles, mueble(fmt.Sprintf("tarima-%d", i+1), "tarima", fmt.Sprintf("Tarima %d", i+1), x, 10.2, 0, 0))
		}
		p.Muebles = append(p.Muebles, mueble("caja", "mostrador", "Oficina y caja", 2.2, 14.6, 0, 2.6))
		p.Areas = []Area{
			{Nombre: "Andén de carga", X: 7.2, Z: 11.5, Ancho: 4.8, Fondo: 4.5, Color: "#f2d48a"},
			{Nombre: "Surtido de pedidos", X: 0.4, Z: 9.2, Ancho: 6.0, Fondo: 2.2, Color: "#b9d7ea"},
		}
	case "papeleria":
		p = Plano{Ancho: 6, Fondo: 8, Alto: 2.8, Puerta: Puerta{X: 3.6, Ancho: 1.4}, Piso: "mosaico"}
		p.Muebles = []Mueble{
			mueble("pared-fondo", "anaquel", "Anaquel escolar", 3.0, 0.25, 0, 5.2),
			mueble("pared-izq", "anaquel", "Anaquel de fiestas", 0.25, 3.2, 90, 3.6),
			mueble("gondola-1", "gondola", "Góndola de cuadernos", 3.2, 3.2, 90, 2.4),
			mueble("caja", "mostrador", "Mostrador", 3.0, 5.6, 0, 3.6),
			mueble("botanero", "botanero", "Dulces y regalos", 5.3, 6.6, 270, 0),
		}
		p.Muebles[0].Niveles = 5
	case "servicios":
		p = Plano{Ancho: 5, Fondo: 6, Alto: 2.8, Puerta: Puerta{X: 2.5, Ancho: 1.2}, Piso: "mosaico"}
		p.Muebles = []Mueble{
			mueble("pared-fondo", "anaquel", "Anaquel de material", 2.5, 0.25, 0, 3.6),
			mueble("caja", "mostrador", "Mostrador", 2.5, 3.6, 0, 2.6),
		}
	default: // abarrotes, dulcería, farmacia, ferretería, comida, otro
		p = Plano{Ancho: 7, Fondo: 9, Alto: 3, Puerta: Puerta{X: 4.9, Ancho: 1.4}, Piso: "mosaico"}
		p.Muebles = []Mueble{
			mueble("refri", "refri", "Refrigerador de bebidas", 0.4, 3.6, 90, 2.4),
			mueble("pared-fondo", "anaquel", "Anaquel del fondo", 3.6, 0.25, 0, 3.4),
			mueble("gondola-1", "gondola", "Góndola 1", 2.6, 4.0, 90, 3.0),
			mueble("gondola-2", "gondola", "Góndola 2", 4.8, 4.0, 90, 3.0),
			mueble("pared-der", "anaquel", "Anaquel de la derecha", 6.75, 4.0, 270, 3.0),
			mueble("caja", "mostrador", "Mostrador y caja", 1.8, 7.4, 0, 2.4),
			mueble("botanero", "botanero", "Exhibidor de botana", 3.5, 7.5, 0, 0),
		}
		p.Muebles[1].Niveles = 3
	}
	// Los anaqueles fotografiados: el primero es la pared del fondo; los demás,
	// anaqueles nuevos a lo largo de la pared izquierda o al fondo.
	for i, an := range anaqueles {
		id := an.ID
		if i == 0 {
			for j := range p.Muebles {
				if p.Muebles[j].ID == "pared-fondo" {
					p.Muebles[j].AnaquelID = &id
					p.Muebles[j].Nombre = an.Nombre
					if n := filasDeZonas(an.Zonas); n > 0 {
						p.Muebles[j].Niveles = n
					}
				}
			}
			continue
		}
		m := mueble(fmt.Sprintf("foto-%d", an.ID), "anaquel", an.Nombre, math.Min(p.Ancho-1.3, 1.3+float64(i-1)*2.6), 1.2, 0, 2.4)
		m.AnaquelID = &id
		if n := filasDeZonas(an.Zonas); n > 0 {
			m.Niveles = min(7, n)
		}
		p.Muebles = append(p.Muebles, m)
	}
	p.limpiar()
	return p
}

// filasDeZonas cuenta cuántas filas (entrepaños) hay en la foto del anaquel,
// agrupando las zonas por la altura de su centro.
func filasDeZonas(zs []Zona) int {
	var cs []float64
	for _, z := range zs {
		cs = append(cs, z.Y+z.H/2)
	}
	sort.Float64s(cs)
	n := 0
	ult := -1.0
	for _, c := range cs {
		if c-ult > 0.12 {
			n++
			ult = c
		}
	}
	return n
}

// nivelDeZona: en la foto la fila de arriba es y≈0; en el mueble el nivel 1 es el de abajo.
func nivelDeZona(z Zona, zs []Zona, niveles int) int {
	var cs []float64
	for _, o := range zs {
		cs = append(cs, o.Y+o.H/2)
	}
	sort.Float64s(cs)
	filas := []float64{}
	ult := -1.0
	for _, c := range cs {
		if c-ult > 0.12 {
			filas = append(filas, c)
			ult = c
		}
	}
	fila := 0
	c := z.Y + z.H/2
	for i, f := range filas {
		if math.Abs(c-f) < math.Abs(c-filas[fila]) {
			fila = i
		}
	}
	n := len(filas) - fila // fila 0 (arriba) → nivel más alto
	return max(1, min(niveles, n))
}

func (a *App) leerPlano(tienda int64) (Plano, bool, error) {
	var datos string
	err := a.db.QueryRow("SELECT datos FROM planos WHERE tienda_id = ?", tienda).Scan(&datos)
	if err == sql.ErrNoRows {
		return Plano{}, false, nil
	}
	if err != nil {
		return Plano{}, false, err
	}
	var p Plano
	if err := json.Unmarshal([]byte(datos), &p); err != nil {
		return Plano{}, false, err
	}
	if p.Muebles == nil {
		p.Muebles = []Mueble{}
	}
	if p.Paredes == nil {
		p.Paredes = []Pared{}
	}
	if p.Columnas == nil {
		p.Columnas = []Columna{}
	}
	if p.Areas == nil {
		p.Areas = []Area{}
	}
	return p, true, nil
}

func (a *App) guardarPlano(ex ejecutor, tienda int64, p Plano) error {
	b, _ := json.Marshal(p)
	_, err := ex.Exec(`INSERT INTO planos (tienda_id, datos, actualizado) VALUES (?, ?, ?)
		ON CONFLICT(tienda_id) DO UPDATE SET datos = excluded.datos, actualizado = excluded.actualizado`, tienda, string(b), ahora())
	return err
}

// Lugar3D es un producto tal como lo dibuja la bodega 3D.
type Lugar3D struct {
	ID             int64   `json:"id"`
	Codigo         string  `json:"codigo"`
	Nombre         string  `json:"nombre"`
	Categoria      string  `json:"categoria"`
	Unidad         string  `json:"unidad"`
	PrecioC        int64   `json:"precio_c"`
	PrecioMayoreoC int64   `json:"precio_mayoreo_c"`
	MinimoMayoreo  int64   `json:"minimo_mayoreo"`
	Stock          *int64  `json:"stock"`
	StockMin       int64   `json:"stock_min"`
	Publicado      bool    `json:"publicado"`
	Vendidos30     int64   `json:"vendidos30"`
	Mueble         string  `json:"mueble"`
	Lado           int     `json:"lado"`
	Nivel          int     `json:"nivel"`
	Posicion       float64 `json:"posicion"`
	// origen: "foto" si el lugar sale de la zona marcada en la foto del anaquel.
	Origen string `json:"origen"`
	Foto   string `json:"foto"` // lleno | poco | vacio | "" (sin foto)
}

type zonaProducto struct {
	anaquel int64
	zona    Zona
	todas   []Zona
	en      *int64
}

// lugares devuelve los productos de la tienda con su lugar. Si un producto no
// tiene lugar pero está marcado en la foto de un anaquel que cuelga en el
// plano, toma el lugar de la foto (fila → entrepaño, x → orden).
func (a *App) lugares(tienda int64, plano Plano) ([]Lugar3D, error) {
	anaqueles, err := a.anaquelesDe(tienda)
	if err != nil {
		return nil, err
	}
	enFoto := map[int64]zonaProducto{}
	for _, an := range anaqueles {
		for _, z := range an.Zonas {
			if z.ProductoID == nil {
				continue
			}
			// Con varios huecos del mismo producto se queda el más lleno.
			prev, ok := enFoto[*z.ProductoID]
			if !ok || rangoNivel(z.Nivel) < rangoNivel(prev.zona.Nivel) {
				enFoto[*z.ProductoID] = zonaProducto{an.ID, z, an.Zonas, an.UltimaEn}
			}
		}
	}
	muebleDeAnaquel := map[int64]Mueble{}
	existe := map[string]bool{}
	for _, m := range plano.Muebles {
		existe[m.ID] = true
		if m.AnaquelID != nil {
			muebleDeAnaquel[*m.AnaquelID] = m
		}
	}
	vend := a.vendidos30(tienda)
	rows, err := a.db.Query(`SELECT id, codigo, nombre, categoria, unidad, precio_c, precio_mayoreo_c, minimo_mayoreo, stock, stock_min,
		publicado, lugar_mueble, lugar_lado, lugar_nivel, lugar_pos FROM productos WHERE tienda_id = ? ORDER BY categoria, nombre COLLATE NOCASE`, tienda)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Lugar3D{}
	for rows.Next() {
		var l Lugar3D
		if err := rows.Scan(&l.ID, &l.Codigo, &l.Nombre, &l.Categoria, &l.Unidad, &l.PrecioC, &l.PrecioMayoreoC, &l.MinimoMayoreo,
			&l.Stock, &l.StockMin, &l.Publicado, &l.Mueble, &l.Lado, &l.Nivel, &l.Posicion); err != nil {
			return nil, err
		}
		l.Vendidos30 = vend[l.ID]
		zp, conFoto := enFoto[l.ID]
		if conFoto {
			l.Foto = zp.zona.Nivel
		}
		if !existe[l.Mueble] {
			l.Mueble, l.Lado, l.Nivel, l.Posicion = "", 0, 0, 0
		}
		if l.Mueble == "" && conFoto {
			if m, ok := muebleDeAnaquel[zp.anaquel]; ok {
				l.Mueble, l.Lado = m.ID, 0
				l.Nivel = nivelDeZona(zp.zona, zp.todas, m.Niveles)
				l.Posicion = zp.zona.X + zp.zona.W/2
				l.Origen = "foto"
			}
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

func rangoNivel(n string) int {
	switch n {
	case "lleno":
		return 0
	case "poco":
		return 1
	}
	return 2
}

func (a *App) anaquelesDe(tienda int64) ([]Anaquel, error) {
	rows, err := a.db.Query("SELECT id FROM anaqueles WHERE tienda_id = ? ORDER BY id", tienda)
	if err != nil {
		return nil, err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	out := []Anaquel{}
	for _, id := range ids {
		an, err := a.cargarAnaquel(id)
		if err != nil {
			return nil, err
		}
		out = append(out, an)
	}
	return out, nil
}

// ---- Acomodo automático ----

// Palabras que mandan un producto a cierto tipo de mueble (en orden de preferencia).
var preferencias = []struct {
	palabras []string
	tipos    []string
}{
	{[]string{"helado", "paleta helada", "hielo", "congelad"}, []string{"congelador", "refri"}},
	{[]string{"refresco", "agua ", "agua natural", "jugo", "leche", "queso", "crema", "yogur", "cerveza", "bebida", "jamon", "salchicha", "mantequilla"}, []string{"refri"}},
	{[]string{"chamarra", "sudadera", "pijama", "sueter", "chaleco", "vestido", "camisa", "blusa", "abrigo", "saco", "uniforme"}, []string{"perchero", "mesa", "rack"}},
	{[]string{"playera", "jeans", "short", "pantalon", "falda", "calceta"}, []string{"mesa", "perchero", "rack"}},
	{[]string{"cloro", "detergente", "jabon", "shampoo", "papel higienico", "servilleta", "pasta dental", "suavizante", "limpia", "fabuloso", "pinol"}, []string{"anaquel", "gondola", "rack"}},
	{[]string{"papas", "botana", "fritura", "cacahuate", "chicharron", "palomitas"}, []string{"botanero", "gondola", "rack"}},
	{[]string{"dulce", "chicle", "paleta", "chocolate", "pila", "encendedor"}, []string{"mostrador", "botanero", "gondola"}},
	{[]string{"reloj", "lente", "joya", "arete", "collar", "celular", "audifono", "perfume", "cargador"}, []string{"vitrina", "mostrador"}},
	{[]string{"tornillo", "clavo", "pija", "taquete", "llave", "herramienta", "cinta", "llavero", "broche", "cable"}, []string{"ganchera", "cajas", "anaquel"}},
}

func normal(s string) string { return " " + normalizar(s) + " " }

func tiposPara(l Lugar3D, giro string) []string {
	txt := normal(l.Nombre + " " + l.Categoria)
	for _, p := range preferencias {
		for _, w := range p.palabras {
			if strings.Contains(txt, w) {
				return append(p.tipos, "gondola", "anaquel", "cajas", "rack", "tarima", "mesa", "vitrina", "ganchera", "perchero", "mostrador", "botanero", "refri")
			}
		}
	}
	if giro == "mayoreo" {
		return []string{"rack", "tarima", "cajas", "anaquel", "gondola", "mesa", "perchero", "mostrador"}
	}
	return []string{"gondola", "anaquel", "cajas", "mesa", "vitrina", "ganchera", "rack", "tarima", "botanero", "perchero", "mostrador", "refri"}
}

type hueco struct {
	m           *Mueble
	lado, nivel int
	cupo, usado int
}

// nivelOjo: el entrepaño que queda a la altura de los ojos (≈1.4 m); lo que más
// se vende va ahí y luego hacia los lados.
func ordenNiveles(m Mueble) []int {
	n := m.Niveles
	hN := m.Alto / float64(n)
	ojo := max(1, min(n, int(1.4/hN)+1))
	out := []int{}
	for d := 0; len(out) < n; d++ {
		for _, v := range []int{ojo - d, ojo + d} {
			if v >= 1 && v <= n && !contiene(out, v) {
				out = append(out, v)
			}
		}
	}
	return out
}

func contiene(xs []int, v int) bool {
	for _, x := range xs {
		if x == v {
			return true
		}
	}
	return false
}

func cupoNivel(m Mueble) int {
	return max(1, int(math.Floor(m.Ancho*tiposMueble[m.Tipo].porMetro)))
}

// acomodar asigna mueble y entrepaño a los productos sin lugar (o a todos si
// todos=true, menos los que salen de la foto). Las categorías quedan juntas y
// lo más vendido a la altura de los ojos. Devuelve cuántos acomodó.
func acomodar(plano Plano, ls []Lugar3D, giro string, todos bool) map[int64]Lugar3D {
	huecos := map[string][]*hueco{} // por tipo de mueble, en orden de recorrido
	porMueble := map[string][]*hueco{}
	for i := range plano.Muebles {
		m := &plano.Muebles[i]
		lados := tiposMueble[m.Tipo].lados
		for _, n := range ordenNiveles(*m) {
			for lado := 0; lado < lados; lado++ {
				h := &hueco{m: m, lado: lado, nivel: n, cupo: cupoNivel(*m)}
				huecos[m.Tipo] = append(huecos[m.Tipo], h)
				porMueble[m.ID] = append(porMueble[m.ID], h)
			}
		}
	}
	// Lo que ya tiene lugar ocupa su hueco.
	pendientes := []Lugar3D{}
	for _, l := range ls {
		if l.Origen == "foto" || (l.Mueble != "" && !todos) {
			for _, h := range porMueble[l.Mueble] {
				if h.lado == l.Lado && h.nivel == l.Nivel {
					h.usado++
				}
			}
			continue
		}
		pendientes = append(pendientes, l)
	}
	// Por categoría; dentro, lo más vendido primero.
	sort.SliceStable(pendientes, func(i, j int) bool {
		if pendientes[i].Categoria != pendientes[j].Categoria {
			return pendientes[i].Categoria < pendientes[j].Categoria
		}
		return pendientes[i].Vendidos30 > pendientes[j].Vendidos30
	})
	out := map[int64]Lugar3D{}
	ultimoDeCategoria := map[string]string{} // categoría → mueble donde quedó
	orden := map[string]float64{}
	for _, l := range pendientes {
		tipos := tiposPara(l, giro)
		var elegido *hueco
		// Primero junto a los de su categoría si su mueble es de un tipo aceptable.
		if mid, ok := ultimoDeCategoria[l.Categoria]; ok {
			for _, h := range porMueble[mid] {
				if h.usado < h.cupo && indice(tipos, h.m.Tipo) <= 1 {
					elegido = h
					break
				}
			}
		}
		for _, t := range tipos {
			if elegido != nil {
				break
			}
			// El mueble con más espacio libre de ese tipo, para repartir.
			mejor := -1
			for _, h := range huecos[t] {
				if h.usado >= h.cupo {
					continue
				}
				libre := 0
				for _, o := range porMueble[h.m.ID] {
					libre += o.cupo - o.usado
				}
				if libre > mejor {
					mejor = libre
					elegido = h
				}
			}
			if elegido != nil {
				// dentro del mueble, el primer hueco libre en orden de altura
				for _, h := range porMueble[elegido.m.ID] {
					if h.usado < h.cupo {
						elegido = h
						break
					}
				}
			}
		}
		if elegido == nil {
			continue // ya no cabe; queda "sin lugar"
		}
		elegido.usado++
		clave := fmt.Sprintf("%s/%d/%d", elegido.m.ID, elegido.lado, elegido.nivel)
		orden[clave]++
		l.Mueble, l.Lado, l.Nivel, l.Posicion, l.Origen = elegido.m.ID, elegido.lado, elegido.nivel, orden[clave], ""
		ultimoDeCategoria[l.Categoria] = elegido.m.ID
		out[l.ID] = l
	}
	repartir(plano, pendientes, out, huecos)
	return out
}

// repartir: si a un lado de un mueble le tocaron pocos productos, no los deja
// todos en el mismo entrepaño: los reparte por niveles (lo primero a la altura
// de los ojos), sin pasarse del cupo de cada entrepaño.
func repartir(plano Plano, orden []Lugar3D, out map[int64]Lugar3D, huecos map[string][]*hueco) {
	type clave struct {
		mueble string
		lado   int
	}
	grupos := map[clave][]int64{}
	var claves []clave
	for _, l := range orden {
		n, ok := out[l.ID]
		if !ok {
			continue
		}
		k := clave{n.Mueble, n.Lado}
		if _, ya := grupos[k]; !ya {
			claves = append(claves, k)
		}
		grupos[k] = append(grupos[k], l.ID)
	}
	for _, k := range claves {
		var m Mueble
		for _, x := range plano.Muebles {
			if x.ID == k.mueble {
				m = x
			}
		}
		ids := grupos[k]
		// Lugar libre en cada nivel, sin contar lo que se va a repartir.
		libre := map[int]int{}
		for _, h := range huecos[m.Tipo] {
			if h.m.ID == m.ID && h.lado == k.lado {
				libre[h.nivel] = h.cupo - h.usado
			}
		}
		for _, id := range ids {
			libre[out[id].Nivel]++
		}
		niveles := ordenNiveles(m)
		base, extra := len(ids)/len(niveles), len(ids)%len(niveles)
		i := 0
		for j, nv := range niveles {
			cuantos := base
			if j < extra {
				cuantos++
			}
			cuantos = min(cuantos, libre[nv])
			for c := 1; c <= cuantos && i < len(ids); c++ {
				l := out[ids[i]]
				l.Nivel, l.Posicion = nv, float64(c)
				out[ids[i]] = l
				i++
			}
		}
		// Lo que no entró parejo, donde quede espacio.
		for _, nv := range niveles {
			usados := 0
			for _, id := range ids[:i] {
				if out[id].Nivel == nv {
					usados++
				}
			}
			for ; i < len(ids) && usados < libre[nv]; i++ {
				usados++
				l := out[ids[i]]
				l.Nivel, l.Posicion = nv, float64(usados)
				out[ids[i]] = l
			}
		}
	}
}

func indice(xs []string, v string) int {
	for i, x := range xs {
		if x == v {
			return i
		}
	}
	return 99
}

func (a *App) giroDe(tienda int64) string {
	var g string
	a.db.QueryRow("SELECT giro FROM tiendas WHERE id = ?", tienda).Scan(&g)
	return g
}

// planoDe lee el plano; si la tienda no tiene, arma uno de su giro, acomoda
// sus productos y lo guarda (así la primera vez ya se ve surtida).
func (a *App) planoDe(tienda int64) (Plano, error) {
	p, ok, err := a.leerPlano(tienda)
	if err != nil || ok {
		return p, err
	}
	ans, err := a.anaquelesDe(tienda)
	if err != nil {
		return p, err
	}
	giro := a.giroDe(tienda)
	p = planoAuto(giro, ans)
	if err := a.guardarPlano(a.db, tienda, p); err != nil {
		return p, err
	}
	ls, err := a.lugares(tienda, p)
	if err != nil {
		return p, err
	}
	return p, a.guardarLugares(tienda, acomodar(p, ls, giro, false))
}

func (a *App) guardarLugares(tienda int64, ls map[int64]Lugar3D) error {
	if len(ls) == 0 {
		return nil
	}
	tx, err := a.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, l := range ls {
		if _, err := tx.Exec("UPDATE productos SET lugar_mueble = ?, lugar_lado = ?, lugar_nivel = ?, lugar_pos = ? WHERE id = ? AND tienda_id = ?",
			l.Mueble, l.Lado, l.Nivel, l.Posicion, l.ID, tienda); err != nil {
			return err
		}
	}
	return tx.Commit()
}

type respuestaBodega struct {
	Plano     Plano          `json:"plano"`
	Productos []Lugar3D      `json:"productos"`
	Anaqueles []anaquelCorto `json:"anaqueles"`
	Tienda    map[string]any `json:"tienda"`
}

type anaquelCorto struct {
	ID        int64  `json:"id"`
	Nombre    string `json:"nombre"`
	Faltantes int    `json:"faltantes"`
	UltimaEn  *int64 `json:"ultima_en"`
}

func (a *App) bodega(w http.ResponseWriter, r *http.Request) {
	tienda := tiendaDe(r)
	p, err := a.planoDe(tienda)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	a.responderBodega(w, tienda, p)
}

func (a *App) responderBodega(w http.ResponseWriter, tienda int64, p Plano) {
	ls, err := a.lugares(tienda, p)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	ans, err := a.anaquelesDe(tienda)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	cortos := []anaquelCorto{}
	for _, an := range ans {
		cortos = append(cortos, anaquelCorto{an.ID, an.Nombre, an.Faltantes, an.UltimaEn})
	}
	t, _ := a.tienda(tienda)
	manejar(w, respuestaBodega{p, ls, cortos, map[string]any{"nombre": t.Nombre, "giro": t.Giro, "slug": t.Slug}}, nil)
}

func (a *App) guardarPlanoHTTP(w http.ResponseWriter, r *http.Request) {
	var p Plano
	if err := leerJSON(r, &p); err != nil {
		manejar(w, nil, err)
		return
	}
	if err := p.limpiar(); err != nil {
		manejar(w, nil, err)
		return
	}
	tienda := tiendaDe(r)
	// Un anaquel vinculado debe ser de la tienda.
	for i := range p.Muebles {
		if id := p.Muebles[i].AnaquelID; id != nil && a.anaquelDeTienda(*id, tienda) != nil {
			p.Muebles[i].AnaquelID = nil
		}
	}
	ids := []any{tienda}
	marcas := []string{}
	for _, m := range p.Muebles {
		ids = append(ids, m.ID)
		marcas = append(marcas, "?")
	}
	tx, err := a.db.Begin()
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer tx.Rollback()
	if err := a.guardarPlano(tx, tienda, p); err != nil {
		manejar(w, nil, err)
		return
	}
	// Lo que estaba en un mueble que se quitó queda sin lugar.
	q := "UPDATE productos SET lugar_mueble = '', lugar_nivel = 0 WHERE tienda_id = ? AND lugar_mueble != ''"
	if len(marcas) > 0 {
		q += " AND lugar_mueble NOT IN (" + strings.Join(marcas, ",") + ")"
	}
	if _, err := tx.Exec(q, ids...); err != nil {
		manejar(w, nil, err)
		return
	}
	// Y lo que quedó en un entrepaño que ya no existe baja al más alto que hay.
	for _, m := range p.Muebles {
		lados := tiposMueble[m.Tipo].lados
		if _, err := tx.Exec("UPDATE productos SET lugar_nivel = ? WHERE tienda_id = ? AND lugar_mueble = ? AND lugar_nivel > ?", m.Niveles, tienda, m.ID, m.Niveles); err != nil {
			manejar(w, nil, err)
			return
		}
		if _, err := tx.Exec("UPDATE productos SET lugar_lado = 0 WHERE tienda_id = ? AND lugar_mueble = ? AND lugar_lado >= ?", tienda, m.ID, lados); err != nil {
			manejar(w, nil, err)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		manejar(w, nil, err)
		return
	}
	a.responderBodega(w, tienda, p)
}

type pedidoLugar struct {
	ProductoID int64   `json:"producto_id"`
	Mueble     string  `json:"mueble"`
	Lado       int     `json:"lado"`
	Nivel      int     `json:"nivel"`
	Posicion   float64 `json:"posicion"`
}

func (a *App) guardarLugaresHTTP(w http.ResponseWriter, r *http.Request) {
	var ps []pedidoLugar
	if err := leerJSON(r, &ps); err != nil {
		manejar(w, nil, err)
		return
	}
	if len(ps) > 2000 {
		manejar(w, nil, fallo(400, "Son demasiados productos"))
		return
	}
	tienda := tiendaDe(r)
	p, err := a.planoDe(tienda)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	muebles := map[string]Mueble{}
	for _, m := range p.Muebles {
		muebles[m.ID] = m
	}
	ls := map[int64]Lugar3D{}
	for _, x := range ps {
		l := Lugar3D{ID: x.ProductoID}
		if m, ok := muebles[x.Mueble]; ok {
			l.Mueble = m.ID
			l.Lado = max(0, min(tiposMueble[m.Tipo].lados-1, x.Lado))
			l.Nivel = max(1, min(m.Niveles, x.Nivel))
			l.Posicion = numeroEn(x.Posicion, 0, 10000)
		} else if x.Mueble != "" {
			manejar(w, nil, fallo(400, "Ese mueble no está en el plano"))
			return
		}
		ls[l.ID] = l
	}
	if err := a.guardarLugares(tienda, ls); err != nil {
		manejar(w, nil, err)
		return
	}
	a.responderBodega(w, tienda, p)
}

// acomodarHTTP: {"todos": true} reacomoda todo (menos lo de la foto);
// {"plano_nuevo": true} además vuelve a armar el local según el giro.
func (a *App) acomodarHTTP(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Todos      bool `json:"todos"`
		PlanoNuevo bool `json:"plano_nuevo"`
	}
	leerJSON(r, &d)
	tienda := tiendaDe(r)
	giro := a.giroDe(tienda)
	var p Plano
	var err error
	if d.PlanoNuevo {
		ans, err := a.anaquelesDe(tienda)
		if err != nil {
			manejar(w, nil, err)
			return
		}
		p = planoAuto(giro, ans)
		if err := a.guardarPlano(a.db, tienda, p); err != nil {
			manejar(w, nil, err)
			return
		}
		d.Todos = true
	} else if p, err = a.planoDe(tienda); err != nil {
		manejar(w, nil, err)
		return
	}
	ls, err := a.lugares(tienda, p)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	nuevos := acomodar(p, ls, giro, d.Todos)
	if d.Todos {
		// Lo que ya no cupo queda sin lugar (no en un mueble viejo).
		for _, l := range ls {
			if _, ok := nuevos[l.ID]; !ok && l.Origen != "foto" && l.Mueble != "" {
				nuevos[l.ID] = Lugar3D{ID: l.ID}
			}
		}
	}
	if err := a.guardarLugares(tienda, nuevos); err != nil {
		manejar(w, nil, err)
		return
	}
	a.responderBodega(w, tienda, p)
}

// bodegaPublica: el recorrido 3D de una tienda para cualquiera. Sin
// existencias exactas (salvo lo que la tienda publicó en su vitrina), sin
// mínimos ni ventas.
func (a *App) bodegaPublica(w http.ResponseWriter, r *http.Request) {
	var id int64
	if err := a.db.QueryRow("SELECT id FROM tiendas WHERE slug = ?", r.PathValue("slug")).Scan(&id); err != nil {
		manejar(w, nil, fallo(404, "Esa tienda no existe"))
		return
	}
	p, err := a.planoDe(id)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	ls, err := a.lugares(id, p)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	for i := range ls {
		l := &ls[i]
		l.Vendidos30, l.StockMin, l.Codigo = 0, 0, ""
		if l.Foto != "" && !l.Publicado {
			// Para el público manda la foto del día (igual que la página de la tienda).
			l.Stock = nil
		} else if !l.Publicado && l.Stock != nil {
			// Solo "hay / queda poco / se acabó", con un número que da la forma.
			s := min(*l.Stock, 24)
			l.Stock = &s
		}
		if !l.Publicado {
			l.PrecioMayoreoC, l.MinimoMayoreo = 0, 0
		}
	}
	t, _ := a.tienda(id)
	for i := range p.Muebles {
		p.Muebles[i].AnaquelID = nil
	}
	manejar(w, respuestaBodega{p, ls, []anaquelCorto{}, map[string]any{
		"nombre": t.Nombre, "giro": t.Giro, "slug": t.Slug, "telefono": t.Telefono, "direccion": t.Direccion,
	}}, nil)
}
