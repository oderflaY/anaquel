package main

import (
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

// Vender al mayoreo: cualquier tienda (aunque sea chiquita) publica sus
// productos con precio de mayoreo, como los distribuidores, y decide quién
// los ve: toda la red o solo las tiendas que elija.

// visibleSQL: condición «la tienda ? puede ver este producto» (lleva dos ? con
// la misma tienda). Para la vista pública se pasa -1 y solo pasa 'todos'.
func visibleSQL(alias string) string {
	return fmt.Sprintf(`(%[1]s.audiencia = 'todos' OR %[1]s.tienda_id = ? OR EXISTS (SELECT 1 FROM producto_audiencia pa
		WHERE pa.producto_id = %[1]s.id AND pa.tienda_id = ?))`, alias)
}

func (a *App) puedeVer(tienda, producto int64) bool {
	var n int
	a.db.QueryRow("SELECT COUNT(*) FROM productos p WHERE p.id = ? AND "+visibleSQL("p"), producto, tienda, tienda).Scan(&n)
	return n > 0
}

type ProductoMayoreo struct {
	ID             int64   `json:"id"`
	Nombre         string  `json:"nombre"`
	Categoria      string  `json:"categoria"`
	Unidad         string  `json:"unidad"`
	Foto           string  `json:"foto,omitempty"`
	PrecioC        int64   `json:"precio_c"`
	PrecioMayoreoC int64   `json:"precio_mayoreo_c"`
	MinimoMayoreo  int64   `json:"minimo_mayoreo"`
	Stock          *int64  `json:"stock"`
	Publicado      bool    `json:"publicado"`
	Audiencia      string  `json:"audiencia"` // todos | elegidos
	Elegidos       []int64 `json:"elegidos"`
}

type TiendaElegible struct {
	ID       int64  `json:"id"`
	Nombre   string `json:"nombre"`
	Giro     string `json:"giro"`
	Contacto bool   `json:"contacto"` // ya platicamos o hicimos trato
}

type MiMayoreo struct {
	Productos []ProductoMayoreo `json:"productos"`
	Tiendas   []TiendaElegible  `json:"tiendas"`
}

// GET /api/mayoreo: mis productos con su precio de mayoreo y a quién se los muestro,
// más las tiendas de la red que puedo elegir (primero con las que ya trato).
func (a *App) verMayoreo(w http.ResponseWriter, r *http.Request) {
	out, err := a.miMayoreo(tiendaDe(r))
	manejar(w, out, err)
}

func (a *App) miMayoreo(yo int64) (MiMayoreo, error) {
	out := MiMayoreo{Productos: []ProductoMayoreo{}, Tiendas: []TiendaElegible{}}
	rows, err := a.db.Query(`SELECT id, nombre, categoria, unidad, foto, precio_c, precio_mayoreo_c, minimo_mayoreo, stock, publicado, audiencia
		FROM productos WHERE tienda_id = ? ORDER BY publicado DESC, categoria, nombre COLLATE NOCASE`, yo)
	if err != nil {
		return out, err
	}
	idx := map[int64]int{}
	for rows.Next() {
		p := ProductoMayoreo{Elegidos: []int64{}}
		if err := rows.Scan(&p.ID, &p.Nombre, &p.Categoria, &p.Unidad, &p.Foto, &p.PrecioC, &p.PrecioMayoreoC, &p.MinimoMayoreo,
			&p.Stock, &p.Publicado, &p.Audiencia); err != nil {
			rows.Close()
			return out, err
		}
		idx[p.ID] = len(out.Productos)
		out.Productos = append(out.Productos, p)
	}
	rows.Close()
	rows, err = a.db.Query(`SELECT pa.producto_id, pa.tienda_id FROM producto_audiencia pa
		JOIN productos p ON p.id = pa.producto_id WHERE p.tienda_id = ?`, yo)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var pid, tid int64
		rows.Scan(&pid, &tid)
		if i, ok := idx[pid]; ok {
			out.Productos[i].Elegidos = append(out.Productos[i].Elegidos, tid)
		}
	}
	rows.Close()
	rows, err = a.db.Query(`SELECT t.id, t.nombre, t.giro,
		EXISTS (SELECT 1 FROM chats c WHERE (c.tienda_a = ? AND c.tienda_b = t.id) OR (c.tienda_b = ? AND c.tienda_a = t.id)) OR
		EXISTS (SELECT 1 FROM tratos x WHERE (x.comprador_id = ? AND x.vendedor_id = t.id) OR (x.vendedor_id = ? AND x.comprador_id = t.id))
		FROM tiendas t WHERE t.id != ? ORDER BY 4 DESC, t.nombre COLLATE NOCASE`, yo, yo, yo, yo, yo)
	if err != nil {
		return out, err
	}
	for rows.Next() {
		var t TiendaElegible
		rows.Scan(&t.ID, &t.Nombre, &t.Giro, &t.Contacto)
		out.Tiendas = append(out.Tiendas, t)
	}
	rows.Close()
	return out, nil
}

type cambioMayoreo struct {
	Publicado      bool    `json:"publicado"`
	PrecioMayoreoC int64   `json:"precio_mayoreo_c"`
	MinimoMayoreo  int64   `json:"minimo_mayoreo"`
	Audiencia      string  `json:"audiencia"`
	Elegidos       []int64 `json:"elegidos"`
}

// PUT /api/mayoreo/{id}: publica o retira un producto y fija quién lo ve.
func (a *App) guardarMayoreo(w http.ResponseWriter, r *http.Request) {
	yo := tiendaDe(r)
	id, err := idRuta(r)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	var d cambioMayoreo
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	var nombre string
	var precio int64
	var antes []int64
	var publicadoAntes bool
	if a.db.QueryRow("SELECT nombre, precio_c, publicado FROM productos WHERE id = ? AND tienda_id = ?", id, yo).Scan(&nombre, &precio, &publicadoAntes) != nil {
		manejar(w, nil, fallo(404, "Ese producto no es tuyo"))
		return
	}
	if d.Audiencia != "elegidos" {
		d.Audiencia = "todos"
	}
	if d.Publicado {
		switch {
		case d.PrecioMayoreoC <= 0:
			manejar(w, nil, fallo(400, "Pon el precio de mayoreo"))
			return
		case precio > 0 && d.PrecioMayoreoC > precio:
			manejar(w, nil, fallo(400, "El precio de mayoreo no puede ser mayor que el de menudeo"))
			return
		case d.MinimoMayoreo < 1:
			manejar(w, nil, fallo(400, "Pon desde cuántas piezas aplica el mayoreo"))
			return
		case d.Audiencia == "elegidos" && len(d.Elegidos) == 0:
			manejar(w, nil, fallo(400, "Elige al menos una tienda que pueda verlo"))
			return
		}
	}
	if len(d.Elegidos) > 300 {
		d.Elegidos = d.Elegidos[:300]
	}
	rows, err := a.db.Query("SELECT tienda_id FROM producto_audiencia WHERE producto_id = ?", id)
	if err == nil {
		for rows.Next() {
			var t int64
			rows.Scan(&t)
			antes = append(antes, t)
		}
		rows.Close()
	}
	tx, err := a.db.Begin()
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`UPDATE productos SET publicado = ?, precio_mayoreo_c = ?, minimo_mayoreo = ?, audiencia = ?, actualizado = ?
		WHERE id = ? AND tienda_id = ?`, d.Publicado, d.PrecioMayoreoC, d.MinimoMayoreo, d.Audiencia, ahora(), id, yo); err != nil {
		manejar(w, nil, err)
		return
	}
	tx.Exec("DELETE FROM producto_audiencia WHERE producto_id = ?", id)
	var nuevos []int64
	if d.Audiencia == "elegidos" {
		ya := map[int64]bool{}
		for _, t := range antes {
			ya[t] = true
		}
		for _, t := range d.Elegidos {
			if t == yo {
				continue
			}
			if r, err := tx.Exec("INSERT OR IGNORE INTO producto_audiencia (producto_id, tienda_id) SELECT ?, id FROM tiendas WHERE id = ?", id, t); err == nil {
				if n, _ := r.RowsAffected(); n > 0 && (!ya[t] || !publicadoAntes) {
					nuevos = append(nuevos, t)
				}
			}
		}
	}
	if err := tx.Commit(); err != nil {
		manejar(w, nil, err)
		return
	}
	// A quien se lo acabo de compartir le llega un aviso (precio exclusivo).
	if d.Publicado {
		quien := strings.TrimSuffix(a.nombreTienda(yo), " (demo)")
		for _, t := range nuevos {
			a.avisar(t, fmt.Sprintf("mayoreo:%d:%d", id, t), "red",
				fmt.Sprintf("%s te compartió un precio de mayoreo solo para ti: %s a %s desde %d.", quien, nombre, pesosTxt(d.PrecioMayoreoC), d.MinimoMayoreo),
				"/panel/red?q="+url.QueryEscape(nombre))
		}
		a.buscarInteresados(yo, id)
	}
	out, err := a.miMayoreo(yo)
	manejar(w, out, err)
}
