// Package reels guarda y sirve videos cortos (hasta 60 s) de los vendedores,
// ligados a una de sus publicaciones del mercado.
package reels

import (
	"bytes"
	"context"
	"embed"
	"errors"
	"io"
	"io/fs"
	"log/slog"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migraciones/*.sql
var migracionesFS embed.FS

func Migraciones() fs.FS { sub, _ := fs.Sub(migracionesFS, "migraciones"); return sub }

const maxBytes = 50 << 20

type Servicio struct {
	bd      *pgxpool.Pool
	firma   *plataforma.Firmante
	mercado *plataforma.ClienteInterno
	videos  *os.Root // carpeta de videos: ninguna operación puede salir de ella
}

func Nuevo(bd *pgxpool.Pool, firma *plataforma.Firmante, urlMercado, carpeta string) (*Servicio, error) {
	dir := filepath.Join(carpeta, "videos")
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return nil, err
	}
	v, err := os.OpenRoot(dir)
	if err != nil {
		return nil, err
	}
	return &Servicio{bd: bd, firma: firma, mercado: plataforma.NuevoClienteInterno(urlMercado, "reels", firma), videos: v}, nil
}

func (s *Servicio) Rutas() http.Handler {
	m := http.NewServeMux()
	cliente := func(h http.HandlerFunc) http.HandlerFunc { return s.firma.Exigir(plataforma.ClienteVerificado, h) }
	vendedor := func(h http.HandlerFunc) http.HandlerFunc { return s.firma.Exigir(plataforma.Vendedor, h) }
	m.HandleFunc("GET /{$}", cliente(s.feed))
	m.HandleFunc("GET /media/{archivo}", cliente(s.servir))
	m.HandleFunc("GET /mios", vendedor(s.mios))
	m.HandleFunc("POST /subir", vendedor(s.subir))
	m.HandleFunc("DELETE /{id}", vendedor(s.borrar))
	m.HandleFunc("GET /salud", func(w http.ResponseWriter, r *http.Request) {
		plataforma.Manejar(w, r, nil, s.bd.Ping(r.Context()))
	})
	return m
}

type PubCorta struct {
	ID      int64  `json:"id"`
	Titulo  string `json:"titulo"`
	PrecioC int64  `json:"precio_c"`
	Unidad  string `json:"unidad"`
}

type Reel struct {
	ID          int64  `json:"id"`
	URL         string `json:"url"`
	Descripcion string `json:"descripcion"`
	DuracionMs  int    `json:"duracion_ms"`
	Ancho       int    `json:"ancho"`
	Alto        int    `json:"alto"`
	Vendedor    struct {
		Nombre string `json:"nombre"`
		Slug   string `json:"slug"`
	} `json:"vendedor"`
	Publicacion *PubCorta `json:"publicacion"`
	EsMio       bool      `json:"es_mio"`
	Creado      time.Time `json:"creado"`
}

const columnas = `id, cuenta_id, vendedor_nombre, vendedor_slug, publicacion_id, publicacion_titulo, publicacion_precio_c,
	publicacion_unidad, archivo, duracion_ms, ancho, alto, descripcion, creado`

func escanear(f pgx.Row, yo int64) (Reel, error) {
	var x Reel
	var cuenta int64
	var pid, precio *int64
	var titulo, unidad *string
	var archivo string
	err := f.Scan(&x.ID, &cuenta, &x.Vendedor.Nombre, &x.Vendedor.Slug, &pid, &titulo, &precio, &unidad, &archivo,
		&x.DuracionMs, &x.Ancho, &x.Alto, &x.Descripcion, &x.Creado)
	if pid != nil && titulo != nil && precio != nil {
		x.Publicacion = &PubCorta{ID: *pid, Titulo: *titulo, PrecioC: *precio}
		if unidad != nil {
			x.Publicacion.Unidad = *unidad
		}
	}
	x.URL = "/api/reels/media/" + archivo
	x.EsMio = cuenta == yo
	return x, err
}

func (s *Servicio) feed(w http.ResponseWriter, r *http.Request) {
	id := plataforma.IdentidadDe(r)
	q := r.URL.Query()
	limite := 6
	if n, err := strconv.Atoi(q.Get("limite")); err == nil && n > 0 && n <= 20 {
		limite = n
	}
	args := []any{limite + 1}
	where := "estado = 'activo'"
	if a, err := strconv.ParseInt(q.Get("antes"), 10, 64); err == nil && a > 0 {
		args = append(args, a)
		where += " AND id < $2"
	}
	if v := q.Get("vendedor"); v != "" {
		args = append(args, v)
		where += " AND vendedor_slug = $" + strconv.Itoa(len(args))
	}
	filas, err := s.bd.Query(r.Context(), "SELECT "+columnas+" FROM reels WHERE "+where+" ORDER BY id DESC LIMIT $1", args...)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (Reel, error) { return escanear(f, id.Cuenta) })
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

func (s *Servicio) mios(w http.ResponseWriter, r *http.Request) {
	id := plataforma.IdentidadDe(r)
	filas, err := s.bd.Query(r.Context(), "SELECT "+columnas+" FROM reels WHERE cuenta_id = $1 ORDER BY id DESC LIMIT 100", id.Cuenta)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (Reel, error) { return escanear(f, id.Cuenta) })
	plataforma.Manejar(w, r, lista, err)
}

// subir lee el multipart en streaming (el video nunca se carga completo en
// memoria), lo escribe a un temporal, lo valida y solo entonces lo publica.
func (s *Servicio) subir(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes+(1<<20))
	mr, err := r.MultipartReader()
	if err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Envía el video como formulario"))
		return
	}
	var desc, pubTxt, tmp string
	var tam int64
	defer func() {
		if tmp != "" {
			_ = s.videos.Remove(tmp)
		}
	}()
	for {
		p, err := mr.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "El video pesa demasiado (máximo 50 MB)"))
			return
		}
		switch p.FormName() {
		case "descripcion":
			desc = campo(p, 2000)
		case "publicacion_id":
			pubTxt = campo(p, 30)
		case "video":
			if tmp != "" {
				plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Sube un solo video"))
				return
			}
			tmp, tam, err = s.guardarTemporal(p)
			if err != nil {
				plataforma.Manejar(w, r, nil, err)
				return
			}
		}
		_ = p.Close()
	}
	desc = limpiar(desc)
	if n := utf8.RuneCountInString(desc); n < 10 || n > 300 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Describe el video en 10 a 300 letras (también lo escuchan quienes usan lector de pantalla)"))
		return
	}
	if tmp == "" {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Elige un video"))
		return
	}
	f, err := s.videos.Open(tmp)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	info, err := analizarMP4(f, tam)
	_ = f.Close()
	if err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, err.Error()))
		return
	}
	var pub *PubCorta
	if pubTxt != "" {
		pid, err := strconv.ParseInt(pubTxt, 10, 64)
		if err != nil || pid <= 0 {
			plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Producto inválido"))
			return
		}
		var datos struct {
			ID         int64  `json:"id"`
			VendedorID int64  `json:"vendedor_id"`
			Titulo     string `json:"titulo"`
			PrecioC    int64  `json:"precio_c"`
			Unidad     string `json:"unidad"`
		}
		if err := s.mercado.Llamar(ctx, http.MethodGet, "/interno/publicaciones/"+pubTxt, nil, &datos); err != nil || datos.VendedorID != id.Cuenta {
			plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Solo puedes ligar el video a uno de tus productos"))
			return
		}
		pub = &PubCorta{ID: datos.ID, Titulo: datos.Titulo, PrecioC: datos.PrecioC, Unidad: datos.Unidad}
	}
	var n int
	_ = s.bd.QueryRow(ctx, "SELECT COUNT(*) FROM reels WHERE cuenta_id = $1 AND creado > now() - interval '1 day'", id.Cuenta).Scan(&n)
	if n >= 20 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(429, "Puedes subir hasta 20 videos al día"))
		return
	}
	nombre := plataforma.Aleatorio(16) + ".mp4"
	final := nombre
	if err := s.videos.Rename(tmp, final); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	tmp = ""
	var pid, precio *int64
	var titulo, unidad *string
	if pub != nil {
		pid, titulo, precio, unidad = &pub.ID, &pub.Titulo, &pub.PrecioC, &pub.Unidad
	}
	var nuevo int64
	err = s.bd.QueryRow(ctx, `INSERT INTO reels (cuenta_id, vendedor_nombre, vendedor_slug, publicacion_id, publicacion_titulo,
			publicacion_precio_c, publicacion_unidad, archivo, bytes, duracion_ms, ancho, alto, descripcion)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13) RETURNING id`,
		id.Cuenta, id.Nombre, id.Slug, pid, titulo, precio, unidad, nombre, tam, info.DuracionMs, info.Ancho, info.Alto, desc).Scan(&nuevo)
	if err != nil {
		_ = s.videos.Remove(final)
	}
	plataforma.Manejar(w, r, map[string]int64{"id": nuevo}, err)
}

func campo(p *multipart.Part, max int64) string {
	b, _ := io.ReadAll(io.LimitReader(p, max))
	return string(b)
}

func (s *Servicio) guardarTemporal(p *multipart.Part) (string, int64, error) {
	nombre := "subida-" + plataforma.Aleatorio(12) + ".tmp"
	f, err := s.videos.OpenFile(nombre, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return "", 0, err
	}
	n, err := io.Copy(f, io.LimitReader(p, maxBytes+1))
	cerr := f.Close()
	if err != nil || cerr != nil || n > maxBytes {
		_ = s.videos.Remove(nombre)
		var mb *http.MaxBytesError
		if n > maxBytes || errors.As(err, &mb) {
			return "", 0, plataforma.Fallo(413, "El video pesa demasiado (máximo 50 MB)")
		}
		if err == nil {
			err = cerr
		}
		return "", 0, err
	}
	return nombre, n, nil
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

func (s *Servicio) borrar(w http.ResponseWriter, r *http.Request) {
	rid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var archivo string
	err = s.bd.QueryRow(r.Context(), "DELETE FROM reels WHERE id = $1 AND cuenta_id = $2 RETURNING archivo", rid, plataforma.IdentidadDe(r).Cuenta).Scan(&archivo)
	if err == nil {
		_ = s.videos.Remove(archivo)
	}
	plataforma.Manejar(w, r, nil, err)
}

var archivoValido = regexp.MustCompile(`^[a-f0-9]{32}\.mp4$`)

// servir admite Range (adelantar el video) y fija el tipo: el navegador nunca
// lo interpreta como otra cosa.
func (s *Servicio) servir(w http.ResponseWriter, r *http.Request) {
	nombre := r.PathValue("archivo")
	if !archivoValido.MatchString(nombre) {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Video no encontrado"))
		return
	}
	var ok bool
	if err := s.bd.QueryRow(r.Context(), "SELECT EXISTS(SELECT 1 FROM reels WHERE archivo = $1 AND estado = 'activo')", nombre).Scan(&ok); err != nil || !ok {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Video no encontrado"))
		return
	}
	f, err := s.videos.Open(nombre)
	if err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Video no encontrado"))
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; sandbox")
	http.ServeContent(w, r, "", st.ModTime(), f)
}

//go:embed demo/*.mp4
var demoFS embed.FS

type demoReel struct{ archivo, email, titulo, descripcion string }

var demos = []demoReel{
	{"gorras.mp4", "mayoreo@anaquel.mx", "Gorra bordada con tu logo", "Gorras bordadas con el logo de tu negocio o evento, desde 50 piezas. Entregamos en Durango."},
	{"queso.mp4", "demo@anaquel.mx", "Queso menonita", "Queso menonita fresco de Nuevo Ideal, por kilo, en Abarrotes Doña Lupita."},
	{"chamarras.mp4", "textiles@anaquel.mx", "Chamarra acolchada de invierno", "Chamarras acolchadas para el frío, precio de mayoreo desde 6 piezas."},
}

// SembrarDemo publica los videos de ejemplo si todavía no hay reels. Pide las
// cuentas a "acceso" y los productos a "mercado" (esperando a que arranquen).
func (s *Servicio) SembrarDemo(ctx context.Context, acceso *plataforma.ClienteInterno) {
	var hay bool
	_ = s.bd.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM reels)").Scan(&hay)
	if hay {
		return
	}
	var cuentas []struct {
		ID     int64  `json:"id"`
		Email  string `json:"email"`
		Nombre string `json:"nombre"`
		Slug   string `json:"slug"`
	}
	type pub struct {
		ID      int64  `json:"id"`
		Titulo  string `json:"titulo"`
		PrecioC int64  `json:"precio_c"`
		Unidad  string `json:"unidad"`
	}
	pubs := map[int64][]pub{}
	for intento := 0; ; intento++ {
		err := acceso.Llamar(ctx, http.MethodGet, "/interno/demo", nil, &cuentas)
		listo := err == nil && len(cuentas) > 0
		if listo {
			for _, c := range cuentas {
				var lista []pub
				if err := s.mercado.Llamar(ctx, http.MethodGet, "/interno/vendedores/"+strconv.FormatInt(c.ID, 10)+"/publicaciones", nil, &lista); err != nil || len(lista) == 0 {
					if c.Email == "mayoreo@anaquel.mx" {
						listo = false // el catálogo de mercado aún no está sembrado
					}
					continue
				}
				pubs[c.ID] = lista
			}
		}
		if listo {
			break
		}
		if intento > 60 {
			slog.Error("demo reels: acceso o mercado no respondieron", "err", err)
			return
		}
		time.Sleep(2 * time.Second)
	}
	for _, d := range demos {
		datos, err := demoFS.ReadFile("demo/" + d.archivo)
		if err != nil {
			continue
		}
		info, err := analizarMP4(bytes.NewReader(datos), int64(len(datos)))
		if err != nil {
			slog.Error("demo reels: video inválido", "archivo", d.archivo, "err", err)
			continue
		}
		for _, c := range cuentas {
			if c.Email != d.email {
				continue
			}
			var pid, precio *int64
			var titulo, unidad *string
			for _, p := range pubs[c.ID] {
				if p.Titulo == d.titulo {
					p := p
					pid, titulo, precio, unidad = &p.ID, &p.Titulo, &p.PrecioC, &p.Unidad
				}
			}
			nombre := plataforma.Aleatorio(16) + ".mp4"
			if err := s.videos.WriteFile(nombre, datos, 0o600); err != nil {
				continue
			}
			_, _ = s.bd.Exec(ctx, `INSERT INTO reels (cuenta_id, vendedor_nombre, vendedor_slug, publicacion_id, publicacion_titulo,
					publicacion_precio_c, publicacion_unidad, archivo, bytes, duracion_ms, ancho, alto, descripcion)
				VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)`,
				c.ID, c.Nombre, c.Slug, pid, titulo, precio, unidad, nombre, len(datos), info.DuracionMs, info.Ancho, info.Alto, d.descripcion)
		}
	}
	slog.Info("demo: reels de ejemplo publicados")
}
