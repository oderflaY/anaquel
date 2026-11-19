package main

import (
	"database/sql"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

type Zona struct {
	ID         int64   `json:"id"`
	ProductoID *int64  `json:"producto_id"`
	Producto   string  `json:"producto"`
	X          float64 `json:"x"`
	Y          float64 `json:"y"`
	W          float64 `json:"w"`
	H          float64 `json:"h"`
	Nivel      string  `json:"nivel"`
	Score      float64 `json:"score"`
}

type Anaquel struct {
	ID         int64   `json:"id"`
	Nombre     string  `json:"nombre"`
	FotoRef    string  `json:"foto_ref"`
	UltimaFoto *string `json:"ultima_foto"`
	UltimaEn   *int64  `json:"ultima_en"`
	Zonas      []Zona  `json:"zonas"`
	Faltantes  int     `json:"faltantes"`
}

func urlMedia(archivo string) string { return "/media/" + archivo }

// guardarFoto lee el campo "foto" del formulario, la recomprime y la guarda
// con un nombre aleatorio. Devuelve el nombre de archivo y los bytes.
func (a *App) guardarFoto(r *http.Request) (string, []byte, error) {
	r.Body = http.MaxBytesReader(nil, r.Body, 15<<20)
	f, _, err := r.FormFile("foto")
	if err != nil {
		return "", nil, fallo(400, "Falta la foto")
	}
	defer f.Close()
	raw, err := io.ReadAll(f)
	if err != nil {
		return "", nil, fallo(400, "La foto es demasiado grande (máx. 15 MB)")
	}
	b, err := normalizarFoto(raw)
	if err != nil {
		return "", nil, err
	}
	nombre := tokenAleatorio() + ".jpg"
	if err := os.WriteFile(filepath.Join(a.media, nombre), b, 0o644); err != nil {
		return "", nil, err
	}
	return nombre, b, nil
}

func (a *App) anaquelDeTienda(id, tienda int64) error {
	var n int
	a.db.QueryRow("SELECT COUNT(*) FROM anaqueles WHERE id = ? AND tienda_id = ?", id, tienda).Scan(&n)
	if n == 0 {
		return fallo(404, "Ese anaquel no existe")
	}
	return nil
}

func (a *App) cargarAnaquel(id int64) (Anaquel, error) {
	var an Anaquel
	err := a.db.QueryRow("SELECT id, nombre, foto_ref, ultima_foto, ultima_en FROM anaqueles WHERE id = ?", id).
		Scan(&an.ID, &an.Nombre, &an.FotoRef, &an.UltimaFoto, &an.UltimaEn)
	if err != nil {
		return an, err
	}
	an.FotoRef = urlMedia(an.FotoRef)
	if an.UltimaFoto != nil {
		u := urlMedia(*an.UltimaFoto)
		an.UltimaFoto = &u
	}
	rows, err := a.db.Query(`SELECT z.id, z.producto_id, COALESCE(p.nombre, ''), z.x, z.y, z.w, z.h, z.nivel, z.score
		FROM zonas z LEFT JOIN productos p ON p.id = z.producto_id WHERE z.anaquel_id = ? ORDER BY z.y, z.x`, id)
	if err != nil {
		return an, err
	}
	defer rows.Close()
	an.Zonas = []Zona{}
	for rows.Next() {
		var z Zona
		rows.Scan(&z.ID, &z.ProductoID, &z.Producto, &z.X, &z.Y, &z.W, &z.H, &z.Nivel, &z.Score)
		if z.Nivel != "lleno" {
			an.Faltantes++
		}
		an.Zonas = append(an.Zonas, z)
	}
	return an, rows.Err()
}

func (a *App) listarAnaqueles(w http.ResponseWriter, r *http.Request) {
	rows, err := a.db.Query("SELECT id FROM anaqueles WHERE tienda_id = ? ORDER BY nombre", tiendaDe(r))
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
	out := []Anaquel{}
	for _, id := range ids {
		an, err := a.cargarAnaquel(id)
		if err != nil {
			manejar(w, nil, err)
			return
		}
		out = append(out, an)
	}
	manejar(w, out, nil)
}

func (a *App) crearAnaquel(w http.ResponseWriter, r *http.Request) {
	archivo, _, err := a.guardarFoto(r)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	nombre := strings.TrimSpace(r.FormValue("nombre"))
	if nombre == "" {
		nombre = "Anaquel"
	}
	res, err := a.db.Exec("INSERT INTO anaqueles (tienda_id, nombre, foto_ref, creado) VALUES (?, ?, ?, ?)",
		tiendaDe(r), nombre, archivo, ahora())
	if err != nil {
		manejar(w, nil, err)
		return
	}
	id, _ := res.LastInsertId()
	an, err := a.cargarAnaquel(id)
	manejar(w, an, err)
}

func (a *App) verAnaquel(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err == nil {
		err = a.anaquelDeTienda(id, tiendaDe(r))
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	an, err := a.cargarAnaquel(id)
	manejar(w, an, err)
}

func (a *App) renombrarAnaquel(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err == nil {
		err = a.anaquelDeTienda(id, tiendaDe(r))
	}
	var d struct {
		Nombre string `json:"nombre"`
	}
	if err == nil {
		err = leerJSON(r, &d)
	}
	if err == nil && strings.TrimSpace(d.Nombre) == "" {
		err = fallo(400, "Escribe un nombre")
	}
	if err == nil {
		_, err = a.db.Exec("UPDATE anaqueles SET nombre = ? WHERE id = ?", strings.TrimSpace(d.Nombre), id)
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	an, err := a.cargarAnaquel(id)
	manejar(w, an, err)
}

func (a *App) borrarAnaquel(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err == nil {
		err = a.anaquelDeTienda(id, tiendaDe(r))
	}
	if err == nil {
		_, err = a.db.Exec("DELETE FROM anaqueles WHERE id = ?", id)
	}
	manejar(w, map[string]bool{"ok": err == nil}, err)
}

// guardarZonas reemplaza las zonas del anaquel. Las que traen id se conservan
// (y con ellas su último nivel); las nuevas empiezan "lleno".
func (a *App) guardarZonas(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err == nil {
		err = a.anaquelDeTienda(id, tiendaDe(r))
	}
	var zonas []Zona
	if err == nil {
		err = leerJSON(r, &zonas)
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	tx, err := a.db.Begin()
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer tx.Rollback()
	conservar := []any{id}
	marcas := []string{}
	for _, z := range zonas {
		if z.ID > 0 {
			conservar = append(conservar, z.ID)
			marcas = append(marcas, "?")
		}
	}
	q := "DELETE FROM zonas WHERE anaquel_id = ?"
	if len(marcas) > 0 {
		q += " AND id NOT IN (" + strings.Join(marcas, ",") + ")"
	}
	if _, err := tx.Exec(q, conservar...); err != nil {
		manejar(w, nil, err)
		return
	}
	for _, z := range zonas {
		z.X, z.Y = recortar(z.X), recortar(z.Y)
		z.W, z.H = recortar(z.W), recortar(z.H)
		if z.W < 0.01 || z.H < 0.01 {
			continue
		}
		if z.ProductoID != nil {
			var n int
			tx.QueryRow("SELECT COUNT(*) FROM productos WHERE id = ? AND tienda_id = ?", *z.ProductoID, tiendaDe(r)).Scan(&n)
			if n == 0 {
				z.ProductoID = nil
			}
		}
		if z.ID > 0 {
			_, err = tx.Exec("UPDATE zonas SET producto_id = ?, x = ?, y = ?, w = ?, h = ? WHERE id = ? AND anaquel_id = ?",
				z.ProductoID, z.X, z.Y, z.W, z.H, z.ID, id)
		} else {
			_, err = tx.Exec("INSERT INTO zonas (anaquel_id, producto_id, x, y, w, h) VALUES (?, ?, ?, ?, ?, ?)",
				id, z.ProductoID, z.X, z.Y, z.W, z.H)
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
	an, err := a.cargarAnaquel(id)
	manejar(w, an, err)
}

func recortar(v float64) float64 { return max(0, min(1, v)) }

// nuevaReferencia cambia la foto del anaquel lleno (p. ej. se reacomodó).
func (a *App) nuevaReferencia(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err == nil {
		err = a.anaquelDeTienda(id, tiendaDe(r))
	}
	var archivo string
	if err == nil {
		archivo, _, err = a.guardarFoto(r)
	}
	if err == nil {
		_, err = a.db.Exec("UPDATE anaqueles SET foto_ref = ? WHERE id = ?", archivo, id)
	}
	if err == nil {
		_, err = a.db.Exec("UPDATE zonas SET nivel = 'lleno', score = 1 WHERE anaquel_id = ?", id)
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	an, err := a.cargarAnaquel(id)
	manejar(w, an, err)
}

type ResultadoFoto struct {
	Anaquel   Anaquel      `json:"anaquel"`
	Metodo    string       `json:"metodo"`
	Faltantes []Zona       `json:"faltantes"`
	Llegaron  []PedidoInfo `json:"llegaron"` // pedidos que esta foto confirmó
}

// fotoDelDia: la foto de hoy se compara con la de referencia, se actualiza el
// nivel de cada zona y, si un hueco que estaba pedido ya se llenó, el pedido
// se marca como recibido solo.
func (a *App) fotoDelDia(w http.ResponseWriter, r *http.Request) {
	tienda := tiendaDe(r)
	id, err := idRuta(r)
	if err == nil {
		err = a.anaquelDeTienda(id, tienda)
	}
	var archivo string
	var hoy []byte
	if err == nil {
		archivo, hoy, err = a.guardarFoto(r)
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	an, err := a.cargarAnaquel(id)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	ref, err := os.ReadFile(filepath.Join(a.media, strings.TrimPrefix(an.FotoRef, "/media/")))
	if err != nil {
		manejar(w, nil, err)
		return
	}
	zv := make([]ZonaVision, 0, len(an.Zonas))
	for _, z := range an.Zonas {
		zv = append(zv, ZonaVision{ID: z.ID, Nombre: z.Producto, X: z.X, Y: z.Y, W: z.W, H: z.H})
	}
	metodo := a.vision.Nombre()
	var lecturas []Lectura
	if len(zv) > 0 {
		lecturas, err = a.vision.Analizar(ref, hoy, zv)
		if err != nil {
			manejar(w, nil, fallo(400, err.Error()))
			return
		}
	}
	t := ahora()
	tx, err := a.db.Begin()
	if err != nil {
		manejar(w, nil, err)
		return
	}
	defer tx.Rollback()
	faltan := 0
	llenas := []int64{} // productos cuyo hueco ya está lleno
	for _, l := range lecturas {
		tx.Exec("UPDATE zonas SET nivel = ?, score = ?, actualizado = ? WHERE id = ?", l.Nivel, l.Score, t, l.ZonaID)
		if l.Nivel != "lleno" {
			faltan++
		} else {
			var pid sql.NullInt64
			tx.QueryRow("SELECT producto_id FROM zonas WHERE id = ?", l.ZonaID).Scan(&pid)
			if pid.Valid {
				llenas = append(llenas, pid.Int64)
			}
		}
	}
	tx.Exec("UPDATE anaqueles SET ultima_foto = ?, ultima_en = ? WHERE id = ?", archivo, t, id)
	tx.Exec("INSERT INTO fotos (anaquel_id, archivo, metodo, faltantes, creado) VALUES (?, ?, ?, ?, ?)", id, archivo, metodo, faltan, t)
	llegaron, err := recibirPorFoto(tx, tienda, llenas, t)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	if err := tx.Commit(); err != nil {
		manejar(w, nil, err)
		return
	}
	res := ResultadoFoto{Metodo: metodo, Faltantes: []Zona{}, Llegaron: []PedidoInfo{}}
	res.Anaquel, err = a.cargarAnaquel(id)
	for _, z := range res.Anaquel.Zonas {
		if z.Nivel != "lleno" {
			res.Faltantes = append(res.Faltantes, z)
		}
	}
	for _, pid := range llegaron {
		if p, err := a.cargarPedido(pid); err == nil {
			res.Llegaron = append(res.Llegaron, p)
		}
	}
	manejar(w, res, err)
}
