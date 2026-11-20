package main

import (
	"database/sql"
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

// Un trato es la negociación entre dos tiendas de la red: quien compra hace
// una oferta (cantidad y precio), la otra parte la acepta o contraoferta, y al
// entregar se mueve el inventario de las dos (salida y entrada en el kárdex).

type MensajeTrato struct {
	ID       int64  `json:"id"`
	De       string `json:"de"` // yo | otro | sistema
	Texto    string `json:"texto"`
	Cantidad *int64 `json:"cantidad,omitempty"`
	PrecioC  *int64 `json:"precio_c,omitempty"`
	Creado   int64  `json:"creado"`
}

type Trato struct {
	ID          int64          `json:"id"`
	Rol         string         `json:"rol"` // compro | vendo
	Otra        TiendaCorta    `json:"otra"`
	Titulo      string         `json:"titulo"`
	Cantidad    int64          `json:"cantidad"`
	PrecioC     int64          `json:"precio_c"`
	Estado      string         `json:"estado"`
	TeToca      bool           `json:"te_toca"` // la última propuesta es de la otra parte
	ProductoID  *int64         `json:"producto_id,omitempty"`
	SolicitudID *int64         `json:"solicitud_id,omitempty"`
	Disponible  *int64         `json:"disponible,omitempty"` // existencia del vendedor
	Creado      int64          `json:"creado"`
	Actualizado int64          `json:"actualizado"`
	Ultimo      string         `json:"ultimo,omitempty"`
	Mensajes    []MensajeTrato `json:"mensajes,omitempty"`
	Whatsapp    string         `json:"whatsapp,omitempty"`
	otraID      int64
}

const selTrato = `SELECT tr.id, tr.comprador_id, tr.vendedor_id, tr.titulo, tr.cantidad, tr.precio_c, tr.estado, tr.propuso_id,
	tr.producto_id, tr.solicitud_id, tr.creado, tr.actualizado,
	(SELECT texto FROM trato_mensajes m WHERE m.trato_id = tr.id ORDER BY m.id DESC LIMIT 1),
	(SELECT stock FROM productos p WHERE p.id = tr.producto_id)
	FROM tratos tr`

func (a *App) scanTrato(s interface{ Scan(...any) error }, yo int64) (Trato, error) {
	var t Trato
	var comprador, vendedor, propuso int64
	var ultimo sql.NullString
	err := s.Scan(&t.ID, &comprador, &vendedor, &t.Titulo, &t.Cantidad, &t.PrecioC, &t.Estado, &propuso,
		&t.ProductoID, &t.SolicitudID, &t.Creado, &t.Actualizado, &ultimo, &t.Disponible)
	if err != nil {
		return t, err
	}
	if yo != comprador && yo != vendedor {
		return t, fallo(404, "Ese trato no existe")
	}
	otra := vendedor
	t.Rol = "compro"
	if yo == vendedor {
		otra, t.Rol = comprador, "vendo"
	}
	t.otraID = otra
	t.TeToca = t.Estado == "negociando" && propuso != yo
	t.Ultimo = ultimo.String
	return t, nil
}

func (a *App) cargarTrato(id, yo int64, conMensajes bool) (Trato, error) {
	t, err := a.scanTrato(a.db.QueryRow(selTrato+" WHERE tr.id = ?", id), yo)
	if err == sql.ErrNoRows {
		return t, fallo(404, "Ese trato no existe")
	}
	if err == nil {
		a.llenarOtra(&t)
	}
	if err != nil || !conMensajes {
		return t, err
	}
	rows, err := a.db.Query("SELECT id, tienda_id, texto, cantidad, precio_c, creado FROM trato_mensajes WHERE trato_id = ? ORDER BY id", id)
	if err != nil {
		return t, err
	}
	defer rows.Close()
	for rows.Next() {
		var m MensajeTrato
		var de *int64
		rows.Scan(&m.ID, &de, &m.Texto, &m.Cantidad, &m.PrecioC, &m.Creado)
		switch {
		case de == nil:
			m.De = "sistema"
		case *de == yo:
			m.De = "yo"
		default:
			m.De = "otro"
		}
		t.Mensajes = append(t.Mensajes, m)
	}
	if tel := telefonoWA(t.Otra.Telefono); tel != "" {
		t.Whatsapp = "https://wa.me/" + tel + "?text=" + url.QueryEscape(fmt.Sprintf("Hola %s, te escribo por el trato de %s en Anaquel.", t.Otra.Nombre, t.Titulo))
	}
	return t, rows.Err()
}

// llenarOtra completa los datos de la otra tienda (fuera de cualquier rows abierto).
func (a *App) llenarOtra(t *Trato) {
	a.db.QueryRow("SELECT id, nombre, slug, giro, telefono FROM tiendas WHERE id = ?", t.otraID).
		Scan(&t.Otra.ID, &t.Otra.Nombre, &t.Otra.Slug, &t.Otra.Giro, &t.Otra.Telefono)
}

func telefonoWA(t string) string {
	var b strings.Builder
	for _, c := range t {
		if c >= '0' && c <= '9' {
			b.WriteRune(c)
		}
	}
	s := b.String()
	if len(s) == 10 {
		s = "52" + s
	}
	if len(s) < 10 {
		return ""
	}
	return s
}

func (a *App) listarTratos(w http.ResponseWriter, r *http.Request) {
	yo := tiendaDe(r)
	rows, err := a.db.Query(selTrato+" WHERE tr.comprador_id = ? OR tr.vendedor_id = ? ORDER BY tr.actualizado DESC LIMIT 100", yo, yo)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer rows.Close()
	out := []Trato{}
	for rows.Next() {
		t, err := a.scanTrato(rows, yo)
		if err != nil {
			manejar(w, nil, err)
			return
		}
		out = append(out, t)
	}
	rows.Close()
	for i := range out {
		a.llenarOtra(&out[i])
	}
	manejar(w, out, nil)
}

func (a *App) verTrato(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	t, err := a.cargarTrato(id, tiendaDe(r), true)
	manejar(w, t, err)
}

// solicitudQueCubre: si el comprador tenía publicada una solicitud de esto, el
// trato la atiende (y al entregarse, la cierra).
func (a *App) solicitudQueCubre(tienda int64, titulo string) any {
	rows, err := a.db.Query("SELECT id, que FROM solicitudes WHERE tienda_id = ? AND estado = 'abierta' ORDER BY id DESC", tienda)
	if err != nil {
		return nil
	}
	defer rows.Close()
	texto := normalizar(titulo)
	for rows.Next() {
		var id int64
		var que string
		rows.Scan(&id, &que)
		if coincide(que, texto) {
			return id
		}
	}
	return nil
}

func (a *App) nombreTienda(id int64) string {
	var n string
	a.db.QueryRow("SELECT nombre FROM tiendas WHERE id = ?", id).Scan(&n)
	return n
}

type datosTrato struct {
	ProductoID  int64  `json:"producto_id"`
	AnuncioID   int64  `json:"anuncio_id"`
	SolicitudID int64  `json:"solicitud_id"`
	MiProducto  int64  `json:"mi_producto_id"` // al responder una solicitud
	Cantidad    int64  `json:"cantidad"`
	PrecioC     int64  `json:"precio_c"`
	Mensaje     string `json:"mensaje"`
}

// crearTrato abre una negociación desde un producto o anuncio de otra tienda
// (yo compro) o desde una solicitud de otra tienda (yo vendo). También queda
// en el chat entre las dos tiendas.
func (a *App) crearTrato(w http.ResponseWriter, r *http.Request) {
	var d datosTrato
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	yo := tiendaDe(r)
	id, otra, err := a.abrirTrato(yo, d, true)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	a.publicarOfertaInicial(yo, otra, id)
	out, err := a.cargarTrato(id, yo, true)
	manejar(w, out, err)
}

// publicarOfertaInicial deja la primera propuesta del trato en el chat de las dos tiendas.
func (a *App) publicarOfertaInicial(yo, otra, id int64) {
	var cant, precio int64
	var texto string
	a.db.QueryRow(`SELECT t.cantidad, t.precio_c, COALESCE((SELECT texto FROM trato_mensajes m WHERE m.trato_id = t.id ORDER BY m.id LIMIT 1), '')
		FROM tratos t WHERE t.id = ?`, id).Scan(&cant, &precio, &texto)
	a.ofertaEnChat(yo, otra, id, cant, precio, texto)
}

// contraofertar cambia cantidad y/o precio del trato (nil o <= 0 = se queda
// igual), deja la propuesta en el trato y en el chat, y avisa a la otra tienda.
func (a *App) contraofertar(id, yo int64, cantidad, precio *int64, texto string) (Trato, error) {
	t, err := a.cargarTrato(id, yo, false)
	if err != nil {
		return t, err
	}
	if t.Estado != "negociando" {
		return t, fallo(409, "El trato ya no está en negociación")
	}
	cant, pc := t.Cantidad, t.PrecioC
	if cantidad != nil && *cantidad > 0 {
		cant = *cantidad
	}
	if precio != nil && *precio > 0 {
		pc = *precio
	}
	if t.Rol == "compro" && t.Disponible != nil && cant > *t.Disponible && *t.Disponible > 0 {
		return t, fallo(409, fmt.Sprintf("Solo tiene %s.", cantidadTxt(*t.Disponible)))
	}
	texto = recortarTexto(strings.TrimSpace(texto), 1000)
	if texto == "" {
		texto = fmt.Sprintf("Te propongo %s a %s c/u.", cantidadTxt(cant), pesosTxt(pc))
	}
	ahoraMs := ahora()
	a.db.Exec("UPDATE tratos SET cantidad = ?, precio_c = ?, propuso_id = ?, actualizado = ? WHERE id = ?", cant, pc, yo, ahoraMs, id)
	a.db.Exec("INSERT INTO trato_mensajes (trato_id, tienda_id, texto, cantidad, precio_c, creado) VALUES (?, ?, ?, ?, ?, ?)", id, yo, texto, cant, pc, ahoraMs)
	a.ofertaEnChat(yo, t.Otra.ID, id, cant, pc, texto)
	a.avisar(t.Otra.ID, fmt.Sprintf("trato:%d:msg:%d", id, ahoraMs), "trato",
		fmt.Sprintf("%s te contraofertó %s %s a %s c/u.", a.nombreTienda(yo), cantidadTxt(cant), t.Titulo, pesosTxt(pc)), fmt.Sprintf("/panel/tratos/%d", id))
	t.Cantidad, t.PrecioC = cant, pc
	return t, nil
}

// abrirTrato crea el trato y su primer mensaje. Devuelve el id y la otra tienda.
func (a *App) abrirTrato(yo int64, d datosTrato, avisarOtra bool) (int64, int64, error) {
	if d.Cantidad <= 0 {
		return 0, 0, fallo(400, "Escribe cuántas piezas")
	}
	var comprador, vendedor int64
	var producto, anuncio, solicitud any
	var titulo string
	switch {
	case d.ProductoID > 0:
		var publicado bool
		var mayoreo, menudeo, minimo int64
		err := a.db.QueryRow("SELECT tienda_id, nombre, publicado, precio_mayoreo_c, precio_c, minimo_mayoreo FROM productos WHERE id = ?", d.ProductoID).
			Scan(&vendedor, &titulo, &publicado, &mayoreo, &menudeo, &minimo)
		if err != nil || !publicado || !a.puedeVer(yo, d.ProductoID) {
			return 0, 0, fallo(404, "Ese producto ya no está en la red")
		}
		if d.PrecioC <= 0 {
			d.PrecioC = menudeo
			if mayoreo > 0 && d.Cantidad >= minimo {
				d.PrecioC = mayoreo
			}
		}
		comprador, producto = yo, d.ProductoID
		solicitud = a.solicitudQueCubre(yo, titulo)
	case d.AnuncioID > 0:
		if err := a.db.QueryRow("SELECT tienda_id, titulo FROM anuncios WHERE id = ? AND activo = 1", d.AnuncioID).Scan(&vendedor, &titulo); err != nil {
			return 0, 0, fallo(404, "Ese anuncio ya no está")
		}
		comprador, anuncio = yo, d.AnuncioID
		solicitud = a.solicitudQueCubre(yo, titulo)
	case d.SolicitudID > 0:
		if err := a.db.QueryRow("SELECT tienda_id, que FROM solicitudes WHERE id = ? AND estado = 'abierta'", d.SolicitudID).Scan(&comprador, &titulo); err != nil {
			return 0, 0, fallo(404, "Esa solicitud ya se cerró")
		}
		vendedor, solicitud = yo, d.SolicitudID
		if d.MiProducto > 0 {
			var nombre string
			if a.db.QueryRow("SELECT nombre FROM productos WHERE id = ? AND tienda_id = ?", d.MiProducto, yo).Scan(&nombre) == nil {
				producto, titulo = d.MiProducto, nombre
			}
		}
	default:
		return 0, 0, fallo(400, "¿Sobre qué es el trato?")
	}
	if comprador == vendedor {
		return 0, 0, fallo(400, "No puedes negociar contigo mismo")
	}
	t := ahora()
	tx, err := a.db.Begin()
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()
	res, err := tx.Exec(`INSERT INTO tratos (comprador_id, vendedor_id, producto_id, anuncio_id, solicitud_id, titulo, cantidad, precio_c,
		propuso_id, creado, actualizado) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		comprador, vendedor, producto, anuncio, solicitud, titulo, d.Cantidad, max(0, d.PrecioC), yo, t, t)
	if err != nil {
		return 0, 0, err
	}
	id, _ := res.LastInsertId()
	texto := strings.TrimSpace(d.Mensaje)
	if texto == "" {
		if yo == comprador {
			texto = "Hola, me interesa. ¿Me lo dejas en este precio?"
		} else {
			texto = "Hola, vi que buscas esto. Te lo puedo surtir."
		}
	}
	tx.Exec("INSERT INTO trato_mensajes (trato_id, tienda_id, texto, cantidad, precio_c, creado) VALUES (?, ?, ?, ?, ?, ?)",
		id, yo, recortarTexto(texto, 1000), d.Cantidad, max(0, d.PrecioC), t)
	if err := tx.Commit(); err != nil {
		return 0, 0, err
	}
	otra := vendedor
	verbo := "quiere comprarte"
	if yo == vendedor {
		otra, verbo = comprador, "te ofrece"
	}
	if avisarOtra {
		a.avisar(otra, fmt.Sprintf("trato:%d:nuevo", id), "trato",
			fmt.Sprintf("%s %s %s %s a %s c/u.", a.nombreTienda(yo), verbo, cantidadTxt(d.Cantidad), titulo, pesosTxt(d.PrecioC)),
			fmt.Sprintf("/panel/tratos/%d", id))
	}
	return id, otra, nil
}

func (a *App) mensajeTrato(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	var d struct {
		Texto    string `json:"texto"`
		Cantidad *int64 `json:"cantidad"`
		PrecioC  *int64 `json:"precio_c"`
	}
	if err == nil {
		err = leerJSON(r, &d)
	}
	yo := tiendaDe(r)
	var t Trato
	if err == nil {
		t, err = a.cargarTrato(id, yo, false)
	}
	if err == nil && t.Estado != "negociando" && (d.Cantidad != nil || d.PrecioC != nil) {
		err = fallo(409, "El trato ya no está en negociación")
	}
	if err == nil && t.Estado == "cancelado" {
		err = fallo(409, "El trato se canceló")
	}
	texto := recortarTexto(strings.TrimSpace(d.Texto), 1000)
	contra := d.Cantidad != nil || d.PrecioC != nil
	if err == nil && texto == "" && !contra {
		err = fallo(400, "Escribe un mensaje")
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	cant, precio := t.Cantidad, t.PrecioC
	if d.Cantidad != nil && *d.Cantidad > 0 {
		cant = *d.Cantidad
	}
	if d.PrecioC != nil && *d.PrecioC >= 0 {
		precio = *d.PrecioC
	}
	if contra {
		if _, err := a.contraofertar(id, yo, &cant, &precio, texto); err != nil {
			manejar(w, nil, err)
			return
		}
	} else {
		ahoraMs := ahora()
		a.db.Exec("UPDATE tratos SET actualizado = ? WHERE id = ?", ahoraMs, id)
		a.db.Exec("INSERT INTO trato_mensajes (trato_id, tienda_id, texto, creado) VALUES (?, ?, ?, ?)", id, yo, texto, ahoraMs)
		a.avisar(t.Otra.ID, fmt.Sprintf("trato:%d:msg:%d", id, ahoraMs), "trato",
			a.nombreTienda(yo)+" respondió en el trato de "+t.Titulo+": "+recortarTexto(texto, 140), fmt.Sprintf("/panel/tratos/%d", id))
	}
	out, err := a.cargarTrato(id, yo, true)
	manejar(w, out, err)
}

func (a *App) sistemaTrato(id int64, texto string) {
	t := ahora()
	a.db.Exec("INSERT INTO trato_mensajes (trato_id, texto, creado) VALUES (?, ?, ?)", id, texto, t)
	a.db.Exec("UPDATE tratos SET actualizado = ? WHERE id = ?", t, id)
}

func (a *App) accionTrato(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	yo := tiendaDe(r)
	if err == nil {
		err = a.hacerAccionTrato(id, yo, r.PathValue("accion"), true)
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	out, err := a.cargarTrato(id, yo, true)
	manejar(w, out, err)
}

// hacerAccionTrato: aceptar, entregar o cancelar (lo usan la pantalla de
// tratos y las tarjetas de pedido del chat).
func (a *App) hacerAccionTrato(id, yo int64, accion string, avisarOtra bool) error {
	t, err := a.cargarTrato(id, yo, false)
	if err != nil {
		return err
	}
	quien := a.nombreTienda(yo)
	enlace := fmt.Sprintf("/panel/tratos/%d", id)
	avisa := func(clave, texto string) {
		if avisarOtra {
			a.avisar(t.Otra.ID, clave, "trato", texto, enlace)
		}
	}
	switch accion {
	case "aceptar":
		if t.Estado != "negociando" || !t.TeToca {
			return fallo(409, "Espera a que la otra tienda responda tu propuesta")
		}
		a.db.Exec("UPDATE tratos SET estado = 'aceptado' WHERE id = ?", id)
		a.sistemaTrato(id, fmt.Sprintf("%s aceptó: %s %s a %s c/u (total %s).", quien, cantidadTxt(t.Cantidad), t.Titulo,
			pesosTxt(t.PrecioC), pesosTxt(t.PrecioC*t.Cantidad)))
		avisa(fmt.Sprintf("trato:%d:aceptado", id), quien+" aceptó el trato de "+t.Titulo+". ¡Pónganse de acuerdo para la entrega!")
	case "entregar":
		if t.Estado != "aceptado" || t.Rol != "vendo" {
			return fallo(409, "Solo quien vende marca la entrega, y el trato debe estar aceptado")
		}
		if err := a.entregarTrato(id, yo, t); err != nil {
			return err
		}
		avisa(fmt.Sprintf("trato:%d:entregado", id), fmt.Sprintf("%s entregó %s %s. Ya se sumaron a tu inventario.", quien, cantidadTxt(t.Cantidad), t.Titulo))
	case "cancelar":
		if t.Estado == "entregado" || t.Estado == "cancelado" {
			return fallo(409, "Ese trato ya terminó")
		}
		a.db.Exec("UPDATE tratos SET estado = 'cancelado' WHERE id = ?", id)
		a.sistemaTrato(id, quien+" canceló el trato.")
		avisa(fmt.Sprintf("trato:%d:cancelado", id), quien+" canceló el trato de "+t.Titulo+".")
	default:
		return fallo(404, "Acción desconocida")
	}
	return nil
}

// entregarTrato mueve el inventario: salida del vendedor y entrada al comprador
// (si el comprador no tiene ese producto, se le da de alta).
func (a *App) entregarTrato(id, vendedor int64, t Trato) error {
	nombreVendedor := a.nombreTienda(vendedor) // fuera de la transacción: hay una sola conexión
	tx, err := a.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	comprador := t.Otra.ID
	var codigo, categoria, unidad string
	var precio int64
	if t.ProductoID != nil {
		var stock sql.NullInt64
		if tx.QueryRow("SELECT codigo, categoria, unidad, precio_c, stock FROM productos WHERE id = ? AND tienda_id = ?", *t.ProductoID, vendedor).
			Scan(&codigo, &categoria, &unidad, &precio, &stock) == nil && stock.Valid {
			if _, err := mover(tx, vendedor, *t.ProductoID, "trato_salida", -t.Cantidad, "Venta a "+t.Otra.Nombre, &id); err != nil {
				return err
			}
		}
	}
	// Producto del comprador: por código, luego por nombre; si no, se crea.
	var suyo int64
	err = sql.ErrNoRows
	if codigo != "" {
		err = tx.QueryRow("SELECT id FROM productos WHERE tienda_id = ? AND codigo = ?", comprador, codigo).Scan(&suyo)
	}
	if err != nil {
		err = tx.QueryRow("SELECT id FROM productos WHERE tienda_id = ? AND nombre = ? COLLATE NOCASE", comprador, t.Titulo).Scan(&suyo)
	}
	if err != nil {
		if unidad == "" {
			unidad = "pza"
		}
		res, err := tx.Exec(`INSERT INTO productos (tienda_id, codigo, nombre, categoria, precio_c, stock, unidad, actualizado)
			VALUES (?, ?, ?, ?, ?, 0, ?, ?)`, comprador, codigo, t.Titulo, categoria, 0, unidad, ahora())
		if err != nil {
			return err
		}
		suyo, _ = res.LastInsertId()
	}
	if _, err := mover(tx, comprador, suyo, "trato_entrada", t.Cantidad, "Compra a "+nombreVendedor+" a "+pesosTxt(t.PrecioC)+" c/u", &id); err != nil {
		return err
	}
	if _, err := tx.Exec("UPDATE tratos SET estado = 'entregado' WHERE id = ?", id); err != nil {
		return err
	}
	if t.SolicitudID != nil {
		// Se cierra cuando lo entregado ya cubre lo que pidió (o no pidió cantidad).
		tx.Exec(`UPDATE solicitudes SET estado = 'cerrada' WHERE id = ? AND (cantidad <= 0 OR cantidad <=
			(SELECT COALESCE(SUM(cantidad), 0) FROM tratos WHERE solicitud_id = solicitudes.id AND estado = 'entregado'))`, *t.SolicitudID)
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	a.sistemaTrato(id, fmt.Sprintf("%s marcó el trato como entregado. Se registró la salida y la entrada en el inventario de las dos tiendas.", nombreVendedor))
	return nil
}
