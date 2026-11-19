package main

import (
	"database/sql"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

// ---------------- Proveedores ----------------

type Proveedor struct {
	ID       int64  `json:"id"`
	Nombre   string `json:"nombre"`
	Telefono string `json:"telefono"`
	Surte    string `json:"surte"`
}

func (a *App) proveedores(tienda int64) ([]Proveedor, error) {
	rows, err := a.db.Query("SELECT id, nombre, telefono, surte FROM proveedores WHERE tienda_id = ? ORDER BY nombre COLLATE NOCASE", tienda)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Proveedor{}
	for rows.Next() {
		var p Proveedor
		rows.Scan(&p.ID, &p.Nombre, &p.Telefono, &p.Surte)
		out = append(out, p)
	}
	return out, rows.Err()
}

func (a *App) listarProveedores(w http.ResponseWriter, r *http.Request) {
	p, err := a.proveedores(tiendaDe(r))
	manejar(w, p, err)
}

func (a *App) crearProveedor(w http.ResponseWriter, r *http.Request) {
	var p Proveedor
	if err := leerJSON(r, &p); err != nil {
		manejar(w, nil, err)
		return
	}
	if strings.TrimSpace(p.Nombre) == "" {
		manejar(w, nil, fallo(400, "Escribe el nombre del proveedor"))
		return
	}
	res, err := a.db.Exec("INSERT INTO proveedores (tienda_id, nombre, telefono, surte, creado) VALUES (?, ?, ?, ?, ?)",
		tiendaDe(r), strings.TrimSpace(p.Nombre), strings.TrimSpace(p.Telefono), strings.TrimSpace(p.Surte), ahora())
	if err == nil {
		p.ID, _ = res.LastInsertId()
	}
	manejar(w, p, err)
}

func (a *App) editarProveedor(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	var p Proveedor
	if err == nil {
		err = leerJSON(r, &p)
	}
	if err == nil && strings.TrimSpace(p.Nombre) == "" {
		err = fallo(400, "Escribe el nombre del proveedor")
	}
	if err == nil {
		_, err = a.db.Exec("UPDATE proveedores SET nombre = ?, telefono = ?, surte = ? WHERE id = ? AND tienda_id = ?",
			strings.TrimSpace(p.Nombre), strings.TrimSpace(p.Telefono), strings.TrimSpace(p.Surte), id, tiendaDe(r))
		p.ID = id
	}
	manejar(w, p, err)
}

func (a *App) borrarProveedor(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err == nil {
		_, err = a.db.Exec("DELETE FROM proveedores WHERE id = ? AND tienda_id = ?", id, tiendaDe(r))
	}
	manejar(w, map[string]bool{"ok": err == nil}, err)
}

// ---------------- Coincidencias de palabras ----------------

// raiz quita plurales simples: "huevos" → "huevo", "tortillas" → "tortilla".
func raiz(p string) string {
	if len(p) > 4 && strings.HasSuffix(p, "es") && !strings.HasSuffix(p, "tes") {
		return p[:len(p)-2]
	}
	if len(p) > 3 && strings.HasSuffix(p, "s") {
		return p[:len(p)-1]
	}
	return p
}

// coincide: ¿alguno de los términos ("huevo, queso fresco") aparece en el texto?
func coincide(terminos, texto string) bool {
	t := normalizar(texto)
	for _, term := range strings.FieldsFunc(normalizar(terminos), func(c rune) bool { return c == ',' || c == ';' || c == '/' }) {
		term = strings.TrimSpace(term)
		if len(term) < 3 {
			continue
		}
		todas := true
		for _, palabra := range strings.Fields(term) {
			if len(palabra) >= 3 && !strings.Contains(t, raiz(palabra)) {
				todas = false
				break
			}
		}
		if todas {
			return true
		}
	}
	return false
}

func distanciaKm(lat1, lng1, lat2, lng2 float64) float64 {
	const r = 6371.0
	rad := math.Pi / 180
	dLat, dLng := (lat2-lat1)*rad, (lng2-lng1)*rad
	h := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dLng/2)*math.Sin(dLng/2)
	return math.Round(2*r*math.Asin(math.Sqrt(h))*10) / 10
}

// ---------------- Faltantes ----------------

type Sugerencia struct {
	Tipo        string   `json:"tipo"` // proveedor | productor
	ID          int64    `json:"id"`
	Nombre      string   `json:"nombre"`
	Telefono    string   `json:"telefono"`
	Lugar       string   `json:"lugar,omitempty"`
	DistanciaKm *float64 `json:"distancia_km,omitempty"`
	Razon       string   `json:"razon"`
}

type Faltante struct {
	Producto    Producto     `json:"producto"`
	Nivel       string       `json:"nivel"` // vacio | poco | bajo (por conteo)
	Motivo      string       `json:"motivo"`
	EnPedido    *int64       `json:"en_pedido"` // pedido abierto que ya lo incluye
	Sugerido    int64        `json:"sugerido"`  // cantidad sugerida para pedir
	Sugerencias []Sugerencia `json:"sugerencias"`
}

func (a *App) calcularFaltantes(tienda int64) ([]Faltante, error) {
	prods, err := a.productos(tienda)
	if err != nil {
		return nil, err
	}
	porID := map[int64]Producto{}
	for _, p := range prods {
		porID[p.ID] = p
	}
	// 1) Huecos vistos en las fotos.
	vistos := map[int64]Faltante{}
	rows, err := a.db.Query(`SELECT z.producto_id, z.nivel, an.nombre FROM zonas z JOIN anaqueles an ON an.id = z.anaquel_id
		WHERE an.tienda_id = ? AND z.producto_id IS NOT NULL AND z.nivel <> 'lleno'`, tienda)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var pid int64
		var nivel, anaquel string
		rows.Scan(&pid, &nivel, &anaquel)
		if p, ok := porID[pid]; ok {
			motivo := "Casi no queda en " + anaquel
			if nivel == "vacio" {
				motivo = "Hueco vacío en " + anaquel
			}
			if prev, ok := vistos[pid]; !ok || nivel == "vacio" && prev.Nivel != "vacio" {
				vistos[pid] = Faltante{Producto: p, Nivel: nivel, Motivo: motivo}
			}
		}
	}
	rows.Close()
	// 2) Conteo de inventario por debajo del mínimo (lo que manda la app).
	for _, p := range prods {
		if _, ok := vistos[p.ID]; ok || p.Stock == nil || p.StockMin <= 0 || *p.Stock > p.StockMin {
			continue
		}
		vistos[p.ID] = Faltante{Producto: p, Nivel: "bajo", Motivo: fmt.Sprintf("Quedan %d (mínimo %d)", *p.Stock, p.StockMin)}
	}
	if len(vistos) == 0 {
		return []Faltante{}, nil
	}
	// Pedidos abiertos que ya los incluyen.
	enPedido := map[int64]int64{}
	rows, err = a.db.Query(`SELECT i.producto_id, p.id FROM pedido_items i JOIN pedidos p ON p.id = i.pedido_id
		WHERE p.tienda_id = ? AND p.estado IN ('enviado','confirmado') AND i.recibido = 0 AND i.producto_id IS NOT NULL`, tienda)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var pid, ped int64
		rows.Scan(&pid, &ped)
		enPedido[pid] = ped
	}
	rows.Close()

	provs, err := a.proveedores(tienda)
	if err != nil {
		return nil, err
	}
	t, err := a.tienda(tienda)
	if err != nil {
		return nil, err
	}
	prods2, err := a.productoresTodos()
	if err != nil {
		return nil, err
	}

	out := make([]Faltante, 0, len(vistos))
	for pid, f := range vistos {
		if ped, ok := enPedido[pid]; ok {
			f.EnPedido = &ped
		}
		f.Sugerido = max(1, f.Producto.StockMin*2)
		// Con conteo bajo se completa hasta el doble del mínimo; si la foto
		// muestra el hueco, el conteo ya no es confiable y se pide el doble del mínimo.
		if f.Nivel == "bajo" && f.Producto.Stock != nil && f.Producto.StockMin > 0 {
			f.Sugerido = max(1, f.Producto.StockMin*2-*f.Producto.Stock)
		}
		f.Sugerencias = a.sugerencias(f.Producto, provs, prods2, t)
		out = append(out, f)
	}
	orden := map[string]int{"vacio": 0, "bajo": 1, "poco": 2}
	sort.Slice(out, func(i, j int) bool {
		if orden[out[i].Nivel] != orden[out[j].Nivel] {
			return orden[out[i].Nivel] < orden[out[j].Nivel]
		}
		return out[i].Producto.Nombre < out[j].Producto.Nombre
	})
	return out, nil
}

// radioLocalKm: hasta dónde se considera "productor de aquí cerca".
const radioLocalKm = 40

func (a *App) sugerencias(p Producto, provs []Proveedor, productores []Productor, t Tienda) []Sugerencia {
	out := []Sugerencia{}
	texto := p.Nombre + " " + p.Categoria
	for _, pv := range provs {
		switch {
		case p.ProveedorID != nil && *p.ProveedorID == pv.ID:
			out = append([]Sugerencia{{Tipo: "proveedor", ID: pv.ID, Nombre: pv.Nombre, Telefono: pv.Telefono, Razon: "Tu proveedor de este producto"}}, out...)
		case pv.Surte != "" && coincide(pv.Surte, texto):
			out = append(out, Sugerencia{Tipo: "proveedor", ID: pv.ID, Nombre: pv.Nombre, Telefono: pv.Telefono, Razon: "Surte " + pv.Surte})
		}
	}
	var locales []Sugerencia
	for _, pr := range productores {
		propio := p.ProductorID != nil && *p.ProductorID == pr.ID
		if !propio && !coincide(pr.Productos, texto) {
			continue
		}
		s := Sugerencia{Tipo: "productor", ID: pr.ID, Nombre: pr.Nombre, Telefono: pr.Telefono, Lugar: pr.Lugar, Razon: "Produce " + pr.Productos}
		if t.Lat != nil && t.Lng != nil {
			d := distanciaKm(*t.Lat, *t.Lng, pr.Lat, pr.Lng)
			if d > radioLocalKm && !propio {
				continue
			}
			s.DistanciaKm = &d
		}
		if propio {
			s.Razon = "Tu productor local de este producto"
		}
		locales = append(locales, s)
	}
	sort.SliceStable(locales, func(i, j int) bool {
		if locales[i].DistanciaKm == nil || locales[j].DistanciaKm == nil {
			return locales[i].DistanciaKm != nil
		}
		return *locales[i].DistanciaKm < *locales[j].DistanciaKm
	})
	if len(locales) > 3 {
		locales = locales[:3]
	}
	return append(out, locales...)
}

func (a *App) faltantes(w http.ResponseWriter, r *http.Request) {
	f, err := a.calcularFaltantes(tiendaDe(r))
	manejar(w, f, err)
}

// ---------------- Pedidos ----------------

type ItemPedido struct {
	ProductoID *int64 `json:"producto_id"`
	Nombre     string `json:"nombre"`
	Cantidad   int64  `json:"cantidad"`
	Recibido   bool   `json:"recibido"`
}

type PedidoInfo struct {
	ID          int64        `json:"id"`
	Tipo        string       `json:"tipo"` // proveedor | productor | otro
	Destino     string       `json:"destino"`
	Telefono    string       `json:"telefono"`
	Estado      string       `json:"estado"`
	Nota        string       `json:"nota"`
	Creado      int64        `json:"creado"`
	Actualizado int64        `json:"actualizado"`
	LlegoAuto   bool         `json:"llego_auto"`
	Atrasado    bool         `json:"atrasado"`
	Dias        int          `json:"dias"`
	Items       []ItemPedido `json:"items"`
	Mensaje     string       `json:"mensaje"`
	WhatsApp    string       `json:"whatsapp"`
}

// diasAtraso: un pedido abierto con más de estos días se marca "no ha llegado".
const diasAtraso = 3

func abierto(estado string) bool { return estado == "enviado" || estado == "confirmado" }

func linkWhatsApp(tel, texto string) string {
	var d strings.Builder
	for _, c := range tel {
		if c >= '0' && c <= '9' {
			d.WriteRune(c)
		}
	}
	n := d.String()
	if len(n) == 10 {
		n = "52" + n // México
	}
	if n == "" {
		return "https://wa.me/?text=" + url.QueryEscape(texto)
	}
	return "https://wa.me/" + n + "?text=" + url.QueryEscape(texto)
}

func (a *App) cargarPedido(id int64) (PedidoInfo, error) {
	var p PedidoInfo
	var prov, prod sql.NullInt64
	var tienda int64
	var auto int
	err := a.db.QueryRow(`SELECT id, tienda_id, proveedor_id, productor_id, destino, telefono, estado, nota, creado, actualizado, llego_auto
		FROM pedidos WHERE id = ?`, id).Scan(&p.ID, &tienda, &prov, &prod, &p.Destino, &p.Telefono, &p.Estado, &p.Nota, &p.Creado, &p.Actualizado, &auto)
	if err != nil {
		return p, err
	}
	p.LlegoAuto = auto == 1
	p.Tipo = "otro"
	if prov.Valid {
		p.Tipo = "proveedor"
	} else if prod.Valid {
		p.Tipo = "productor"
	}
	p.Dias = int(time.Since(time.UnixMilli(p.Creado)).Hours() / 24)
	p.Atrasado = abierto(p.Estado) && p.Dias >= diasAtraso
	rows, err := a.db.Query("SELECT producto_id, nombre, cantidad, recibido FROM pedido_items WHERE pedido_id = ? ORDER BY id", id)
	if err != nil {
		return p, err
	}
	defer rows.Close()
	p.Items = []ItemPedido{}
	for rows.Next() {
		var it ItemPedido
		var rec int
		rows.Scan(&it.ProductoID, &it.Nombre, &it.Cantidad, &rec)
		it.Recibido = rec == 1
		p.Items = append(p.Items, it)
	}
	var nombreTienda string
	a.db.QueryRow("SELECT nombre FROM tiendas WHERE id = ?", tienda).Scan(&nombreTienda)
	p.Mensaje = mensajePedido(nombreTienda, p)
	p.WhatsApp = linkWhatsApp(p.Telefono, p.Mensaje)
	return p, rows.Err()
}

func mensajePedido(tienda string, p PedidoInfo) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Hola %s, le escribo de %s. Quisiera pedirle:\n", p.Destino, tienda)
	for _, it := range p.Items {
		fmt.Fprintf(&b, "• %d × %s\n", it.Cantidad, it.Nombre)
	}
	if p.Nota != "" {
		fmt.Fprintf(&b, "Nota: %s\n", p.Nota)
	}
	fmt.Fprintf(&b, "¿Me confirma cuándo me lo puede traer? Gracias. (Pedido #%d)", p.ID)
	return b.String()
}

func (a *App) listarPedidos(w http.ResponseWriter, r *http.Request) {
	rows, err := a.db.Query(`SELECT id FROM pedidos WHERE tienda_id = ?
		ORDER BY CASE WHEN estado IN ('enviado','confirmado') THEN 0 ELSE 1 END, creado DESC LIMIT 200`, tiendaDe(r))
	if err != nil {
		manejar(w, nil, err)
		return
	}
	var ids []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	out := []PedidoInfo{}
	for _, id := range ids {
		p, err := a.cargarPedido(id)
		if err != nil {
			manejar(w, nil, err)
			return
		}
		out = append(out, p)
	}
	manejar(w, out, nil)
}

func (a *App) crearPedido(w http.ResponseWriter, r *http.Request) {
	tienda := tiendaDe(r)
	var d struct {
		ProveedorID *int64 `json:"proveedor_id"`
		ProductorID *int64 `json:"productor_id"`
		Destino     string `json:"destino"`
		Telefono    string `json:"telefono"`
		Nota        string `json:"nota"`
		Items       []struct {
			ProductoID *int64 `json:"producto_id"`
			Nombre     string `json:"nombre"`
			Cantidad   int64  `json:"cantidad"`
		} `json:"items"`
	}
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	destino, tel := strings.TrimSpace(d.Destino), strings.TrimSpace(d.Telefono)
	switch {
	case d.ProveedorID != nil:
		err := a.db.QueryRow("SELECT nombre, telefono FROM proveedores WHERE id = ? AND tienda_id = ?", *d.ProveedorID, tienda).Scan(&destino, &tel)
		if err != nil {
			manejar(w, nil, fallo(400, "Ese proveedor no existe"))
			return
		}
		d.ProductorID = nil
	case d.ProductorID != nil:
		if err := a.db.QueryRow("SELECT nombre, telefono FROM productores WHERE id = ?", *d.ProductorID).Scan(&destino, &tel); err != nil {
			manejar(w, nil, fallo(400, "Ese productor no existe"))
			return
		}
	}
	if destino == "" {
		manejar(w, nil, fallo(400, "¿A quién se le pide?"))
		return
	}
	if len(d.Items) == 0 {
		manejar(w, nil, fallo(400, "El pedido no tiene productos"))
		return
	}
	tx, err := a.db.Begin()
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer tx.Rollback()
	t := ahora()
	res, err := tx.Exec(`INSERT INTO pedidos (tienda_id, proveedor_id, productor_id, destino, telefono, estado, nota, creado, actualizado)
		VALUES (?, ?, ?, ?, ?, 'enviado', ?, ?, ?)`, tienda, d.ProveedorID, d.ProductorID, destino, tel, strings.TrimSpace(d.Nota), t, t)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	id, _ := res.LastInsertId()
	for _, it := range d.Items {
		nombre := strings.TrimSpace(it.Nombre)
		if it.ProductoID != nil {
			if err := tx.QueryRow("SELECT nombre FROM productos WHERE id = ? AND tienda_id = ?", *it.ProductoID, tienda).Scan(&nombre); err != nil {
				it.ProductoID = nil
			}
		}
		if nombre == "" || it.Cantidad <= 0 {
			manejar(w, nil, fallo(400, "Cada producto necesita nombre y cantidad"))
			return
		}
		if _, err := tx.Exec("INSERT INTO pedido_items (pedido_id, producto_id, nombre, cantidad) VALUES (?, ?, ?, ?)",
			id, it.ProductoID, nombre, it.Cantidad); err != nil {
			manejar(w, nil, err)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		manejar(w, nil, err)
		return
	}
	p, err := a.cargarPedido(id)
	manejar(w, p, err)
}

func (a *App) cambiarEstadoPedido(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	var d struct {
		Estado string `json:"estado"`
	}
	if err == nil {
		err = leerJSON(r, &d)
	}
	var actual string
	if err == nil {
		err = a.db.QueryRow("SELECT estado FROM pedidos WHERE id = ? AND tienda_id = ?", id, tiendaDe(r)).Scan(&actual)
	}
	if err == nil {
		switch {
		case d.Estado != "confirmado" && d.Estado != "llego" && d.Estado != "cancelado":
			err = fallo(400, "Estado inválido")
		case !abierto(actual):
			err = fallo(409, "Ese pedido ya está cerrado")
		}
	}
	if err == nil {
		_, err = a.db.Exec("UPDATE pedidos SET estado = ?, actualizado = ? WHERE id = ?", d.Estado, ahora(), id)
	}
	if err == nil && d.Estado == "llego" {
		_, err = a.db.Exec("UPDATE pedido_items SET recibido = 1 WHERE pedido_id = ?", id)
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	p, err := a.cargarPedido(id)
	manejar(w, p, err)
}

// recibirPorFoto: los productos cuyo hueco ya se ve lleno cuentan como
// recibidos; un pedido con todo recibido pasa a "llegó" sin que nadie lo toque.
func recibirPorFoto(tx *sql.Tx, tienda int64, llenos []int64, t int64) ([]int64, error) {
	if len(llenos) == 0 {
		return nil, nil
	}
	for _, pid := range llenos {
		if _, err := tx.Exec(`UPDATE pedido_items SET recibido = 1 WHERE producto_id = ? AND recibido = 0 AND pedido_id IN
			(SELECT id FROM pedidos WHERE tienda_id = ? AND estado IN ('enviado','confirmado'))`, pid, tienda); err != nil {
			return nil, err
		}
	}
	rows, err := tx.Query(`SELECT p.id FROM pedidos p WHERE p.tienda_id = ? AND p.estado IN ('enviado','confirmado')
		AND NOT EXISTS (SELECT 1 FROM pedido_items i WHERE i.pedido_id = p.id AND i.recibido = 0)`, tienda)
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
	for _, id := range ids {
		if _, err := tx.Exec("UPDATE pedidos SET estado = 'llego', llego_auto = 1, actualizado = ? WHERE id = ?", t, id); err != nil {
			return nil, err
		}
	}
	return ids, nil
}

// ---------------- Resumen ----------------

func (a *App) resumen(w http.ResponseWriter, r *http.Request) {
	tienda := tiendaDe(r)
	f, err := a.calcularFaltantes(tienda)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	sinPedir := 0
	for _, x := range f {
		if x.EnPedido == nil {
			sinPedir++
		}
	}
	var abiertos, atrasados, anaqueles, sinFoto, productos int
	limite := time.Now().AddDate(0, 0, -diasAtraso).UnixMilli()
	a.db.QueryRow("SELECT COUNT(*) FROM pedidos WHERE tienda_id = ? AND estado IN ('enviado','confirmado')", tienda).Scan(&abiertos)
	a.db.QueryRow("SELECT COUNT(*) FROM pedidos WHERE tienda_id = ? AND estado IN ('enviado','confirmado') AND creado <= ?", tienda, limite).Scan(&atrasados)
	a.db.QueryRow("SELECT COUNT(*) FROM anaqueles WHERE tienda_id = ?", tienda).Scan(&anaqueles)
	a.db.QueryRow("SELECT COUNT(*) FROM anaqueles WHERE tienda_id = ? AND (ultima_en IS NULL OR ultima_en < ?)", tienda, inicioDelDia()).Scan(&sinFoto)
	a.db.QueryRow("SELECT COUNT(*) FROM productos WHERE tienda_id = ?", tienda).Scan(&productos)
	manejar(w, map[string]int{
		"faltantes": len(f), "sin_pedir": sinPedir, "pedidos_abiertos": abiertos, "atrasados": atrasados,
		"anaqueles": anaqueles, "sin_foto_hoy": sinFoto, "productos": productos,
	}, nil)
}

// Durango: UTC-6 todo el año.
var zonaMX = time.FixedZone("CST", -6*3600)

func inicioDelDia() int64 {
	n := time.Now().In(zonaMX)
	return time.Date(n.Year(), n.Month(), n.Day(), 0, 0, 0, 0, zonaMX).UnixMilli()
}
