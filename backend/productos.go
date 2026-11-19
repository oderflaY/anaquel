package main

import (
	"database/sql"
	"net/http"
	"strings"
)

type Producto struct {
	ID          int64  `json:"id"`
	Codigo      string `json:"codigo"`
	Nombre      string `json:"nombre"`
	Categoria   string `json:"categoria"`
	PrecioC     int64  `json:"precio_c"`
	Stock       *int64 `json:"stock"`
	StockMin    int64  `json:"stock_min"`
	ProveedorID *int64 `json:"proveedor_id"`
	ProductorID *int64 `json:"productor_id"`
	Productor   string `json:"productor,omitempty"`
	// Vitrina: lo que la tienda ofrece a otras tiendas de la red.
	Publicado      bool   `json:"publicado"`
	PrecioMayoreoC int64  `json:"precio_mayoreo_c"`
	MinimoMayoreo  int64  `json:"minimo_mayoreo"`
	Unidad         string `json:"unidad"`
	Descripcion    string `json:"descripcion"`
}

const selProducto = `SELECT p.id, p.codigo, p.nombre, p.categoria, p.precio_c, p.stock, p.stock_min,
	p.proveedor_id, p.productor_id, COALESCE(pr.nombre, ''),
	p.publicado, p.precio_mayoreo_c, p.minimo_mayoreo, p.unidad, p.descripcion
	FROM productos p LEFT JOIN productores pr ON pr.id = p.productor_id`

func scanProducto(s interface{ Scan(...any) error }) (Producto, error) {
	var p Producto
	err := s.Scan(&p.ID, &p.Codigo, &p.Nombre, &p.Categoria, &p.PrecioC, &p.Stock, &p.StockMin,
		&p.ProveedorID, &p.ProductorID, &p.Productor,
		&p.Publicado, &p.PrecioMayoreoC, &p.MinimoMayoreo, &p.Unidad, &p.Descripcion)
	return p, err
}

func (a *App) productos(tienda int64) ([]Producto, error) {
	rows, err := a.db.Query(selProducto+" WHERE p.tienda_id = ? ORDER BY p.nombre COLLATE NOCASE", tienda)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Producto{}
	for rows.Next() {
		p, err := scanProducto(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (a *App) listarProductos(w http.ResponseWriter, r *http.Request) {
	p, err := a.productos(tiendaDe(r))
	manejar(w, p, err)
}

// validarRelaciones evita que una tienda apunte a proveedores de otra.
func (a *App) validarRelaciones(tienda int64, p *Producto) {
	if p.ProveedorID != nil {
		var n int
		a.db.QueryRow("SELECT COUNT(*) FROM proveedores WHERE id = ? AND tienda_id = ?", *p.ProveedorID, tienda).Scan(&n)
		if n == 0 {
			p.ProveedorID = nil
		}
	}
	if p.ProductorID != nil {
		var n int
		a.db.QueryRow("SELECT COUNT(*) FROM productores WHERE id = ?", *p.ProductorID).Scan(&n)
		if n == 0 {
			p.ProductorID = nil
		}
	}
}

func (a *App) crearProducto(w http.ResponseWriter, r *http.Request) {
	var p Producto
	if err := leerJSON(r, &p); err != nil {
		manejar(w, nil, err)
		return
	}
	p.Nombre = strings.TrimSpace(p.Nombre)
	if p.Nombre == "" {
		manejar(w, nil, fallo(400, "Escribe el nombre del producto"))
		return
	}
	a.validarRelaciones(tiendaDe(r), &p)
	limpiarVitrina(&p)
	res, err := a.db.Exec(`INSERT INTO productos (tienda_id, codigo, nombre, categoria, precio_c, stock, stock_min,
		proveedor_id, productor_id, publicado, precio_mayoreo_c, minimo_mayoreo, unidad, descripcion, actualizado)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		tiendaDe(r), strings.TrimSpace(p.Codigo), p.Nombre, strings.TrimSpace(p.Categoria), max(0, p.PrecioC),
		p.Stock, max(0, p.StockMin), p.ProveedorID, p.ProductorID, p.Publicado, p.PrecioMayoreoC, p.MinimoMayoreo,
		p.Unidad, p.Descripcion, ahora())
	if err != nil {
		manejar(w, nil, err)
		return
	}
	id, _ := res.LastInsertId()
	if p.Stock != nil && *p.Stock > 0 {
		a.db.Exec(`INSERT INTO movimientos (tienda_id, producto_id, tipo, cantidad, saldo, nota, creado)
			VALUES (?, ?, 'entrada', ?, ?, 'Existencia inicial', ?)`, tiendaDe(r), id, *p.Stock, *p.Stock, ahora())
	}
	if p.Publicado {
		a.buscarInteresados(tiendaDe(r), id)
	}
	out, err := scanProducto(a.db.QueryRow(selProducto+" WHERE p.id = ?", id))
	manejar(w, out, err)
}

func (a *App) editarProducto(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	var p Producto
	if err == nil {
		err = leerJSON(r, &p)
	}
	if err == nil && strings.TrimSpace(p.Nombre) == "" {
		err = fallo(400, "Escribe el nombre del producto")
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	a.validarRelaciones(tiendaDe(r), &p)
	limpiarVitrina(&p)
	var antes *int64
	var publicadoAntes bool
	a.db.QueryRow("SELECT stock, publicado FROM productos WHERE id = ? AND tienda_id = ?", id, tiendaDe(r)).Scan(&antes, &publicadoAntes)
	res, err := a.db.Exec(`UPDATE productos SET codigo = ?, nombre = ?, categoria = ?, precio_c = ?, stock = ?,
		stock_min = ?, proveedor_id = ?, productor_id = ?, publicado = ?, precio_mayoreo_c = ?, minimo_mayoreo = ?,
		unidad = ?, descripcion = ?, actualizado = ? WHERE id = ? AND tienda_id = ?`,
		strings.TrimSpace(p.Codigo), strings.TrimSpace(p.Nombre), strings.TrimSpace(p.Categoria), max(0, p.PrecioC),
		p.Stock, max(0, p.StockMin), p.ProveedorID, p.ProductorID, p.Publicado, p.PrecioMayoreoC, p.MinimoMayoreo,
		p.Unidad, p.Descripcion, ahora(), id, tiendaDe(r))
	if err == nil {
		registrarAjuste(a.db, tiendaDe(r), id, antes, p.Stock, "Corrección manual")
		if p.Publicado && !publicadoAntes {
			a.buscarInteresados(tiendaDe(r), id)
		}
	}
	if err == nil {
		if n, _ := res.RowsAffected(); n == 0 {
			err = fallo(404, "Ese producto no existe")
		}
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	out, err := scanProducto(a.db.QueryRow(selProducto+" WHERE p.id = ?", id))
	manejar(w, out, err)
}

func (a *App) borrarProducto(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err == nil {
		_, err = a.db.Exec("DELETE FROM productos WHERE id = ? AND tienda_id = ?", id, tiendaDe(r))
	}
	manejar(w, map[string]bool{"ok": err == nil}, err)
}

// syncProductos recibe el inventario de la app (que funciona sin internet) y
// lo refleja en la nube: actualiza por código de barras (o por nombre si no
// tiene) y crea los que falten. Nunca borra: en la web pueden existir
// productos que la app no tiene.
func (a *App) syncProductos(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Productos []Producto `json:"productos"`
	}
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	tienda := tiendaDe(r)
	tx, err := a.db.Begin()
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer tx.Rollback()
	creados, actualizados := 0, 0
	t := ahora()
	for _, p := range d.Productos {
		p.Nombre = strings.TrimSpace(p.Nombre)
		p.Codigo = strings.TrimSpace(p.Codigo)
		if p.Nombre == "" {
			continue
		}
		var id int64
		var err error
		if p.Codigo != "" {
			err = tx.QueryRow("SELECT id FROM productos WHERE tienda_id = ? AND codigo = ?", tienda, p.Codigo).Scan(&id)
		} else {
			err = tx.QueryRow("SELECT id FROM productos WHERE tienda_id = ? AND codigo = '' AND nombre = ? COLLATE NOCASE", tienda, p.Nombre).Scan(&id)
		}
		switch {
		case err == sql.ErrNoRows:
			_, err = tx.Exec(`INSERT INTO productos (tienda_id, codigo, nombre, categoria, precio_c, stock, stock_min, actualizado)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)`, tienda, p.Codigo, p.Nombre, p.Categoria, max(0, p.PrecioC), p.Stock, max(0, p.StockMin), t)
			creados++
		case err == nil:
			var antes *int64
			tx.QueryRow("SELECT stock FROM productos WHERE id = ?", id).Scan(&antes)
			registrarAjuste(tx, tienda, id, antes, p.Stock, "Sincronizado desde la app")
			_, err = tx.Exec(`UPDATE productos SET nombre = ?, categoria = ?, precio_c = ?, stock = ?, stock_min = ?, actualizado = ?
				WHERE id = ?`, p.Nombre, p.Categoria, max(0, p.PrecioC), p.Stock, max(0, p.StockMin), t, id)
			actualizados++
		}
		if err != nil {
			manejar(w, nil, err)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		manejar(w, nil, err)
		return
	}
	manejar(w, map[string]int{"creados": creados, "actualizados": actualizados}, nil)
}

// limpiarVitrina deja valores razonables en los campos de la vitrina.
func limpiarVitrina(p *Producto) {
	p.Unidad = strings.TrimSpace(p.Unidad)
	if p.Unidad == "" {
		p.Unidad = "pza"
	}
	p.Descripcion = recortarTexto(strings.TrimSpace(p.Descripcion), 400)
	p.PrecioMayoreoC = max(0, p.PrecioMayoreoC)
	p.MinimoMayoreo = max(0, p.MinimoMayoreo)
}
