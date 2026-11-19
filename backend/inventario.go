package main

import (
	"database/sql"
	"net/http"
	"strconv"
	"strings"
)

// Inventario al estilo MatObra: la existencia (productos.stock) solo cambia
// con un movimiento, y cada movimiento deja un renglón en el kárdex con el
// saldo que quedó. Nunca se deja una existencia negativa.

type ejecutor interface {
	Exec(query string, args ...any) (sql.Result, error)
	QueryRow(query string, args ...any) *sql.Row
}

type Movimiento struct {
	ID         int64  `json:"id"`
	ProductoID int64  `json:"producto_id"`
	Producto   string `json:"producto,omitempty"`
	Tipo       string `json:"tipo"`
	Cantidad   int64  `json:"cantidad"`
	Saldo      int64  `json:"saldo"`
	Nota       string `json:"nota"`
	TratoID    *int64 `json:"trato_id,omitempty"`
	Creado     int64  `json:"creado"`
}

// mover suma `cantidad` (con signo) a la existencia del producto y anota el
// movimiento. Devuelve el saldo. Un producto "sin conteo" (stock NULL) empieza en 0.
func mover(ex ejecutor, tienda, producto int64, tipo string, cantidad int64, nota string, trato *int64) (int64, error) {
	var actual sql.NullInt64
	var nombre string
	if err := ex.QueryRow("SELECT stock, nombre FROM productos WHERE id = ? AND tienda_id = ?", producto, tienda).Scan(&actual, &nombre); err != nil {
		return 0, fallo(404, "Ese producto no existe")
	}
	saldo := actual.Int64 + cantidad
	if saldo < 0 {
		return 0, fallo(409, "No alcanza la existencia de «"+nombre+"»: hay "+strconv.FormatInt(actual.Int64, 10))
	}
	t := ahora()
	if _, err := ex.Exec("UPDATE productos SET stock = ?, actualizado = ? WHERE id = ?", saldo, t, producto); err != nil {
		return 0, err
	}
	_, err := ex.Exec(`INSERT INTO movimientos (tienda_id, producto_id, tipo, cantidad, saldo, nota, trato_id, creado)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`, tienda, producto, tipo, cantidad, saldo, recortarTexto(nota, 300), trato, t)
	return saldo, err
}

// registrarAjuste anota en el kárdex un cambio de existencia hecho por fuera
// de mover (edición manual o sincronización desde la app).
func registrarAjuste(ex ejecutor, tienda, producto int64, antes, despues *int64, nota string) {
	if despues == nil {
		return
	}
	var a int64
	if antes != nil {
		a = *antes
	}
	if a == *despues {
		return
	}
	ex.Exec(`INSERT INTO movimientos (tienda_id, producto_id, tipo, cantidad, saldo, nota, creado)
		VALUES (?, ?, 'ajuste', ?, ?, ?, ?)`, tienda, producto, *despues-a, *despues, nota, ahora())
}

// productoPorCodigo busca por código de barras (o por id si es un número chico).
func (a *App) productoPorCodigo(ex ejecutor, tienda int64, codigo string) (int64, error) {
	codigo = strings.TrimSpace(codigo)
	var id int64
	err := ex.QueryRow("SELECT id FROM productos WHERE tienda_id = ? AND codigo = ? AND codigo != ''", tienda, codigo).Scan(&id)
	if err == nil {
		return id, nil
	}
	return 0, fallo(404, "No tienes ningún producto con el código «"+codigo+"»")
}

type pedidoMovimiento struct {
	Tipo       string `json:"tipo"` // entrada | salida | venta | ajuste
	ProductoID int64  `json:"producto_id"`
	Codigo     string `json:"codigo"`
	Cantidad   int64  `json:"cantidad"`
	Nota       string `json:"nota"`
}

// aplicar resuelve un movimiento manual: entrada/salida/venta suman o restan
// la cantidad; ajuste deja la existencia en la cantidad (conteo físico).
func (a *App) aplicar(ex ejecutor, tienda int64, m pedidoMovimiento) (int64, error) {
	id := m.ProductoID
	if id == 0 {
		var err error
		if id, err = a.productoPorCodigo(ex, tienda, m.Codigo); err != nil {
			return 0, err
		}
	}
	switch m.Tipo {
	case "entrada":
		if m.Cantidad <= 0 {
			return 0, fallo(400, "La cantidad debe ser mayor que cero")
		}
		return mover(ex, tienda, id, "entrada", m.Cantidad, m.Nota, nil)
	case "salida", "venta":
		if m.Cantidad <= 0 {
			return 0, fallo(400, "La cantidad debe ser mayor que cero")
		}
		return mover(ex, tienda, id, m.Tipo, -m.Cantidad, m.Nota, nil)
	case "ajuste":
		if m.Cantidad < 0 {
			return 0, fallo(400, "El conteo no puede ser negativo")
		}
		var actual sql.NullInt64
		ex.QueryRow("SELECT stock FROM productos WHERE id = ? AND tienda_id = ?", id, tienda).Scan(&actual)
		nota := m.Nota
		if nota == "" {
			nota = "Conteo físico"
		}
		return mover(ex, tienda, id, "ajuste", m.Cantidad-actual.Int64, nota, nil)
	}
	return 0, fallo(400, "El tipo debe ser entrada, salida, venta o ajuste")
}

func (a *App) crearMovimiento(w http.ResponseWriter, r *http.Request) {
	var m pedidoMovimiento
	if err := leerJSON(r, &m); err != nil {
		manejar(w, nil, err)
		return
	}
	tx, err := a.db.Begin()
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer tx.Rollback()
	saldo, err := a.aplicar(tx, tiendaDe(r), m)
	if err == nil {
		err = tx.Commit()
	}
	if err == nil && m.Tipo == "entrada" {
		id := m.ProductoID
		if id == 0 {
			id, _ = a.productoPorCodigo(a.db, tiendaDe(r), m.Codigo)
		}
		a.buscarInteresados(tiendaDe(r), id)
	}
	manejar(w, map[string]int64{"saldo": saldo}, err)
}

// crearLote registra lo escaneado con la pistola (o la cámara) como una
// "caja de súper": todos los renglones entran o ninguno.
func (a *App) crearLote(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Tipo  string             `json:"tipo"`
		Nota  string             `json:"nota"`
		Items []pedidoMovimiento `json:"items"`
	}
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	if len(d.Items) == 0 || len(d.Items) > 500 {
		manejar(w, nil, fallo(400, "Escanea al menos un producto"))
		return
	}
	tienda := tiendaDe(r)
	tx, err := a.db.Begin()
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer tx.Rollback()
	for _, it := range d.Items {
		it.Tipo, it.Nota = d.Tipo, d.Nota
		if _, err := a.aplicar(tx, tienda, it); err != nil {
			manejar(w, nil, err)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		manejar(w, nil, err)
		return
	}
	manejar(w, map[string]int{"registrados": len(d.Items)}, nil)
}

func (a *App) listarMovimientos(w http.ResponseWriter, r *http.Request) {
	q := `SELECT m.id, m.producto_id, p.nombre, m.tipo, m.cantidad, m.saldo, m.nota, m.trato_id, m.creado
		FROM movimientos m JOIN productos p ON p.id = m.producto_id WHERE m.tienda_id = ?`
	args := []any{tiendaDe(r)}
	if p, err := strconv.ParseInt(r.URL.Query().Get("producto"), 10, 64); err == nil {
		q += " AND m.producto_id = ?"
		args = append(args, p)
	}
	q += " ORDER BY m.creado DESC, m.id DESC LIMIT 200"
	rows, err := a.db.Query(q, args...)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer rows.Close()
	out := []Movimiento{}
	for rows.Next() {
		var m Movimiento
		rows.Scan(&m.ID, &m.ProductoID, &m.Producto, &m.Tipo, &m.Cantidad, &m.Saldo, &m.Nota, &m.TratoID, &m.Creado)
		out = append(out, m)
	}
	manejar(w, out, rows.Err())
}

func (a *App) productoDeCodigo(w http.ResponseWriter, r *http.Request) {
	id, err := a.productoPorCodigo(a.db, tiendaDe(r), r.PathValue("codigo"))
	if err != nil {
		manejar(w, nil, err)
		return
	}
	p, err := scanProducto(a.db.QueryRow(selProducto+" WHERE p.id = ?", id))
	manejar(w, p, err)
}

// vendidos30 = piezas que salieron (venta/salida/trato) por producto en 30 días.
func (a *App) vendidos30(tienda int64) map[int64]int64 {
	out := map[int64]int64{}
	rows, err := a.db.Query(`SELECT producto_id, -SUM(cantidad) FROM movimientos
		WHERE tienda_id = ? AND tipo IN ('venta','salida','trato_salida') AND creado > ? GROUP BY producto_id`,
		tienda, ahora()-30*24*3600*1000)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var id, n int64
		rows.Scan(&id, &n)
		out[id] = n
	}
	return out
}
