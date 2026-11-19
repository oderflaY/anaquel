package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// Chat entre tiendas de la red (la tiendita y su proveedor, o dos tiendas
// cualesquiera). Además de texto se puede mandar un mini catálogo con lo que
// tienes en vitrina; quien lo recibe elige cantidades y lo convierte en un
// pedido, que por dentro son tratos (uno por producto) que se aceptan y se
// entregan desde la misma tarjeta, moviendo el inventario de las dos tiendas.

type ItemCatalogo struct {
	ProductoID     int64  `json:"producto_id"`
	Nombre         string `json:"nombre"`
	Categoria      string `json:"categoria,omitempty"`
	Unidad         string `json:"unidad"`
	PrecioC        int64  `json:"precio_c"`
	PrecioMayoreoC int64  `json:"precio_mayoreo_c"`
	MinimoMayoreo  int64  `json:"minimo_mayoreo"`
	Disponible     *int64 `json:"disponible"`
	Sigue          bool   `json:"sigue"` // sigue publicado en la vitrina
}

type TratoChat struct {
	ID       int64  `json:"id"`
	Titulo   string `json:"titulo"`
	Cantidad int64  `json:"cantidad"`
	PrecioC  int64  `json:"precio_c"`
	Estado   string `json:"estado"`
}

type MensajeChat struct {
	ID     int64          `json:"id"`
	De     string         `json:"de"` // yo | otro | sistema
	Tipo   string         `json:"tipo"`
	Texto  string         `json:"texto"`
	Items  []ItemCatalogo `json:"items,omitempty"`
	Tratos []TratoChat    `json:"tratos,omitempty"`
	Total  int64          `json:"total,omitempty"`
	Rol    string         `json:"rol,omitempty"`    // pedido: compro | vendo
	Estado string         `json:"estado,omitempty"` // pedido: negociando | aceptado | entregado | cancelado | mixto
	Puedo  []string       `json:"puedo,omitempty"`  // pedido: aceptar | entregar | cancelar
	Creado int64          `json:"creado"`
	tienda *int64
	datos  string
}

type Chat struct {
	ID          int64         `json:"id"`
	Otra        TiendaCorta   `json:"otra"`
	Ultimo      string        `json:"ultimo"`
	UltimoMio   bool          `json:"ultimo_mio"`
	Actualizado int64         `json:"actualizado"`
	SinLeer     int           `json:"sin_leer"`
	Pendientes  int           `json:"pendientes"` // pedidos donde me toca algo
	Mensajes    []MensajeChat `json:"mensajes,omitempty"`
	Whatsapp    string        `json:"whatsapp,omitempty"`
	DistanciaKm *float64      `json:"distancia_km,omitempty"`
	otraID      int64
}

type datosCatalogo struct {
	Items []ItemCatalogo `json:"items"`
}

type datosPedido struct {
	Tratos   []int64 `json:"tratos"`
	Catalogo int64   `json:"catalogo,omitempty"`
}

// chatEntre devuelve (o crea) la conversación entre dos tiendas.
func (a *App) chatEntre(t1, t2 int64) (int64, error) {
	if t1 == t2 {
		return 0, fallo(400, "No puedes chatear contigo mismo")
	}
	x, y := min(t1, t2), max(t1, t2)
	var id int64
	err := a.db.QueryRow("SELECT id FROM chats WHERE tienda_a = ? AND tienda_b = ?", x, y).Scan(&id)
	if err == sql.ErrNoRows {
		var existe int
		a.db.QueryRow("SELECT COUNT(*) FROM tiendas WHERE id = ?", t2).Scan(&existe)
		if existe == 0 {
			return 0, fallo(404, "Esa tienda no existe")
		}
		res, err := a.db.Exec("INSERT INTO chats (tienda_a, tienda_b, actualizado) VALUES (?, ?, ?)", x, y, ahora())
		if err != nil {
			return 0, err
		}
		return res.LastInsertId()
	}
	return id, err
}

// mensajeChat guarda un mensaje (tienda nil = sistema) y mueve la conversación arriba.
func (a *App) mensajeChat(chat int64, tienda *int64, tipo, texto string, datos any) int64 {
	d := ""
	if datos != nil {
		b, _ := json.Marshal(datos)
		d = string(b)
	}
	t := ahora()
	res, err := a.db.Exec("INSERT INTO chat_mensajes (chat_id, tienda_id, tipo, texto, datos, creado) VALUES (?, ?, ?, ?, ?, ?)",
		chat, tienda, tipo, recortarTexto(texto, 2000), d, t)
	if err != nil {
		return 0
	}
	id, _ := res.LastInsertId()
	a.db.Exec("UPDATE chats SET actualizado = ? WHERE id = ?", t, chat)
	if tienda != nil { // quien escribe ya leyó hasta aquí
		a.db.Exec("UPDATE chats SET leido_a = CASE WHEN tienda_a = ? THEN ? ELSE leido_a END, leido_b = CASE WHEN tienda_b = ? THEN ? ELSE leido_b END WHERE id = ?",
			*tienda, id, *tienda, id, chat)
	}
	return id
}

// chatDe carga la cabecera de una conversación y revisa que sea mía.
func (a *App) chatDe(id, yo int64) (Chat, error) {
	var c Chat
	var x, y, leidoA, leidoB int64
	err := a.db.QueryRow("SELECT id, tienda_a, tienda_b, actualizado, leido_a, leido_b FROM chats WHERE id = ?", id).
		Scan(&c.ID, &x, &y, &c.Actualizado, &leidoA, &leidoB)
	if err == sql.ErrNoRows || (err == nil && yo != x && yo != y) {
		return c, fallo(404, "Esa conversación no existe")
	}
	if err != nil {
		return c, err
	}
	c.otraID = y
	leido := leidoA
	if yo == y {
		c.otraID, leido = x, leidoB
	}
	a.db.QueryRow("SELECT id, nombre, slug, giro, telefono FROM tiendas WHERE id = ?", c.otraID).
		Scan(&c.Otra.ID, &c.Otra.Nombre, &c.Otra.Slug, &c.Otra.Giro, &c.Otra.Telefono)
	a.db.QueryRow("SELECT COUNT(*) FROM chat_mensajes WHERE chat_id = ? AND id > ? AND (tienda_id IS NULL OR tienda_id != ?)", id, leido, yo).Scan(&c.SinLeer)
	return c, nil
}

func (a *App) listarChats(w http.ResponseWriter, r *http.Request) {
	yo := tiendaDe(r)
	out, err := a.chatsDe(yo)
	manejar(w, out, err)
}

func (a *App) chatsDe(yo int64) ([]Chat, error) {
	rows, err := a.db.Query("SELECT id FROM chats WHERE tienda_a = ? OR tienda_b = ? ORDER BY actualizado DESC LIMIT 200", yo, yo)
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
	lat, lng := a.coordsDe(yo)
	out := []Chat{}
	for _, id := range ids {
		c, err := a.chatDe(id, yo)
		if err != nil {
			continue
		}
		var tipo, texto string
		var de sql.NullInt64
		if a.db.QueryRow("SELECT tipo, texto, tienda_id FROM chat_mensajes WHERE chat_id = ? ORDER BY id DESC LIMIT 1", id).Scan(&tipo, &texto, &de) == nil {
			c.Ultimo = vistaPrevia(tipo, texto)
			c.UltimoMio = de.Valid && de.Int64 == yo
		}
		c.Pendientes = a.pendientesChat(id, yo)
		if lat != nil && lng != nil {
			if la, ln := a.coordsDe(c.otraID); la != nil && ln != nil {
				d := redondear(distanciaKm(*lat, *lng, *la, *ln))
				c.DistanciaKm = &d
			}
		}
		out = append(out, c)
	}
	return out, nil
}

func redondear(km float64) float64 { return float64(int(km*10+0.5)) / 10 }

func vistaPrevia(tipo, texto string) string {
	switch tipo {
	case "catalogo":
		return "📋 " + texto
	case "pedido":
		return "🧾 " + texto
	}
	return texto
}

// pendientesChat: pedidos de la conversación en los que me toca aceptar o entregar.
func (a *App) pendientesChat(chat, yo int64) int {
	var n int
	a.db.QueryRow(`SELECT COUNT(*) FROM tratos t WHERE (t.comprador_id = ? OR t.vendedor_id = ?)
		AND ((t.estado = 'negociando' AND t.propuso_id != ?) OR (t.estado = 'aceptado' AND t.vendedor_id = ?))
		AND t.id IN (SELECT value FROM chat_mensajes m, json_each(json_extract(m.datos, '$.tratos')) WHERE m.chat_id = ? AND m.tipo = 'pedido')`,
		yo, yo, yo, yo, chat).Scan(&n)
	return n
}

// cargarChat trae la conversación completa con catálogos y pedidos al día.
func (a *App) cargarChat(id, yo int64, marcarLeido bool) (Chat, error) {
	c, err := a.chatDe(id, yo)
	if err != nil {
		return c, err
	}
	rows, err := a.db.Query(`SELECT id, tienda_id, tipo, texto, datos, creado FROM (
		SELECT * FROM chat_mensajes WHERE chat_id = ? ORDER BY id DESC LIMIT 300) ORDER BY id`, id)
	if err != nil {
		return c, err
	}
	for rows.Next() {
		var m MensajeChat
		rows.Scan(&m.ID, &m.tienda, &m.Tipo, &m.Texto, &m.datos, &m.Creado)
		switch {
		case m.tienda == nil:
			m.De = "sistema"
		case *m.tienda == yo:
			m.De = "yo"
		default:
			m.De = "otro"
		}
		c.Mensajes = append(c.Mensajes, m)
	}
	rows.Close()
	// Fuera del rows: una sola conexión.
	for i := range c.Mensajes {
		m := &c.Mensajes[i]
		switch m.Tipo {
		case "catalogo":
			var d datosCatalogo
			json.Unmarshal([]byte(m.datos), &d)
			for _, it := range d.Items {
				a.alDia(&it, *m.tienda)
				m.Items = append(m.Items, it)
			}
		case "pedido":
			var d datosPedido
			json.Unmarshal([]byte(m.datos), &d)
			a.llenarPedido(m, d, yo)
		}
	}
	if marcarLeido && len(c.Mensajes) > 0 {
		ultimo := c.Mensajes[len(c.Mensajes)-1].ID
		a.db.Exec("UPDATE chats SET leido_a = CASE WHEN tienda_a = ? THEN ? ELSE leido_a END, leido_b = CASE WHEN tienda_b = ? THEN ? ELSE leido_b END WHERE id = ?",
			yo, ultimo, yo, ultimo, id)
		a.db.Exec("UPDATE avisos SET leido = 1 WHERE tienda_id = ? AND clave LIKE ?", yo, fmt.Sprintf("chat:%d:%%", id))
		c.SinLeer = 0
	}
	if tel := telefonoWA(c.Otra.Telefono); tel != "" {
		c.Whatsapp = "https://wa.me/" + tel + "?text=" + url.QueryEscape("Hola "+c.Otra.Nombre+", te escribo de la red Anaquel.")
	}
	c.Pendientes = a.pendientesChat(id, yo)
	return c, nil
}

// alDia: precio y existencia de hoy de un producto del catálogo.
func (a *App) alDia(it *ItemCatalogo, dueno int64) {
	var publicado bool
	err := a.db.QueryRow("SELECT nombre, unidad, precio_c, precio_mayoreo_c, minimo_mayoreo, stock, publicado FROM productos WHERE id = ? AND tienda_id = ?",
		it.ProductoID, dueno).Scan(&it.Nombre, &it.Unidad, &it.PrecioC, &it.PrecioMayoreoC, &it.MinimoMayoreo, &it.Disponible, &publicado)
	it.Sigue = err == nil && publicado
}

func (a *App) llenarPedido(m *MensajeChat, d datosPedido, yo int64) {
	estados := map[string]int{}
	puedo := map[string]bool{}
	for _, tid := range d.Tratos {
		var t TratoChat
		var comprador, vendedor, propuso int64
		if a.db.QueryRow("SELECT id, titulo, cantidad, precio_c, estado, comprador_id, vendedor_id, propuso_id FROM tratos WHERE id = ?", tid).
			Scan(&t.ID, &t.Titulo, &t.Cantidad, &t.PrecioC, &t.Estado, &comprador, &vendedor, &propuso) != nil {
			continue
		}
		if yo != comprador && yo != vendedor {
			continue
		}
		m.Rol = "compro"
		if yo == vendedor {
			m.Rol = "vendo"
		}
		m.Tratos = append(m.Tratos, t)
		if t.Estado != "cancelado" {
			m.Total += t.Cantidad * t.PrecioC
		}
		estados[t.Estado]++
		switch t.Estado {
		case "negociando":
			if propuso != yo {
				puedo["aceptar"] = true
			}
			puedo["cancelar"] = true
		case "aceptado":
			if yo == vendedor {
				puedo["entregar"] = true
			}
			puedo["cancelar"] = true
		}
	}
	switch len(estados) {
	case 0:
		m.Estado = "cancelado"
	case 1:
		for e := range estados {
			m.Estado = e
		}
	default:
		m.Estado = "mixto"
		if estados["negociando"] == 0 && estados["aceptado"] == 0 {
			m.Estado = "entregado" // lo que no se canceló ya se entregó
		}
	}
	for _, k := range []string{"aceptar", "entregar", "cancelar"} {
		if puedo[k] {
			m.Puedo = append(m.Puedo, k)
		}
	}
}

// abrirChat: POST /api/chats {tienda_id} → conversación (nueva o la que ya había).
func (a *App) abrirChat(w http.ResponseWriter, r *http.Request) {
	var d struct {
		TiendaID int64 `json:"tienda_id"`
	}
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	yo := tiendaDe(r)
	id, err := a.chatEntre(yo, d.TiendaID)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	c, err := a.cargarChat(id, yo, true)
	manejar(w, c, err)
}

func (a *App) verChat(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	c, err := a.cargarChat(id, tiendaDe(r), true)
	manejar(w, c, err)
}

// escribirChat: texto o catálogo. Catálogo: {"catalogo": [ids]} o {"catalogo_todo": true}.
func (a *App) escribirChat(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	var d struct {
		Texto        string  `json:"texto"`
		Catalogo     []int64 `json:"catalogo"`
		CatalogoTodo bool    `json:"catalogo_todo"`
	}
	if err == nil {
		err = leerJSON(r, &d)
	}
	yo := tiendaDe(r)
	var c Chat
	if err == nil {
		c, err = a.chatDe(id, yo)
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	texto := strings.TrimSpace(d.Texto)
	quien := a.nombreTienda(yo)
	var aviso string
	if d.CatalogoTodo || len(d.Catalogo) > 0 {
		items, err := a.miCatalogo(yo, d.Catalogo, d.CatalogoTodo)
		if err != nil {
			manejar(w, nil, err)
			return
		}
		if texto == "" {
			texto = fmt.Sprintf("Te comparto mi catálogo (%d productos)", len(items))
		}
		a.mensajeChat(id, &yo, "catalogo", texto, datosCatalogo{Items: items})
		aviso = fmt.Sprintf("%s te mandó su catálogo con %d productos.", quien, len(items))
	} else {
		if texto == "" {
			manejar(w, nil, fallo(400, "Escribe un mensaje"))
			return
		}
		a.mensajeChat(id, &yo, "texto", texto, nil)
		aviso = quien + ": " + recortarTexto(texto, 140)
	}
	a.avisar(c.otraID, fmt.Sprintf("chat:%d:%d", id, ahora()), "chat", aviso, fmt.Sprintf("/panel/chats/%d", id))
	out, err := a.cargarChat(id, yo, true)
	manejar(w, out, err)
}

// miCatalogo: los productos de mi vitrina (publicados y con existencia) que voy a mandar.
func (a *App) miCatalogo(yo int64, ids []int64, todo bool) ([]ItemCatalogo, error) {
	q := `SELECT id, nombre, categoria, unidad, precio_c, precio_mayoreo_c, minimo_mayoreo, stock FROM productos
		WHERE tienda_id = ? AND publicado = 1 AND (stock IS NULL OR stock > 0)`
	args := []any{yo}
	if !todo {
		if len(ids) > 40 {
			ids = ids[:40]
		}
		marcas := make([]string, len(ids))
		for i, x := range ids {
			marcas[i] = "?"
			args = append(args, x)
		}
		q += " AND id IN (" + strings.Join(marcas, ",") + ")"
	}
	rows, err := a.db.Query(q+" ORDER BY categoria, nombre LIMIT 40", args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ItemCatalogo
	for rows.Next() {
		var it ItemCatalogo
		rows.Scan(&it.ProductoID, &it.Nombre, &it.Categoria, &it.Unidad, &it.PrecioC, &it.PrecioMayoreoC, &it.MinimoMayoreo, &it.Disponible)
		it.Sigue = true
		out = append(out, it)
	}
	if len(out) == 0 {
		return nil, fallo(400, "No tienes productos en tu vitrina. Publica alguno en «Mi vitrina» para poder mandar tu catálogo.")
	}
	return out, rows.Err()
}

// pedirDeChat: con cantidades elegidas del catálogo de la otra tienda se abre un
// trato por producto y queda una sola tarjeta de pedido en el chat.
func (a *App) pedirDeChat(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	var d struct {
		Catalogo int64 `json:"catalogo"`
		Items    []struct {
			ProductoID int64 `json:"producto_id"`
			Cantidad   int64 `json:"cantidad"`
		} `json:"items"`
		Texto string `json:"texto"`
	}
	if err == nil {
		err = leerJSON(r, &d)
	}
	yo := tiendaDe(r)
	var c Chat
	if err == nil {
		c, err = a.chatDe(id, yo)
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	// Primero se revisa todo (para no dejar medio pedido).
	type linea struct {
		id, cant int64
		nombre   string
	}
	var lineas []linea
	for _, it := range d.Items {
		if it.Cantidad <= 0 {
			continue
		}
		var dueno int64
		var nombre string
		var publicado bool
		var stock *int64
		if a.db.QueryRow("SELECT tienda_id, nombre, publicado, stock FROM productos WHERE id = ?", it.ProductoID).Scan(&dueno, &nombre, &publicado, &stock) != nil ||
			dueno != c.otraID || !publicado {
			manejar(w, nil, fallo(409, "Uno de los productos ya no está en su vitrina. Pídele que te mande su catálogo otra vez."))
			return
		}
		if stock != nil && it.Cantidad > *stock {
			manejar(w, nil, fallo(409, fmt.Sprintf("De %s solo tiene %s.", nombre, cantidadTxt(*stock))))
			return
		}
		lineas = append(lineas, linea{it.ProductoID, it.Cantidad, nombre})
	}
	if len(lineas) == 0 {
		manejar(w, nil, fallo(400, "Pon la cantidad de al menos un producto"))
		return
	}
	texto := strings.TrimSpace(d.Texto)
	var tratos []int64
	var total int64
	for _, l := range lineas {
		tid, _, err := a.abrirTrato(yo, datosTrato{ProductoID: l.id, Cantidad: l.cant, Mensaje: texto}, false)
		if err != nil {
			continue
		}
		tratos = append(tratos, tid)
		var p int64
		a.db.QueryRow("SELECT precio_c FROM tratos WHERE id = ?", tid).Scan(&p)
		total += p * l.cant
	}
	if len(tratos) == 0 {
		manejar(w, nil, fallo(409, "No se pudo armar el pedido"))
		return
	}
	resumen := fmt.Sprintf("Pedido de %d producto", len(tratos))
	if len(tratos) != 1 {
		resumen += "s"
	}
	resumen += " por " + pesosTxt(total)
	if texto != "" {
		resumen += ". " + texto
	}
	a.mensajeChat(id, &yo, "pedido", resumen, datosPedido{Tratos: tratos, Catalogo: d.Catalogo})
	a.avisar(c.otraID, fmt.Sprintf("chat:%d:pedido:%d", id, ahora()), "trato",
		fmt.Sprintf("%s te hizo un pedido de %d productos por %s. Acéptalo en el chat.", a.nombreTienda(yo), len(tratos), pesosTxt(total)),
		fmt.Sprintf("/panel/chats/%d", id))
	out, err := a.cargarChat(id, yo, true)
	manejar(w, out, err)
}

// accionPedido: aceptar, entregar o cancelar todo un pedido del chat.
func (a *App) accionPedido(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	yo := tiendaDe(r)
	var c Chat
	if err == nil {
		c, err = a.chatDe(id, yo)
	}
	msg, err2 := strconv.ParseInt(r.PathValue("msg"), 10, 64)
	if err == nil && err2 != nil {
		err = fallo(400, "Mensaje inválido")
	}
	var datos string
	if err == nil && a.db.QueryRow("SELECT datos FROM chat_mensajes WHERE id = ? AND chat_id = ? AND tipo = 'pedido'", msg, id).Scan(&datos) != nil {
		err = fallo(404, "Ese pedido no existe")
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	var d datosPedido
	json.Unmarshal([]byte(datos), &d)
	accion := r.PathValue("accion")
	hechos := 0
	var ultimoErr error
	for _, tid := range d.Tratos {
		if e := a.hacerAccionTrato(tid, yo, accion, false); e != nil {
			ultimoErr = e
			continue
		}
		hechos++
	}
	if hechos == 0 {
		if ultimoErr == nil {
			ultimoErr = fallo(409, "No hay nada que hacer en ese pedido")
		}
		manejar(w, nil, ultimoErr)
		return
	}
	quien := a.nombreTienda(yo)
	var texto string
	switch accion {
	case "aceptar":
		texto = quien + " aceptó el pedido. Pónganse de acuerdo para la entrega."
	case "entregar":
		texto = quien + " entregó el pedido. Ya se registró la salida y la entrada en el inventario de las dos tiendas."
	case "cancelar":
		texto = quien + " canceló el pedido."
	}
	a.mensajeChat(id, nil, "sistema", texto, nil)
	a.avisar(c.otraID, fmt.Sprintf("chat:%d:%s:%d", id, accion, msg), "trato", texto, fmt.Sprintf("/panel/chats/%d", id))
	out, err := a.cargarChat(id, yo, true)
	manejar(w, out, err)
}

// chatsSinLeer: mensajes nuevos en todas mis conversaciones (para la burbuja).
func (a *App) chatsSinLeer(yo int64) int {
	var n int
	a.db.QueryRow(`SELECT COUNT(*) FROM chat_mensajes m JOIN chats c ON c.id = m.chat_id
		WHERE (c.tienda_a = ? AND m.id > c.leido_a OR c.tienda_b = ? AND m.id > c.leido_b)
		AND (m.tienda_id IS NULL OR m.tienda_id != ?)`, yo, yo, yo).Scan(&n)
	return n
}
