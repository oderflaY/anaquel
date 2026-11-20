package main

import (
	"fmt"
	"net/http"
	"regexp"
	"sort"
	"strings"
)

// Inicio de la app: lo que conviene surtir y quién lo tiene cerca. Junta lo
// que se te está acabando con las vitrinas de la red, las promociones y las
// tiendas/proveedores de tu zona, para que el tendero abra la app y vea
// directo dónde comprar más barato.

type OfertaInicio struct {
	Resultado
	Motivo   string `json:"motivo"`
	Ganancia int64  `json:"ganancia_pct,omitempty"` // cuánto ganas por pieza si lo revendes a tu precio
}

type SurtirInicio struct {
	ProductoID int64          `json:"producto_id"`
	Nombre     string         `json:"nombre"`
	Stock      *int64         `json:"stock"`
	StockMin   int64          `json:"stock_min"`
	Unidad     string         `json:"unidad"`
	Nivel      string         `json:"nivel"` // vacio | poco | bajo
	PrecioC    int64          `json:"mi_precio_c"`
	Ofertas    []OfertaInicio `json:"ofertas"`
}

type TiendaZona struct {
	TiendaCorta
	Descripcion string   `json:"descripcion"`
	DistanciaKm *float64 `json:"distancia_km,omitempty"`
	Productos   int      `json:"productos"`
	ChatID      int64    `json:"chat_id,omitempty"`
}

// ProveedorGrande: mayorista con catálogo amplio y sus ofertas más fuertes.
type ProveedorGrande struct {
	TiendaZona
	Afines     int         `json:"afines"` // productos de categorías que tú manejas
	Destacados []Resultado `json:"destacados"`
	Promocion  string      `json:"promocion,omitempty"`
}

type Inicio struct {
	Proveedores  []ProveedorGrande `json:"proveedores"`
	Saludo       string            `json:"saludo"`
	ParaSurtir   []SurtirInicio    `json:"para_surtir"`
	Convienen    []OfertaInicio    `json:"te_convienen"`
	Promociones  []Resultado       `json:"promociones"`
	Buscan       []Resultado       `json:"buscan"` // otras tiendas buscan algo que tú vendes
	Tiendas      []TiendaZona      `json:"tiendas"`
	ChatsSinLeer int               `json:"chats_sin_leer"`
	Pendientes   int               `json:"tratos_pendientes"`
}

func costoUnitario(r Resultado) int64 {
	if r.PrecioMayoreoC > 0 {
		return r.PrecioMayoreoC
	}
	return r.PrecioC
}

func ganancia(miPrecio int64, r Resultado, miUnidad string) int64 {
	c := costoUnitario(r)
	// Solo se compara pieza con pieza (una caja de 12 contra una pieza engaña).
	if miPrecio <= 0 || c <= 0 || c >= miPrecio || (r.Unidad != "" && miUnidad != "" && r.Unidad != miUnidad) {
		return 0
	}
	return (miPrecio - c) * 100 / miPrecio
}

func (a *App) inicio(w http.ResponseWriter, r *http.Request) {
	out, err := a.datosInicio(tiendaDe(r))
	manejar(w, out, err)
}

func (a *App) datosInicio(yo int64) (Inicio, error) {
	lat, lng := a.coordsDe(yo)
	out := Inicio{Proveedores: []ProveedorGrande{}, ParaSurtir: []SurtirInicio{}, Convienen: []OfertaInicio{}, Promociones: []Resultado{}, Buscan: []Resultado{}, Tiendas: []TiendaZona{}}

	var nombre, giro string
	a.db.QueryRow("SELECT nombre, giro FROM tiendas WHERE id = ?", yo).Scan(&nombre, &giro)
	out.Saludo = nombre

	// Todo lo que está en las vitrinas de la red (una sola consulta).
	vitrinas, err := a.buscarRed(filtroRed{yo: yo, tipo: "producto", lat: lat, lng: lng})
	if err != nil {
		return out, err
	}
	mios, _ := a.productos(yo)
	categorias := map[string]bool{}
	nombresMios := map[string]bool{}
	for _, p := range mios {
		if p.Categoria != "" {
			categorias[normalizar(p.Categoria)] = true
		}
		nombresMios[normalizar(p.Nombre)] = true
	}

	// 1) Lo que se te acaba y quién lo tiene.
	usados := map[int64]bool{}
	faltan, _ := a.calcularFaltantes(yo)
	for _, f := range faltan {
		if len(out.ParaSurtir) >= 8 {
			break
		}
		p := f.Producto
		claves := clavesDe(p.Nombre)
		if len(claves) == 0 {
			continue
		}
		s := SurtirInicio{ProductoID: p.ID, Nombre: p.Nombre, Stock: p.Stock, StockMin: p.StockMin, Unidad: p.Unidad, Nivel: f.Nivel, PrecioC: p.PrecioC, Ofertas: []OfertaInicio{}}
		var cand []OfertaInicio
		for _, v := range vitrinas {
			if !mismoProducto(p.Nombre, v.Titulo) {
				continue
			}
			o := OfertaInicio{Resultado: v, Ganancia: ganancia(p.PrecioC, v, p.Unidad)}
			o.Motivo = "Te quedan pocos"
			if p.Stock != nil {
				o.Motivo = "Te quedan " + cantidadTxt(*p.Stock)
				if *p.Stock <= 0 {
					o.Motivo = "Se te acabó"
				}
			}
			if o.Ganancia > 0 {
				o.Motivo += fmt.Sprintf(" · ganas %d%% por pieza", o.Ganancia)
			}
			cand = append(cand, o)
		}
		// Más barato primero; a igual precio, el más cercano.
		sort.SliceStable(cand, func(i, j int) bool {
			ci, cj := costoUnitario(cand[i].Resultado), costoUnitario(cand[j].Resultado)
			if ci != cj {
				return ci < cj
			}
			return distancia(cand[i].Resultado) < distancia(cand[j].Resultado)
		})
		if len(cand) > 3 {
			cand = cand[:3]
		}
		for _, c := range cand {
			usados[c.ID] = true
		}
		s.Ofertas = append(s.Ofertas, cand...)
		out.ParaSurtir = append(out.ParaSurtir, s)
	}
	// Primero lo que sí se puede surtir en la red.
	sort.SliceStable(out.ParaSurtir, func(i, j int) bool {
		return len(out.ParaSurtir[i].Ofertas) > 0 && len(out.ParaSurtir[j].Ofertas) == 0
	})

	// 2) Te convienen: de las categorías que manejas o de tu giro, lo más cerca.
	for _, v := range vitrinas {
		if usados[v.ID] {
			continue
		}
		o := OfertaInicio{Resultado: v}
		cat := ""
		for c := range categorias {
			if c != "" && strings.Contains(v.texto, c) {
				cat = c
				break
			}
		}
		switch {
		case nombresMios[normalizar(v.Titulo)]:
			o.Motivo = "Lo vendes en tu tienda"
		case cat != "":
			o.Motivo = "Manejas " + cat
		case v.Tienda != nil && v.Tienda.Giro == "mayoreo":
			o.Motivo = "Precio de mayoreo"
		default:
			continue
		}
		if v.MinimoMayoreo > 0 && v.PrecioMayoreoC > 0 && v.PrecioC > v.PrecioMayoreoC {
			o.Motivo += fmt.Sprintf(" · %d%% menos por mayoreo", (v.PrecioC-v.PrecioMayoreoC)*100/v.PrecioC)
		}
		out.Convienen = append(out.Convienen, o)
	}
	sort.SliceStable(out.Convienen, func(i, j int) bool {
		pi, pj := prioridadMotivo(out.Convienen[i].Motivo), prioridadMotivo(out.Convienen[j].Motivo)
		if pi != pj {
			return pi < pj
		}
		return distancia(out.Convienen[i].Resultado) < distancia(out.Convienen[j].Resultado)
	})
	if len(out.Convienen) > 12 {
		out.Convienen = out.Convienen[:12]
	}

	// 3) Promociones y servicios de la zona.
	anuncios, _ := a.buscarRed(filtroRed{yo: yo, tipo: "anuncio", lat: lat, lng: lng})
	sort.SliceStable(anuncios, func(i, j int) bool { return distancia(anuncios[i]) < distancia(anuncios[j]) })
	if len(anuncios) > 8 {
		anuncios = anuncios[:8]
	}
	out.Promociones = append(out.Promociones, anuncios...)

	// 4) Tiendas que buscan algo que yo vendo (oportunidad de venta).
	sols, _ := a.buscarRed(filtroRed{yo: yo, tipo: "solicitud", lat: lat, lng: lng, solicitudes: true})
	for _, s := range sols {
		for n := range nombresMios {
			cl := clavesDe(s.Titulo)
			if len(cl) > 0 && strings.Contains(n, cl[0]) {
				out.Buscan = append(out.Buscan, s)
				break
			}
		}
		if len(out.Buscan) >= 5 {
			break
		}
	}

	// 5) Tiendas y proveedores cercanos (con su chat si ya hay).
	out.Tiendas = a.tiendasDeLaZona(yo, lat, lng, 12)
	out.ChatsSinLeer = a.chatsSinLeer(yo)
	a.db.QueryRow(`SELECT COUNT(*) FROM tratos WHERE (comprador_id = ? OR vendedor_id = ?) AND
		((estado = 'negociando' AND propuso_id != ?) OR (estado = 'aceptado' AND vendedor_id = ?))`, yo, yo, yo, yo).Scan(&out.Pendientes)

	// 6) Proveedores grandes: mayoristas con 10+ productos en vitrina.
	porTienda := map[int64][]Resultado{}
	for _, v := range vitrinas {
		if v.Tienda != nil && v.Tienda.Giro == "mayoreo" {
			porTienda[v.Tienda.ID] = append(porTienda[v.Tienda.ID], v)
		}
	}
	for _, t := range a.tiendasDeLaZona(yo, lat, lng, 50) {
		vs := porTienda[t.ID]
		if len(vs) < 10 {
			continue
		}
		pg := ProveedorGrande{TiendaZona: t, Destacados: []Resultado{}}
		for _, v := range vs {
			for c := range categorias {
				if c != "" && strings.Contains(v.texto, c) {
					pg.Afines++
					break
				}
			}
		}
		// Lo que más baja de precio por mayoreo, primero lo que tú manejas.
		sort.SliceStable(vs, func(i, j int) bool {
			ai, aj := afin(vs[i], categorias), afin(vs[j], categorias)
			if ai != aj {
				return ai
			}
			return descuento(vs[i]) > descuento(vs[j])
		})
		pg.Destacados = append(pg.Destacados, vs[:min(4, len(vs))]...)
		a.db.QueryRow("SELECT titulo || ': ' || precio FROM anuncios WHERE tienda_id = ? AND activo = 1 AND tipo = 'promocion' ORDER BY creado DESC LIMIT 1", t.ID).Scan(&pg.Promocion)
		out.Proveedores = append(out.Proveedores, pg)
	}
	sort.SliceStable(out.Proveedores, func(i, j int) bool { return out.Proveedores[i].Afines > out.Proveedores[j].Afines })
	// Si hay mayoristas con lo que vendes, no estorban los de otro giro.
	if len(out.Proveedores) > 0 && out.Proveedores[0].Afines > 0 {
		n := 0
		for _, p := range out.Proveedores {
			if p.Afines > 0 {
				out.Proveedores[n] = p
				n++
			}
		}
		out.Proveedores = out.Proveedores[:n]
	}
	return out, nil
}

func afin(r Resultado, categorias map[string]bool) bool {
	for c := range categorias {
		if c != "" && strings.Contains(r.texto, c) {
			return true
		}
	}
	return false
}

// descuento: % que baja el precio al comprar por mayoreo.
func descuento(r Resultado) int64 {
	if r.PrecioC <= 0 || r.PrecioMayoreoC <= 0 || r.PrecioMayoreoC >= r.PrecioC {
		return 0
	}
	return (r.PrecioC - r.PrecioMayoreoC) * 100 / r.PrecioC
}

var reTamano = regexp.MustCompile(`(\d+(?:[.,]\d+)?)\s*(kg|g|gr|l|lt|ml|piezas|pzas|pz|rollos|hojas|pliegos)\b`)

// tamano: "1 L", "600 ml", "4 rollos"… normalizado (1l, 600ml, 4rollos).
func tamano(nombre string) string {
	m := reTamano.FindStringSubmatch(normalizar(nombre))
	if m == nil {
		return ""
	}
	u := map[string]string{"gr": "g", "lt": "l", "pzas": "piezas", "pz": "piezas"}[m[2]]
	if u == "" {
		u = m[2]
	}
	return strings.Replace(m[1], ",", ".", 1) + u
}

// mismoProducto: ¿la oferta es el mismo artículo que tengo? Las palabras
// importantes de mi producto deben estar en la oferta (al menos 3 de cada 4)
// y, si los dos dicen presentación, debe ser la misma (leche 1 L ≠ 600 ml).
func mismoProducto(mio, oferta string) bool {
	cm := clavesDe(mio)
	if len(cm) == 0 {
		return false
	}
	o := normalizar(oferta)
	n := 0
	for _, c := range cm {
		if strings.Contains(o, c) {
			n++
		}
	}
	if n*4 < len(cm)*3 || !strings.Contains(o, cm[0]) {
		return false
	}
	tm, to := tamano(mio), tamano(oferta)
	return tm == "" || to == "" || tm == to
}

func prioridadMotivo(m string) int {
	switch {
	case strings.HasPrefix(m, "Lo vendes"):
		return 0
	case strings.HasPrefix(m, "Manejas"):
		return 1
	}
	return 2
}

func distancia(r Resultado) float64 {
	if r.DistanciaKm == nil {
		return 1e9
	}
	return *r.DistanciaKm
}

func (a *App) tiendasDeLaZona(yo int64, lat, lng *float64, limite int) []TiendaZona {
	rows, err := a.db.Query(`SELECT t.id, t.nombre, t.slug, t.giro, t.telefono, t.descripcion, t.lat, t.lng,
		(SELECT COUNT(*) FROM productos p WHERE p.tienda_id = t.id AND p.publicado = 1 AND (p.stock IS NULL OR p.stock > 0) AND `+visibleSQL("p")+`) +
		(SELECT COUNT(*) FROM anuncios n WHERE n.tienda_id = t.id AND n.activo = 1)
		FROM tiendas t WHERE t.id != ?`, yo, yo, yo)
	if err != nil {
		return []TiendaZona{}
	}
	var out []TiendaZona
	for rows.Next() {
		var t TiendaZona
		var la, ln *float64
		rows.Scan(&t.ID, &t.Nombre, &t.Slug, &t.Giro, &t.Telefono, &t.Descripcion, &la, &ln, &t.Productos)
		if t.Productos == 0 {
			continue
		}
		if lat != nil && lng != nil && la != nil && ln != nil {
			d := redondear(distanciaKm(*lat, *lng, *la, *ln))
			t.DistanciaKm = &d
		}
		out = append(out, t)
	}
	rows.Close()
	sort.SliceStable(out, func(i, j int) bool {
		di, dj := 1e9, 1e9
		if out[i].DistanciaKm != nil {
			di = *out[i].DistanciaKm
		}
		if out[j].DistanciaKm != nil {
			dj = *out[j].DistanciaKm
		}
		return di < dj
	})
	if len(out) > limite {
		out = out[:limite]
	}
	for i := range out {
		x, y := min(yo, out[i].ID), max(yo, out[i].ID)
		a.db.QueryRow("SELECT id FROM chats WHERE tienda_a = ? AND tienda_b = ?", x, y).Scan(&out[i].ChatID)
	}
	if out == nil {
		out = []TiendaZona{}
	}
	return out
}

// catalogoDe: GET /api/tiendas/{id}/catalogo → la vitrina de otra tienda (para pedir sin esperar a que te la mande).
// Con id "yo" es mi propia vitrina (para elegir qué mandar en un chat).
func (a *App) catalogoDe(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if r.PathValue("id") == "yo" {
		id, err = tiendaDe(r), nil
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	items, err := a.miCatalogo(id, tiendaDe(r), nil, true)
	if err != nil {
		items = []ItemCatalogo{}
	}
	manejar(w, items, nil)
}
