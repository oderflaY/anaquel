package mercado

import (
	"context"
	"errors"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"anaquel/servicios/internal/imagen"
	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
)

type Foto struct {
	ID    int64  `json:"id"`
	URL   string `json:"url"`
	Alt   string `json:"alt"`
	Ancho int    `json:"ancho"`
	Alto  int    `json:"alto"`
}

type VendedorCorto struct {
	Nombre  string `json:"nombre"`
	Slug    string `json:"slug"`
	Colonia string `json:"colonia"`
}

type Publicacion struct {
	ID          int64         `json:"id"`
	Titulo      string        `json:"titulo"`
	Descripcion string        `json:"descripcion,omitempty"`
	Categoria   string        `json:"categoria"`
	PrecioC     int64         `json:"precio_c"`
	Unidad      string        `json:"unidad"`
	Existencias *int          `json:"existencias"`
	Minimo      int           `json:"minimo"`
	Estado      string        `json:"estado"`
	Foto        *Foto         `json:"foto"`
	Fotos       []Foto        `json:"fotos,omitempty"`
	Vendedor    VendedorCorto `json:"vendedor"`
	DistanciaKm *float64      `json:"distancia_km"`
	EsMia       bool          `json:"es_mia"`
	Creada      time.Time     `json:"creada"`
}

func urlFoto(archivo string) string { return "/api/mercado/fotos/" + archivo }

var unidades = map[string]bool{"pieza": true, "kilo": true, "litro": true, "caja": true, "paquete": true,
	"bulto": true, "docena": true, "metro": true, "servicio": true}

// columnas de una tarjeta; $1 y $2 son lat/lng de quien mira (pueden ser NULL).
const columnasTarjeta = `p.id, p.titulo, p.categoria, p.precio_c, p.unidad, p.existencias, p.minimo, p.estado, p.creada,
	v.nombre, v.slug, v.colonia, p.vendedor_id,
	CASE WHEN v.lat IS NULL OR $1::float8 IS NULL THEN NULL ELSE
		6371 * 2 * asin(sqrt(power(sin(radians(v.lat - $1::float8) / 2), 2)
			+ cos(radians($1::float8)) * cos(radians(v.lat)) * power(sin(radians(v.lng - $2::float8) / 2), 2))) END,
	f.archivo, f.alt, f.ancho, f.alto`

const desdeTarjeta = ` FROM publicaciones p JOIN perfiles v ON v.cuenta_id = p.vendedor_id
	LEFT JOIN LATERAL (SELECT archivo, alt, ancho, alto FROM fotos WHERE publicacion_id = p.id ORDER BY orden, id LIMIT 1) f ON true`

func escanearTarjeta(f pgx.Row, yo int64) (Publicacion, error) {
	var p Publicacion
	var vendedorID int64
	var dist *float64
	var archivo, alt *string
	var ancho, alto *int
	err := f.Scan(&p.ID, &p.Titulo, &p.Categoria, &p.PrecioC, &p.Unidad, &p.Existencias, &p.Minimo, &p.Estado, &p.Creada,
		&p.Vendedor.Nombre, &p.Vendedor.Slug, &p.Vendedor.Colonia, &vendedorID, &dist, &archivo, &alt, &ancho, &alto)
	if err != nil {
		return p, err
	}
	if dist != nil {
		d := math.Round(*dist*10) / 10
		p.DistanciaKm = &d
	}
	if archivo != nil {
		p.Foto = &Foto{URL: urlFoto(*archivo), Alt: *alt, Ancho: *ancho, Alto: *alto}
	}
	p.EsMia = vendedorID == yo
	return p, nil
}

func (s *Servicio) categorias(w http.ResponseWriter, r *http.Request) {
	filas, err := s.bd.Query(r.Context(), "SELECT clave, nombre, icono FROM categorias ORDER BY orden")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	type cat struct {
		Clave  string `json:"clave"`
		Nombre string `json:"nombre"`
		Icono  string `json:"icono"`
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (cat, error) {
		var c cat
		return c, f.Scan(&c.Clave, &c.Nombre, &c.Icono)
	})
	plataforma.Manejar(w, r, lista, err)
}

// escaparLike evita que %, _ y \ del usuario funcionen como comodines.
func escaparLike(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

// listarPublicaciones: búsqueda, filtro por categoría o vendedor, y paginación
// por cursor (?antes=<id>): estable y rápida aunque haya miles de productos.
func (s *Servicio) listarPublicaciones(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	q := r.URL.Query()
	lat, lng, err := s.asegurarPerfil(ctx, id)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	limite := 24
	if n, err := strconv.Atoi(q.Get("limite")); err == nil && n > 0 && n <= 48 {
		limite = n
	}
	args := []any{lat, lng}
	where := []string{"p.estado = 'activa'"}
	add := func(cond string, v any) {
		args = append(args, v)
		where = append(where, strings.ReplaceAll(cond, "?", "$"+strconv.Itoa(len(args))))
	}
	if b := strings.TrimSpace(q.Get("q")); b != "" {
		if utf8.RuneCountInString(b) > 80 {
			plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "La búsqueda es demasiado larga"))
			return
		}
		args = append(args, b, "%"+escaparLike(b)+"%")
		n := len(args)
		where = append(where, "(p.busqueda @@ websearch_to_tsquery('spanish', $"+strconv.Itoa(n-1)+
			") OR p.titulo ILIKE $"+strconv.Itoa(n)+" OR v.nombre ILIKE $"+strconv.Itoa(n)+")")
	}
	if c := q.Get("categoria"); c != "" {
		add("p.categoria = ?", c)
	}
	if v := q.Get("vendedor"); v != "" {
		add("v.slug = ?", v)
	}
	if a, err := strconv.ParseInt(q.Get("antes"), 10, 64); err == nil && a > 0 {
		add("p.id < ?", a)
	}
	args = append(args, limite+1)
	sql := "SELECT " + columnasTarjeta + desdeTarjeta + " WHERE " + strings.Join(where, " AND ") +
		" ORDER BY p.id DESC LIMIT $" + strconv.Itoa(len(args))
	filas, err := s.bd.Query(ctx, sql, args...)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (Publicacion, error) { return escanearTarjeta(f, id.Cuenta) })
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var siguiente *int64
	if len(lista) > limite {
		lista = lista[:limite]
		siguiente = &lista[limite-1].ID
	}
	plataforma.Manejar(w, r, map[string]any{"items": lista, "siguiente": siguiente}, nil)
}

func (s *Servicio) fotosDe(ctx context.Context, pub int64) ([]Foto, error) {
	filas, err := s.bd.Query(ctx, "SELECT id, archivo, alt, ancho, alto FROM fotos WHERE publicacion_id = $1 ORDER BY orden, id", pub)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(filas, func(f pgx.CollectableRow) (Foto, error) {
		var x Foto
		var archivo string
		err := f.Scan(&x.ID, &archivo, &x.Alt, &x.Ancho, &x.Alto)
		x.URL = urlFoto(archivo)
		return x, err
	})
}

type DetalleVendedor struct {
	Nombre      string  `json:"nombre"`
	Slug        string  `json:"slug"`
	Colonia     string  `json:"colonia"`
	Giro        string  `json:"giro"`
	Descripcion string  `json:"descripcion"`
	TiendaSlug  *string `json:"tienda_slug"`
}

func (s *Servicio) verPublicacion(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	pid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lat, lng, err := s.asegurarPerfil(ctx, id)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	// Una publicación pausada solo la ve su dueño.
	p, err := escanearTarjeta(s.bd.QueryRow(ctx, "SELECT "+columnasTarjeta+desdeTarjeta+
		" WHERE p.id = $3 AND (p.estado = 'activa' OR p.vendedor_id = $4)", lat, lng, pid, id.Cuenta), id.Cuenta)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var v DetalleVendedor
	if err := s.bd.QueryRow(ctx, `SELECT p.descripcion, v.nombre, v.slug, v.colonia, v.giro, v.descripcion, v.tienda_slug
		FROM publicaciones p JOIN perfiles v ON v.cuenta_id = p.vendedor_id WHERE p.id = $1`, pid).
		Scan(&p.Descripcion, &v.Nombre, &v.Slug, &v.Colonia, &v.Giro, &v.Descripcion, &v.TiendaSlug); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if p.Fotos, err = s.fotosDe(ctx, pid); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	plataforma.Manejar(w, r, map[string]any{"publicacion": p, "vendedor": v}, nil)
}

func (s *Servicio) verVendedor(w http.ResponseWriter, r *http.Request) {
	var v DetalleVendedor
	var total int
	err := s.bd.QueryRow(r.Context(), `SELECT v.nombre, v.slug, v.colonia, v.giro, v.descripcion, v.tienda_slug,
			(SELECT COUNT(*) FROM publicaciones p WHERE p.vendedor_id = v.cuenta_id AND p.estado = 'activa')
		FROM perfiles v WHERE v.slug = $1`, r.PathValue("slug")).
		Scan(&v.Nombre, &v.Slug, &v.Colonia, &v.Giro, &v.Descripcion, &v.TiendaSlug, &total)
	plataforma.Manejar(w, r, map[string]any{"vendedor": v, "publicaciones": total}, err)
}

func (s *Servicio) misPublicaciones(w http.ResponseWriter, r *http.Request) {
	id := plataforma.IdentidadDe(r)
	filas, err := s.bd.Query(r.Context(), "SELECT "+columnasTarjeta+desdeTarjeta+
		" WHERE p.vendedor_id = $3 ORDER BY p.id DESC LIMIT 200", nil, nil, id.Cuenta)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (Publicacion, error) { return escanearTarjeta(f, id.Cuenta) })
	plataforma.Manejar(w, r, lista, err)
}

// ---- crear / editar ----

type datosPublicacion struct {
	Titulo      string `json:"titulo"`
	Descripcion string `json:"descripcion"`
	Categoria   string `json:"categoria"`
	PrecioC     int64  `json:"precio_c"`
	Unidad      string `json:"unidad"`
	Existencias *int   `json:"existencias"`
	Minimo      int    `json:"minimo"`
}

func limpiar(s string) string {
	s = strings.ToValidUTF8(s, "")
	return strings.TrimSpace(strings.Map(func(r rune) rune {
		if (r < 0x20 && r != '\n') || r == 0x7f || (r >= 0x200b && r <= 0x200f) || (r >= 0x202a && r <= 0x202e) {
			return -1
		}
		return r
	}, s))
}

func (d *datosPublicacion) validar() error {
	d.Titulo = limpiar(strings.ReplaceAll(d.Titulo, "\n", " "))
	d.Descripcion = limpiar(d.Descripcion)
	if d.Minimo == 0 {
		d.Minimo = 1
	}
	switch n := utf8.RuneCountInString(d.Titulo); {
	case n < 3:
		return plataforma.Fallo(400, "Escribe un título de al menos 3 letras")
	case n > 80:
		return plataforma.Fallo(400, "El título puede tener hasta 80 letras")
	case utf8.RuneCountInString(d.Descripcion) > 1000:
		return plataforma.Fallo(400, "La descripción puede tener hasta 1,000 letras")
	case d.PrecioC < 100 || d.PrecioC > 100_000_000:
		return plataforma.Fallo(400, "El precio debe estar entre $1 y $1,000,000")
	case !unidades[d.Unidad]:
		return plataforma.Fallo(400, "Elige una unidad de venta")
	case d.Existencias != nil && (*d.Existencias < 0 || *d.Existencias > 1_000_000):
		return plataforma.Fallo(400, "Las existencias deben estar entre 0 y 1,000,000")
	case d.Minimo < 1 || d.Minimo > 100_000:
		return plataforma.Fallo(400, "La compra mínima debe estar entre 1 y 100,000")
	}
	return nil
}

func (s *Servicio) categoriaExiste(ctx context.Context, c string) bool {
	var ok bool
	_ = s.bd.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM categorias WHERE clave = $1)", c).Scan(&ok)
	return ok
}

func (s *Servicio) crearPublicacion(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	var d datosPublicacion
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if err := d.validar(); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if !s.categoriaExiste(ctx, d.Categoria) {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Elige una categoría"))
		return
	}
	if _, _, err := s.asegurarPerfil(ctx, id); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var n int
	_ = s.bd.QueryRow(ctx, "SELECT COUNT(*) FROM publicaciones WHERE vendedor_id = $1", id.Cuenta).Scan(&n)
	if n >= 500 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(409, "Llegaste al límite de 500 publicaciones"))
		return
	}
	var nuevo int64
	err := s.bd.QueryRow(ctx, `INSERT INTO publicaciones (vendedor_id, titulo, descripcion, categoria, precio_c, unidad, existencias, minimo)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING id`,
		id.Cuenta, d.Titulo, d.Descripcion, d.Categoria, d.PrecioC, d.Unidad, d.Existencias, d.Minimo).Scan(&nuevo)
	plataforma.Manejar(w, r, map[string]int64{"id": nuevo}, err)
}

func (s *Servicio) editarPublicacion(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	pid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var d datosPublicacion
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if err := d.validar(); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if !s.categoriaExiste(ctx, d.Categoria) {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Elige una categoría"))
		return
	}
	// "AND vendedor_id = $9": solo el dueño puede editar (si no, "no encontrada").
	tag, err := s.bd.Exec(ctx, `UPDATE publicaciones SET titulo = $1, descripcion = $2, categoria = $3, precio_c = $4, unidad = $5,
		existencias = $6, minimo = $7, actualizada = now() WHERE id = $8 AND vendedor_id = $9`,
		d.Titulo, d.Descripcion, d.Categoria, d.PrecioC, d.Unidad, d.Existencias, d.Minimo, pid, id.Cuenta)
	if err == nil && tag.RowsAffected() == 0 {
		err = pgx.ErrNoRows
	}
	plataforma.Manejar(w, r, nil, err)
}

func (s *Servicio) estadoPublicacion(w http.ResponseWriter, r *http.Request) {
	id := plataforma.IdentidadDe(r)
	pid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var d struct {
		Estado string `json:"estado"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if d.Estado != "activa" && d.Estado != "pausada" {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Estado inválido"))
		return
	}
	tag, err := s.bd.Exec(r.Context(), "UPDATE publicaciones SET estado = $1, actualizada = now() WHERE id = $2 AND vendedor_id = $3",
		d.Estado, pid, id.Cuenta)
	if err == nil && tag.RowsAffected() == 0 {
		err = pgx.ErrNoRows
	}
	plataforma.Manejar(w, r, nil, err)
}

func (s *Servicio) borrarPublicacion(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	pid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	fotos, err := s.fotosDe(ctx, pid)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	tag, err := s.bd.Exec(ctx, "DELETE FROM publicaciones WHERE id = $1 AND vendedor_id = $2", pid, id.Cuenta)
	if err == nil && tag.RowsAffected() == 0 {
		err = pgx.ErrNoRows
	}
	if err == nil {
		for _, f := range fotos {
			_ = os.Remove(filepath.Join(s.fotos, filepath.Base(f.URL)))
		}
	}
	plataforma.Manejar(w, r, nil, err)
}

// ---- fotos ----

const maxFotos = 6

func (s *Servicio) subirFoto(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	pid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var titulo string
	var n int
	err = s.bd.QueryRow(ctx, `SELECT titulo, (SELECT COUNT(*) FROM fotos WHERE publicacion_id = p.id)
		FROM publicaciones p WHERE id = $1 AND vendedor_id = $2`, pid, id.Cuenta).Scan(&titulo, &n)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if n >= maxFotos {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(409, "Cada publicación puede tener hasta 6 fotos"))
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 9<<20)
	if err := r.ParseMultipartForm(1 << 20); err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "La foto pesa demasiado (máximo 8 MB)"))
		return
	}
	defer r.MultipartForm.RemoveAll()
	alt := limpiar(strings.ReplaceAll(r.FormValue("alt"), "\n", " "))
	if utf8.RuneCountInString(alt) < 3 {
		alt = titulo
	}
	if utf8.RuneCountInString(alt) > 140 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "La descripción de la foto puede tener hasta 140 letras"))
		return
	}
	archivo, _, err := r.FormFile("foto")
	if err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Elige una foto"))
		return
	}
	defer archivo.Close()
	res, err := imagen.Procesar(archivo, 8<<20)
	if err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, err.Error()))
		return
	}
	nombre := plataforma.Aleatorio(16) + ".jpg"
	if err := escribirAtomico(filepath.Join(s.fotos, nombre), res.JPEG); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var f Foto
	err = s.bd.QueryRow(ctx, `INSERT INTO fotos (publicacion_id, archivo, alt, ancho, alto, orden) VALUES ($1, $2, $3, $4, $5, $6)
		RETURNING id`, pid, nombre, alt, res.Ancho, res.Alto, n).Scan(&f.ID)
	if err != nil {
		_ = os.Remove(filepath.Join(s.fotos, nombre))
		plataforma.Manejar(w, r, nil, err)
		return
	}
	f.URL, f.Alt, f.Ancho, f.Alto = urlFoto(nombre), alt, res.Ancho, res.Alto
	plataforma.Manejar(w, r, f, nil)
}

func escribirAtomico(ruta string, datos []byte) error {
	tmp := ruta + ".tmp"
	if err := os.WriteFile(tmp, datos, 0o640); err != nil {
		return err
	}
	return os.Rename(tmp, ruta)
}

func (s *Servicio) borrarFoto(w http.ResponseWriter, r *http.Request) {
	id := plataforma.IdentidadDe(r)
	pid, err1 := plataforma.IDRuta(r, "id")
	fid, err2 := plataforma.IDRuta(r, "foto")
	if err := errors.Join(err1, err2); err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Identificador inválido"))
		return
	}
	var archivo string
	err := s.bd.QueryRow(r.Context(), `DELETE FROM fotos f USING publicaciones p
		WHERE f.id = $1 AND f.publicacion_id = $2 AND p.id = f.publicacion_id AND p.vendedor_id = $3 RETURNING f.archivo`,
		fid, pid, id.Cuenta).Scan(&archivo)
	if err == nil {
		_ = os.Remove(filepath.Join(s.fotos, archivo))
	}
	plataforma.Manejar(w, r, nil, err)
}

var archivoValido = regexp.MustCompile(`^[a-f0-9]{32}\.jpg$`)

// servirFoto: el nombre se valida con una expresión estricta (no hay forma de
// salir de la carpeta) y solo se sirve si está registrado en la base.
func (s *Servicio) servirFoto(w http.ResponseWriter, r *http.Request) {
	nombre := r.PathValue("archivo")
	if !archivoValido.MatchString(nombre) {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Foto no encontrada"))
		return
	}
	var existe bool
	if err := s.bd.QueryRow(r.Context(), "SELECT EXISTS(SELECT 1 FROM fotos WHERE archivo = $1)", nombre).Scan(&existe); err != nil || !existe {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Foto no encontrada"))
		return
	}
	f, err := os.Open(filepath.Join(s.fotos, nombre))
	if err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Foto no encontrada"))
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=86400, immutable")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; sandbox")
	http.ServeContent(w, r, "", st.ModTime(), f)
}
