package main

import (
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Productor struct {
	ID          int64    `json:"id"`
	Nombre      string   `json:"nombre"`
	Telefono    string   `json:"telefono"`
	Productos   string   `json:"productos"`
	Descripcion string   `json:"descripcion"`
	Lugar       string   `json:"lugar"`
	Lat         float64  `json:"lat"`
	Lng         float64  `json:"lng"`
	DistanciaKm *float64 `json:"distancia_km,omitempty"`
}

func (a *App) productoresTodos() ([]Productor, error) {
	rows, err := a.db.Query("SELECT id, nombre, telefono, productos, descripcion, lugar, lat, lng FROM productores ORDER BY nombre")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Productor{}
	for rows.Next() {
		var p Productor
		rows.Scan(&p.ID, &p.Nombre, &p.Telefono, &p.Productos, &p.Descripcion, &p.Lugar, &p.Lat, &p.Lng)
		out = append(out, p)
	}
	return out, rows.Err()
}

func coordenadas(r *http.Request) (float64, float64, bool) {
	lat, e1 := strconv.ParseFloat(r.URL.Query().Get("lat"), 64)
	lng, e2 := strconv.ParseFloat(r.URL.Query().Get("lng"), 64)
	return lat, lng, e1 == nil && e2 == nil
}

// GET /api/publico/productores?lat=&lng=&q=huevo
func (a *App) productoresCercanos(w http.ResponseWriter, r *http.Request) {
	todos, err := a.productoresTodos()
	if err != nil {
		manejar(w, nil, err)
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	lat, lng, conPos := coordenadas(r)
	out := []Productor{}
	for _, p := range todos {
		if q != "" && !coincide(q, p.Productos+" "+p.Nombre+" "+p.Descripcion) {
			continue
		}
		if conPos {
			d := distanciaKm(lat, lng, p.Lat, p.Lng)
			p.DistanciaKm = &d
		}
		out = append(out, p)
	}
	if conPos {
		sort.Slice(out, func(i, j int) bool { return *out[i].DistanciaKm < *out[j].DistanciaKm })
	}
	manejar(w, out, nil)
}

// Límite sencillo para el registro público: 5 por hora por IP.
var limitador = struct {
	sync.Mutex
	m map[string][]time.Time
}{m: map[string][]time.Time{}}

func permitido(r *http.Request) bool {
	ip := r.Header.Get("X-Forwarded-For")
	if i := strings.Index(ip, ","); i >= 0 {
		ip = ip[:i]
	}
	if ip == "" {
		ip, _, _ = net.SplitHostPort(r.RemoteAddr)
	}
	limitador.Lock()
	defer limitador.Unlock()
	corte := time.Now().Add(-time.Hour)
	recientes := limitador.m[ip][:0]
	for _, t := range limitador.m[ip] {
		if t.After(corte) {
			recientes = append(recientes, t)
		}
	}
	if len(recientes) >= 5 {
		limitador.m[ip] = recientes
		return false
	}
	limitador.m[ip] = append(recientes, time.Now())
	return true
}

func recortarTexto(s string, n int) string {
	s = strings.TrimSpace(s)
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

func (a *App) registrarProductor(w http.ResponseWriter, r *http.Request) {
	var p Productor
	if err := leerJSON(r, &p); err != nil {
		manejar(w, nil, err)
		return
	}
	p.Nombre = recortarTexto(p.Nombre, 80)
	p.Telefono = recortarTexto(p.Telefono, 20)
	p.Productos = recortarTexto(p.Productos, 200)
	p.Descripcion = recortarTexto(p.Descripcion, 400)
	p.Lugar = recortarTexto(p.Lugar, 80)
	digitos := 0
	for _, c := range p.Telefono {
		if c >= '0' && c <= '9' {
			digitos++
		}
	}
	switch {
	case p.Nombre == "":
		manejar(w, nil, fallo(400, "Escribe tu nombre o el de tu rancho/taller"))
		return
	case digitos < 10:
		manejar(w, nil, fallo(400, "Escribe un teléfono de WhatsApp a 10 dígitos"))
		return
	case p.Productos == "":
		manejar(w, nil, fallo(400, "Escribe qué produces (ej. huevo, queso, miel)"))
		return
	case p.Lat < -90 || p.Lat > 90 || p.Lng < -180 || p.Lng > 180 || (p.Lat == 0 && p.Lng == 0):
		manejar(w, nil, fallo(400, "Marca en el mapa dónde estás"))
		return
	}
	if !permitido(r) {
		manejar(w, nil, fallo(429, "Demasiados registros desde aquí, intenta más tarde"))
		return
	}
	tok := tokenAleatorio()
	res, err := a.db.Exec(`INSERT INTO productores (nombre, telefono, productos, descripcion, lugar, lat, lng, token_hash, creado)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`, p.Nombre, p.Telefono, p.Productos, p.Descripcion, p.Lugar, p.Lat, p.Lng, hashToken(tok), ahora())
	if err == nil {
		p.ID, _ = res.LastInsertId()
	}
	manejar(w, p, err)
}

type TiendaCercana struct {
	Nombre      string   `json:"nombre"`
	Slug        string   `json:"slug"`
	Direccion   string   `json:"direccion"`
	Lat         float64  `json:"lat"`
	Lng         float64  `json:"lng"`
	DistanciaKm *float64 `json:"distancia_km,omitempty"`
}

func (a *App) tiendasCercanas(w http.ResponseWriter, r *http.Request) {
	rows, err := a.db.Query("SELECT nombre, slug, direccion, lat, lng FROM tiendas WHERE lat IS NOT NULL AND lng IS NOT NULL")
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer rows.Close()
	lat, lng, conPos := coordenadas(r)
	out := []TiendaCercana{}
	for rows.Next() {
		var t TiendaCercana
		rows.Scan(&t.Nombre, &t.Slug, &t.Direccion, &t.Lat, &t.Lng)
		if conPos {
			d := distanciaKm(lat, lng, t.Lat, t.Lng)
			t.DistanciaKm = &d
		}
		out = append(out, t)
	}
	if conPos {
		sort.Slice(out, func(i, j int) bool { return *out[i].DistanciaKm < *out[j].DistanciaKm })
	}
	manejar(w, out, rows.Err())
}

type ProductoPublico struct {
	Nombre    string `json:"nombre"`
	Categoria string `json:"categoria"`
	PrecioC   int64  `json:"precio_c"`
	Estado    string `json:"estado"` // hay | poco | agotado
	Pedido    bool   `json:"pedido"` // agotado pero ya viene en camino
	Productor string `json:"productor,omitempty"`
	Lugar     string `json:"lugar,omitempty"`
}

// tiendaPublica: lo que hay hoy en una tienda, según la última foto de cada
// anaquel (o el conteo de la app), y lo que se produce aquí cerca.
func (a *App) tiendaPublica(w http.ResponseWriter, r *http.Request) {
	var t Tienda
	err := a.db.QueryRow("SELECT id, nombre, slug, telefono, direccion, lat, lng, giro, descripcion FROM tiendas WHERE slug = ?", r.PathValue("slug")).
		Scan(&t.ID, &t.Nombre, &t.Slug, &t.Telefono, &t.Direccion, &t.Lat, &t.Lng, &t.Giro, &t.Descripcion)
	if err != nil {
		manejar(w, nil, fallo(404, "Esa tienda no existe"))
		return
	}
	nivel := map[int64]string{}
	rows, err := a.db.Query(`SELECT z.producto_id, z.nivel FROM zonas z JOIN anaqueles an ON an.id = z.anaquel_id
		WHERE an.tienda_id = ? AND z.producto_id IS NOT NULL`, t.ID)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	peor := map[string]int{"lleno": 0, "poco": 1, "vacio": 2}
	for rows.Next() {
		var pid int64
		var n string
		rows.Scan(&pid, &n)
		if prev, ok := nivel[pid]; !ok || peor[n] < peor[prev] {
			nivel[pid] = n // con varios huecos del mismo producto, cuenta el más lleno
		}
	}
	rows.Close()
	pedidos := map[int64]bool{}
	rows, err = a.db.Query(`SELECT i.producto_id FROM pedido_items i JOIN pedidos p ON p.id = i.pedido_id
		WHERE p.tienda_id = ? AND p.estado IN ('enviado','confirmado') AND i.recibido = 0 AND i.producto_id IS NOT NULL`, t.ID)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	for rows.Next() {
		var pid int64
		rows.Scan(&pid)
		pedidos[pid] = true
	}
	rows.Close()
	rows, err = a.db.Query(`SELECT p.id, p.nombre, p.categoria, p.precio_c, p.stock, COALESCE(pr.nombre, ''), COALESCE(pr.lugar, '')
		FROM productos p LEFT JOIN productores pr ON pr.id = p.productor_id WHERE p.tienda_id = ? ORDER BY p.nombre COLLATE NOCASE`, t.ID)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer rows.Close()
	hay, cerca := []ProductoPublico{}, []ProductoPublico{}
	for rows.Next() {
		var id int64
		var stock *int64
		var p ProductoPublico
		rows.Scan(&id, &p.Nombre, &p.Categoria, &p.PrecioC, &stock, &p.Productor, &p.Lugar)
		switch n, conFoto := nivel[id]; {
		case conFoto && n == "lleno":
			p.Estado = "hay"
		case conFoto && n == "poco":
			p.Estado = "poco"
		case conFoto:
			p.Estado = "agotado"
		case stock != nil && *stock > 0:
			p.Estado = "hay"
		case stock != nil:
			p.Estado = "agotado"
		default:
			continue // sin foto ni conteo: no sabemos, mejor no prometer
		}
		p.Pedido = p.Estado == "agotado" && pedidos[id]
		hay = append(hay, p)
		if p.Productor != "" {
			cerca = append(cerca, p)
		}
	}
	var ultima *int64
	a.db.QueryRow("SELECT MAX(ultima_en) FROM anaqueles WHERE tienda_id = ?", t.ID).Scan(&ultima)
	anuncios, _ := a.anunciosDe(t.ID, true)
	vitrina, _ := a.buscarRed(filtroRed{yo: -1, tipo: "producto", limite: 0})
	mia := []Resultado{}
	for _, v := range vitrina {
		if v.Tienda != nil && v.Tienda.ID == t.ID {
			mia = append(mia, v)
		}
	}
	manejar(w, map[string]any{"tienda": t, "productos": hay, "hecho_cerca": cerca, "actualizado": ultima,
		"anuncios": anuncios, "vitrina": mia}, nil)
}
