package main

import (
	"database/sql"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
)

// ---------------- Giros ----------------

var giros = []struct{ Clave, Nombre string }{
	{"abarrotes", "Abarrotes / tiendita"},
	{"ropa", "Ropa y accesorios"},
	{"papeleria", "Papelería"},
	{"ferreteria", "Ferretería"},
	{"farmacia", "Farmacia"},
	{"dulceria", "Dulcería y fiestas"},
	{"comida", "Comida y antojitos"},
	{"mayoreo", "Proveedor / mayoreo"},
	{"servicios", "Servicios"},
	{"otro", "Otro"},
}

func giroValido(g string) string {
	for _, x := range giros {
		if x.Clave == g {
			return g
		}
	}
	return "otro"
}

func nombreGiro(g string) string {
	for _, x := range giros {
		if x.Clave == g {
			return x.Nombre
		}
	}
	return "Otro"
}

func (a *App) listarGiros(w http.ResponseWriter, r *http.Request) { manejar(w, giros, nil) }

// ---------------- Búsqueda en la red ----------------

type TiendaCorta struct {
	ID       int64  `json:"id"`
	Nombre   string `json:"nombre"`
	Slug     string `json:"slug"`
	Giro     string `json:"giro"`
	Telefono string `json:"telefono"`
}

// Resultado: algo que se encontró en la red (producto en vitrina, anuncio,
// solicitud de otra tienda o productor local).
type Resultado struct {
	Tipo           string       `json:"tipo"` // producto | anuncio | solicitud | productor
	ID             int64        `json:"id"`
	Titulo         string       `json:"titulo"`
	Detalle        string       `json:"detalle"`
	Subtipo        string       `json:"subtipo,omitempty"` // anuncio: producto | servicio | promocion
	PrecioC        int64        `json:"precio_c,omitempty"`
	PrecioMayoreoC int64        `json:"precio_mayoreo_c,omitempty"`
	MinimoMayoreo  int64        `json:"minimo_mayoreo,omitempty"`
	Unidad         string       `json:"unidad,omitempty"`
	Disponible     *int64       `json:"disponible,omitempty"`
	Precio         string       `json:"precio,omitempty"`   // anuncio (texto libre)
	Cantidad       int64        `json:"cantidad,omitempty"` // solicitud
	Tienda         *TiendaCorta `json:"tienda,omitempty"`
	Telefono       string       `json:"telefono,omitempty"` // productor
	Lugar          string       `json:"lugar,omitempty"`
	DistanciaKm    *float64     `json:"distancia_km,omitempty"`
	Creado         int64        `json:"creado"`
	puntos         float64
	lat, lng       *float64
	texto          string
}

var vacias = map[string]bool{
	"de": true, "del": true, "la": true, "las": true, "el": true, "los": true, "un": true, "una": true, "unos": true,
	"unas": true, "para": true, "por": true, "con": true, "sin": true, "que": true, "quien": true, "donde": true,
	"busco": true, "buscar": true, "necesito": true, "quiero": true, "tengo": true, "tiene": true, "hay": true,
	"mil": true, "cien": true, "piezas": true, "pieza": true, "pzas": true, "mas": true, "muy": true, "como": true,
	"cual": true, "cuales": true, "mi": true, "mis": true, "me": true, "algo": true, "alguien": true, "vende": true,
	"venda": true, "vender": true, "comprar": true, "consigue": true, "consigueme": true, "buscame": true,
	"proveedor": true, "proveedores": true, "tienda": true, "tiendas": true, "red": true, "favor": true,
	"temporada": true, "esta": true, "este": true, "eso": true, "esto": true, "sus": true, "son": true, "tambien": true,
}

// clavesDe: palabras con significado de una búsqueda, ya normalizadas.
func clavesDe(q string) []string {
	var out []string
	vistas := map[string]bool{}
	for _, p := range strings.FieldsFunc(normalizar(q), func(c rune) bool {
		return !(c >= 'a' && c <= 'z' || c == 'n' || c >= '0' && c <= '9')
	}) {
		if len(p) < 3 || vacias[p] {
			continue
		}
		if _, err := strconv.Atoi(p); err == nil {
			continue
		}
		r := raiz(p)
		if !vistas[r] {
			vistas[r] = true
			out = append(out, r)
		}
	}
	return out
}

func puntaje(claves []string, texto string) float64 {
	if len(claves) == 0 {
		return 1
	}
	n := 0.0
	for i, c := range claves {
		if strings.Contains(texto, c) {
			n++
			if i == 0 {
				n += 0.5 // la primera palabra suele ser lo que se busca ("gorras bordadas")
			}
		}
	}
	return n
}

type filtroRed struct {
	yo          int64 // 0 = visitante (sin sesión)
	q, tipo     string
	lat, lng    *float64
	limite      int
	solicitudes bool
}

// buscarRed busca en vitrinas, anuncios, solicitudes y productores.
func (a *App) buscarRed(f filtroRed) ([]Resultado, error) {
	claves := clavesDe(f.q)
	var cand []Resultado
	quiere := func(t string) bool { return f.tipo == "" || f.tipo == "todo" || f.tipo == t }

	tiendaDe := func(rows *sql.Rows, extra ...any) (*TiendaCorta, *float64, *float64, error) {
		t := &TiendaCorta{}
		var lat, lng *float64
		dest := append(extra, &t.ID, &t.Nombre, &t.Slug, &t.Giro, &t.Telefono, &lat, &lng)
		err := rows.Scan(dest...)
		return t, lat, lng, err
	}
	const colsTienda = "t.id, t.nombre, t.slug, t.giro, t.telefono, t.lat, t.lng"

	if quiere("producto") {
		rows, err := a.db.Query(`SELECT p.id, p.nombre, p.categoria, p.descripcion, p.precio_c, p.precio_mayoreo_c,
			p.minimo_mayoreo, p.unidad, p.stock, p.actualizado, `+colsTienda+`
			FROM productos p JOIN tiendas t ON t.id = p.tienda_id
			WHERE p.publicado = 1 AND p.tienda_id != ? AND (p.stock IS NULL OR p.stock > 0)`, f.yo)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			r := Resultado{Tipo: "producto"}
			var cat string
			t, lat, lng, err := tiendaDe(rows, &r.ID, &r.Titulo, &cat, &r.Detalle, &r.PrecioC, &r.PrecioMayoreoC,
				&r.MinimoMayoreo, &r.Unidad, &r.Disponible, &r.Creado)
			if err != nil {
				rows.Close()
				return nil, err
			}
			r.Tienda, r.lat, r.lng = t, lat, lng
			r.texto = normalizar(r.Titulo + " " + cat + " " + r.Detalle)
			cand = append(cand, r)
		}
		rows.Close()
	}
	if quiere("anuncio") || quiere("servicio") {
		q := `SELECT a.id, a.titulo, a.texto, a.tipo, a.precio, a.creado, ` + colsTienda + `
			FROM anuncios a JOIN tiendas t ON t.id = a.tienda_id WHERE a.activo = 1 AND a.tienda_id != ?`
		if f.tipo == "servicio" {
			q += " AND a.tipo = 'servicio'"
		}
		rows, err := a.db.Query(q, f.yo)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			r := Resultado{Tipo: "anuncio"}
			t, lat, lng, err := tiendaDe(rows, &r.ID, &r.Titulo, &r.Detalle, &r.Subtipo, &r.Precio, &r.Creado)
			if err != nil {
				rows.Close()
				return nil, err
			}
			r.Tienda, r.lat, r.lng = t, lat, lng
			r.texto = normalizar(r.Titulo + " " + r.Detalle)
			cand = append(cand, r)
		}
		rows.Close()
	}
	if f.solicitudes && quiere("solicitud") {
		rows, err := a.db.Query(`SELECT s.id, s.que, s.detalle, s.cantidad, s.creado, `+colsTienda+`
			FROM solicitudes s JOIN tiendas t ON t.id = s.tienda_id WHERE s.estado = 'abierta' AND s.tienda_id != ?`, f.yo)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			r := Resultado{Tipo: "solicitud"}
			t, lat, lng, err := tiendaDe(rows, &r.ID, &r.Titulo, &r.Detalle, &r.Cantidad, &r.Creado)
			if err != nil {
				rows.Close()
				return nil, err
			}
			r.Tienda, r.lat, r.lng = t, lat, lng
			r.texto = normalizar(r.Titulo + " " + r.Detalle)
			cand = append(cand, r)
		}
		rows.Close()
	}
	if quiere("productor") {
		ps, err := a.productoresTodos()
		if err != nil {
			return nil, err
		}
		for _, p := range ps {
			lat, lng := p.Lat, p.Lng
			cand = append(cand, Resultado{
				Tipo: "productor", ID: p.ID, Titulo: p.Nombre, Detalle: p.Productos + ". " + p.Descripcion,
				Telefono: p.Telefono, Lugar: p.Lugar, lat: &lat, lng: &lng,
				texto: normalizar(p.Nombre + " " + p.Productos + " " + p.Descripcion),
			})
		}
	}

	out := []Resultado{}
	for _, r := range cand {
		r.puntos = puntaje(claves, r.texto)
		if r.puntos == 0 {
			continue
		}
		if f.lat != nil && f.lng != nil && r.lat != nil && r.lng != nil {
			d := distanciaKm(*f.lat, *f.lng, *r.lat, *r.lng)
			r.DistanciaKm = &d
		}
		out = append(out, r)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].puntos != out[j].puntos {
			return out[i].puntos > out[j].puntos
		}
		if len(claves) == 0 {
			return out[i].Creado > out[j].Creado
		}
		di, dj := 1e9, 1e9
		if out[i].DistanciaKm != nil {
			di = *out[i].DistanciaKm
		}
		if out[j].DistanciaKm != nil {
			dj = *out[j].DistanciaKm
		}
		return di < dj
	})
	if f.limite > 0 && len(out) > f.limite {
		out = out[:f.limite]
	}
	return out, nil
}

func (a *App) coordsDe(tienda int64) (*float64, *float64) {
	var lat, lng *float64
	a.db.QueryRow("SELECT lat, lng FROM tiendas WHERE id = ?", tienda).Scan(&lat, &lng)
	return lat, lng
}

func (a *App) red(w http.ResponseWriter, r *http.Request) {
	yo := tiendaDe(r)
	lat, lng := a.coordsDe(yo)
	res, err := a.buscarRed(filtroRed{yo: yo, q: r.URL.Query().Get("q"), tipo: r.URL.Query().Get("tipo"),
		lat: lat, lng: lng, limite: 60, solicitudes: true})
	manejar(w, res, err)
}

// redPublica: lo que ven los vecinos sin cuenta (sin solicitudes entre tiendas).
func (a *App) redPublica(w http.ResponseWriter, r *http.Request) {
	var lat, lng *float64
	if la, ln, ok := coordenadas(r); ok {
		lat, lng = &la, &ln
	}
	res, err := a.buscarRed(filtroRed{q: r.URL.Query().Get("q"), tipo: r.URL.Query().Get("tipo"), lat: lat, lng: lng, limite: 60})
	manejar(w, res, err)
}

// ---------------- Anuncios (publicidad) ----------------

type Anuncio struct {
	ID     int64  `json:"id"`
	Tipo   string `json:"tipo"`
	Titulo string `json:"titulo"`
	Texto  string `json:"texto"`
	Precio string `json:"precio"`
	Activo bool   `json:"activo"`
	Creado int64  `json:"creado"`
}

func (a *App) anunciosDe(tienda int64, soloActivos bool) ([]Anuncio, error) {
	q := "SELECT id, tipo, titulo, texto, precio, activo, creado FROM anuncios WHERE tienda_id = ?"
	if soloActivos {
		q += " AND activo = 1"
	}
	rows, err := a.db.Query(q+" ORDER BY id DESC", tienda)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Anuncio{}
	for rows.Next() {
		var x Anuncio
		rows.Scan(&x.ID, &x.Tipo, &x.Titulo, &x.Texto, &x.Precio, &x.Activo, &x.Creado)
		out = append(out, x)
	}
	return out, rows.Err()
}

func (a *App) listarAnuncios(w http.ResponseWriter, r *http.Request) {
	x, err := a.anunciosDe(tiendaDe(r), false)
	manejar(w, x, err)
}

func leerAnuncio(r *http.Request) (Anuncio, error) {
	var x Anuncio
	if err := leerJSON(r, &x); err != nil {
		return x, err
	}
	x.Titulo = recortarTexto(strings.TrimSpace(x.Titulo), 120)
	x.Texto = recortarTexto(strings.TrimSpace(x.Texto), 800)
	x.Precio = recortarTexto(strings.TrimSpace(x.Precio), 80)
	if x.Tipo != "servicio" && x.Tipo != "promocion" {
		x.Tipo = "producto"
	}
	if x.Titulo == "" {
		return x, fallo(400, "Escribe un título para tu anuncio")
	}
	return x, nil
}

func (a *App) crearAnuncio(w http.ResponseWriter, r *http.Request) {
	x, err := leerAnuncio(r)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	var n int
	a.db.QueryRow("SELECT COUNT(*) FROM anuncios WHERE tienda_id = ?", tiendaDe(r)).Scan(&n)
	if n >= 30 {
		manejar(w, nil, fallo(400, "Máximo 30 anuncios por tienda"))
		return
	}
	res, err := a.db.Exec("INSERT INTO anuncios (tienda_id, tipo, titulo, texto, precio, activo, creado) VALUES (?, ?, ?, ?, ?, 1, ?)",
		tiendaDe(r), x.Tipo, x.Titulo, x.Texto, x.Precio, ahora())
	if err == nil {
		x.ID, _ = res.LastInsertId()
		x.Activo = true
		a.anuncioInteresa(tiendaDe(r), x)
	}
	manejar(w, x, err)
}

func (a *App) editarAnuncio(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	var x Anuncio
	if err == nil {
		x, err = leerAnuncio(r)
	}
	if err == nil {
		_, err = a.db.Exec("UPDATE anuncios SET tipo = ?, titulo = ?, texto = ?, precio = ?, activo = ? WHERE id = ? AND tienda_id = ?",
			x.Tipo, x.Titulo, x.Texto, x.Precio, x.Activo, id, tiendaDe(r))
		x.ID = id
	}
	manejar(w, x, err)
}

func (a *App) borrarAnuncio(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err == nil {
		_, err = a.db.Exec("DELETE FROM anuncios WHERE id = ? AND tienda_id = ?", id, tiendaDe(r))
	}
	manejar(w, map[string]bool{"ok": err == nil}, err)
}

// ---------------- Solicitudes ("busco…") ----------------

type Solicitud struct {
	ID        int64  `json:"id"`
	Que       string `json:"que"`
	Cantidad  int64  `json:"cantidad"`
	Detalle   string `json:"detalle"`
	Estado    string `json:"estado"`
	PorAsesor bool   `json:"por_asesor"`
	Creado    int64  `json:"creado"`
	Ofertas   int    `json:"ofertas"` // tratos abiertos ligados a esta solicitud
}

func (a *App) listarSolicitudes(w http.ResponseWriter, r *http.Request) {
	rows, err := a.db.Query(`SELECT s.id, s.que, s.cantidad, s.detalle, s.estado, s.por_asesor, s.creado,
		(SELECT COUNT(*) FROM tratos t WHERE t.solicitud_id = s.id AND t.estado != 'cancelado')
		FROM solicitudes s WHERE s.tienda_id = ? ORDER BY s.estado = 'abierta' DESC, s.id DESC LIMIT 50`, tiendaDe(r))
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer rows.Close()
	out := []Solicitud{}
	for rows.Next() {
		var s Solicitud
		rows.Scan(&s.ID, &s.Que, &s.Cantidad, &s.Detalle, &s.Estado, &s.PorAsesor, &s.Creado, &s.Ofertas)
		out = append(out, s)
	}
	manejar(w, out, rows.Err())
}

func (a *App) verSolicitud(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	var s Solicitud
	t := &TiendaCorta{}
	err = a.db.QueryRow(`SELECT s.id, s.que, s.cantidad, s.detalle, s.estado, s.creado, t.id, t.nombre, t.slug, t.giro, t.telefono
		FROM solicitudes s JOIN tiendas t ON t.id = s.tienda_id WHERE s.id = ?`, id).
		Scan(&s.ID, &s.Que, &s.Cantidad, &s.Detalle, &s.Estado, &s.Creado, &t.ID, &t.Nombre, &t.Slug, &t.Giro, &t.Telefono)
	if err != nil {
		manejar(w, nil, fallo(404, "Esa solicitud ya no existe"))
		return
	}
	manejar(w, map[string]any{"solicitud": s, "tienda": t}, nil)
}

// publicarSolicitud crea la solicitud y busca de inmediato quién la tiene.
func (a *App) publicarSolicitud(tienda int64, que, detalle string, cantidad int64, porAsesor bool) (Solicitud, []Resultado, error) {
	s := Solicitud{Que: recortarTexto(strings.TrimSpace(que), 120), Detalle: recortarTexto(strings.TrimSpace(detalle), 600),
		Cantidad: max(0, cantidad), Estado: "abierta", PorAsesor: porAsesor, Creado: ahora()}
	if len(clavesDe(s.Que)) == 0 {
		return s, nil, fallo(400, "Escribe qué buscas (por ejemplo: gorras)")
	}
	var abiertas int
	a.db.QueryRow("SELECT COUNT(*) FROM solicitudes WHERE tienda_id = ? AND estado = 'abierta'", tienda).Scan(&abiertas)
	if abiertas >= 20 {
		return s, nil, fallo(400, "Tienes 20 solicitudes abiertas; cierra alguna primero")
	}
	res, err := a.db.Exec("INSERT INTO solicitudes (tienda_id, que, cantidad, detalle, por_asesor, creado) VALUES (?, ?, ?, ?, ?, ?)",
		tienda, s.Que, s.Cantidad, s.Detalle, porAsesor, s.Creado)
	if err != nil {
		return s, nil, err
	}
	s.ID, _ = res.LastInsertId()
	encontrados := a.buscarOfertas(s.ID)
	return s, encontrados, nil
}

func (a *App) crearSolicitud(w http.ResponseWriter, r *http.Request) {
	var d Solicitud
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	s, enc, err := a.publicarSolicitud(tiendaDe(r), d.Que, d.Detalle, d.Cantidad, false)
	manejar(w, map[string]any{"solicitud": s, "encontrados": enc}, err)
}

func (a *App) cerrarSolicitud(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err == nil {
		_, err = a.db.Exec("UPDATE solicitudes SET estado = 'cerrada' WHERE id = ? AND tienda_id = ?", id, tiendaDe(r))
	}
	manejar(w, map[string]bool{"ok": err == nil}, err)
}

// ---------------- El buscador: une lo que se busca con lo que se ofrece ----------------

func cantidadTxt(n int64) string {
	s := strconv.FormatInt(n, 10)
	for i := len(s) - 3; i > 0; i -= 3 {
		s = s[:i] + "," + s[i:]
	}
	return s
}

func pesosTxt(c int64) string {
	if c%100 == 0 {
		return "$" + cantidadTxt(c/100)
	}
	return fmt.Sprintf("$%s.%02d", cantidadTxt(c/100), c%100)
}

func (r Resultado) resumen() string {
	var b strings.Builder
	switch r.Tipo {
	case "producto":
		b.WriteString("«" + r.Titulo + "» en " + r.Tienda.Nombre)
		if r.Disponible != nil {
			b.WriteString(": hay " + cantidadTxt(*r.Disponible) + " " + r.Unidad)
		}
		if r.PrecioMayoreoC > 0 {
			b.WriteString(", mayoreo " + pesosTxt(r.PrecioMayoreoC))
			if r.MinimoMayoreo > 0 {
				b.WriteString(" desde " + cantidadTxt(r.MinimoMayoreo))
			}
		} else if r.PrecioC > 0 {
			b.WriteString(", " + pesosTxt(r.PrecioC))
		}
	case "anuncio":
		b.WriteString("«" + r.Titulo + "» de " + r.Tienda.Nombre)
		if r.Precio != "" {
			b.WriteString(" (" + r.Precio + ")")
		}
	case "productor":
		b.WriteString(r.Titulo + " (productor local)")
	default:
		b.WriteString(r.Titulo)
	}
	return b.String()
}

func (a *App) avisar(tienda int64, clave, tipo, texto, enlace string) {
	a.db.Exec("INSERT OR IGNORE INTO avisos (tienda_id, clave, tipo, texto, enlace, creado) VALUES (?, ?, ?, ?, ?, ?)",
		tienda, clave, tipo, recortarTexto(texto, 500), enlace, ahora())
}

func enlaceRed(q string) string { return "/panel/red?q=" + url.QueryEscape(q) }

// buscarOfertas: alguien publicó "busco X" → busca quién lo tiene y avisa a ambos.
func (a *App) buscarOfertas(solicitud int64) []Resultado {
	var tienda, cantidad int64
	var que, detalle, quien string
	if a.db.QueryRow(`SELECT s.tienda_id, s.que, s.detalle, s.cantidad, t.nombre FROM solicitudes s JOIN tiendas t ON t.id = s.tienda_id
		WHERE s.id = ? AND s.estado = 'abierta'`, solicitud).Scan(&tienda, &que, &detalle, &cantidad, &quien) != nil {
		return nil
	}
	lat, lng := a.coordsDe(tienda)
	res, _ := a.buscarRed(filtroRed{yo: tienda, q: que, tipo: "todo", lat: lat, lng: lng, limite: 40})
	buscado := que
	if cantidad > 0 {
		buscado = cantidadTxt(cantidad) + " " + que
	}
	// Un aviso por tienda que lo tiene (con su mejor opción), no uno por artículo.
	var out []Resultado
	porTienda := map[int64][]Resultado{}
	var orden []int64
	for _, r := range res {
		if r.Tipo == "productor" || r.Tienda == nil || !coincide(que, r.texto) {
			continue
		}
		out = append(out, r)
		if _, ok := porTienda[r.Tienda.ID]; !ok {
			orden = append(orden, r.Tienda.ID)
		}
		porTienda[r.Tienda.ID] = append(porTienda[r.Tienda.ID], r)
	}
	// Primero lo que es producto con existencia (lo más concreto).
	for _, id := range orden {
		rs := porTienda[id]
		sort.SliceStable(rs, func(i, j int) bool { return rs[i].Tipo == "producto" && rs[j].Tipo != "producto" })
		mejor := rs[0]
		clave := fmt.Sprintf("sol:%d:t:%d", solicitud, id)
		texto := "Tu asesor encontró lo que buscas (" + buscado + "): " + mejor.resumen() + "."
		if mejor.Disponible != nil && cantidad > 0 && *mejor.Disponible >= cantidad {
			texto += " ¡Le alcanza para todo tu pedido!"
		}
		if len(rs) > 1 {
			texto += fmt.Sprintf(" Tiene %d opción(es) más.", len(rs)-1)
		}
		a.avisar(tienda, clave, "encontrado", texto, enlaceRed(que))
		var suyos []string
		for _, r := range rs[:min(3, len(rs))] {
			suyos = append(suyos, "«"+r.Titulo+"»")
		}
		texto = quien + " busca " + buscado
		if detalle != "" {
			texto += " (" + recortarTexto(detalle, 120) + ")"
		}
		texto += ". Tú tienes " + strings.Join(suyos, ", ") + ". Ofréceselo desde tu red."
		a.avisar(id, clave, "interesado", texto, fmt.Sprintf("/panel/red?solicitud=%d", solicitud))
	}
	if len(out) > 8 {
		out = out[:8]
	}
	return out
}

// buscarInteresados: una tienda publicó (o le llegó) un producto → ¿alguien lo busca?
func (a *App) buscarInteresados(tienda, producto int64) {
	var nombre, cat, desc string
	var publicado bool
	if a.db.QueryRow("SELECT nombre, categoria, descripcion, publicado FROM productos WHERE id = ? AND tienda_id = ?", producto, tienda).
		Scan(&nombre, &cat, &desc, &publicado) != nil || !publicado {
		return
	}
	texto := normalizar(nombre + " " + cat + " " + desc)
	rows, err := a.db.Query("SELECT id, que FROM solicitudes WHERE estado = 'abierta' AND tienda_id != ?", tienda)
	if err != nil {
		return
	}
	var ids []int64
	for rows.Next() {
		var id int64
		var que string
		rows.Scan(&id, &que)
		if coincide(que, texto) {
			ids = append(ids, id)
		}
	}
	rows.Close()
	for _, id := range ids {
		a.buscarOfertas(id) // las claves de aviso evitan repetir lo ya avisado
	}
}

func (a *App) anuncioInteresa(tienda int64, x Anuncio) {
	texto := normalizar(x.Titulo + " " + x.Texto)
	rows, err := a.db.Query("SELECT id, que FROM solicitudes WHERE estado = 'abierta' AND tienda_id != ?", tienda)
	if err != nil {
		return
	}
	var ids []int64
	for rows.Next() {
		var id int64
		var que string
		rows.Scan(&id, &que)
		if coincide(que, texto) {
			ids = append(ids, id)
		}
	}
	rows.Close()
	for _, id := range ids {
		a.buscarOfertas(id)
	}
}

// ---------------- Avisos ----------------

type Aviso struct {
	ID     int64  `json:"id"`
	Tipo   string `json:"tipo"`
	Texto  string `json:"texto"`
	Enlace string `json:"enlace"`
	Leido  bool   `json:"leido"`
	Creado int64  `json:"creado"`
}

func (a *App) listarAvisos(w http.ResponseWriter, r *http.Request) {
	rows, err := a.db.Query("SELECT id, tipo, texto, enlace, leido, creado FROM avisos WHERE tienda_id = ? ORDER BY id DESC LIMIT 60", tiendaDe(r))
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer rows.Close()
	out := []Aviso{}
	for rows.Next() {
		var x Aviso
		rows.Scan(&x.ID, &x.Tipo, &x.Texto, &x.Enlace, &x.Leido, &x.Creado)
		out = append(out, x)
	}
	manejar(w, out, rows.Err())
}

func (a *App) avisosLeidos(w http.ResponseWriter, r *http.Request) {
	_, err := a.db.Exec("UPDATE avisos SET leido = 1 WHERE tienda_id = ? AND leido = 0", tiendaDe(r))
	manejar(w, map[string]bool{"ok": err == nil}, err)
}
