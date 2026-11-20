// Package mercado es el servicio del mercado local: publicaciones de los
// negocios de Durango, fotos, búsqueda, perfiles de vendedor y órdenes.
package mercado

import (
	"context"
	"embed"
	"errors"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migraciones/*.sql
var migracionesFS embed.FS

func Migraciones() fs.FS { sub, _ := fs.Sub(migracionesFS, "migraciones"); return sub }

type Servicio struct {
	bd     *pgxpool.Pool
	firma  *plataforma.Firmante
	acceso *plataforma.ClienteInterno
	fotos  *os.Root // carpeta de fotos: ninguna operación puede salir de ella
	demo   bool
}

func Nuevo(bd *pgxpool.Pool, firma *plataforma.Firmante, urlAcceso, carpeta string, demo bool) (*Servicio, error) {
	dir := filepath.Join(carpeta, "fotos")
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return nil, err
	}
	fotos, err := os.OpenRoot(dir)
	if err != nil {
		return nil, err
	}
	return &Servicio{bd: bd, firma: firma, acceso: plataforma.NuevoClienteInterno(urlAcceso, "mercado", firma), fotos: fotos, demo: demo}, nil
}

func (s *Servicio) Rutas() http.Handler {
	m := http.NewServeMux()
	cliente := func(h http.HandlerFunc) http.HandlerFunc { return s.firma.Exigir(plataforma.ClienteVerificado, h) }
	vendedor := func(h http.HandlerFunc) http.HandlerFunc { return s.firma.Exigir(plataforma.Vendedor, h) }

	m.HandleFunc("GET /categorias", cliente(s.categorias))
	m.HandleFunc("GET /publicaciones", cliente(s.listarPublicaciones))
	m.HandleFunc("GET /publicaciones/{id}", cliente(s.verPublicacion))
	m.HandleFunc("GET /vendedores/{slug}", cliente(s.verVendedor))
	m.HandleFunc("GET /fotos/{archivo}", cliente(s.servirFoto))

	m.HandleFunc("GET /mias", vendedor(s.misPublicaciones))
	m.HandleFunc("POST /publicaciones", vendedor(s.crearPublicacion))
	m.HandleFunc("PUT /publicaciones/{id}", vendedor(s.editarPublicacion))
	m.HandleFunc("POST /publicaciones/{id}/estado", vendedor(s.estadoPublicacion))
	m.HandleFunc("DELETE /publicaciones/{id}", vendedor(s.borrarPublicacion))
	m.HandleFunc("POST /publicaciones/{id}/fotos", vendedor(s.subirFoto))
	m.HandleFunc("DELETE /publicaciones/{id}/fotos/{foto}", vendedor(s.borrarFoto))

	m.HandleFunc("POST /ordenes", cliente(s.crearOrden))
	m.HandleFunc("GET /ordenes", cliente(s.listarOrdenes))
	m.HandleFunc("GET /ordenes/{id}", cliente(s.verOrden))
	m.HandleFunc("POST /ordenes/{id}/{accion}", cliente(s.accionOrden))

	m.HandleFunc("GET /interno/ordenes/{id}", s.firma.Exigir(plataforma.Servicio("pagos"), s.internoOrden))
	m.HandleFunc("POST /interno/ordenes/{id}/cobro", s.firma.Exigir(plataforma.Servicio("pagos"), s.internoCobro))
	m.HandleFunc("GET /interno/publicaciones/{id}", s.firma.Exigir(plataforma.Servicio("reels"), s.internoPublicacion))
	m.HandleFunc("GET /interno/vendedores/{id}/publicaciones", s.firma.Exigir(plataforma.Servicio("reels"), s.internoPublicacionesDe))

	m.HandleFunc("GET /salud", func(w http.ResponseWriter, r *http.Request) {
		plataforma.Manejar(w, r, nil, s.bd.Ping(r.Context()))
	})
	return m
}

// enTx corre f en una transacción con la identidad puesta para las reglas de
// seguridad por fila (RLS) de órdenes.
func (s *Servicio) enTx(ctx context.Context, cuenta int64, servicio string, f func(pgx.Tx) error) error {
	return pgx.BeginFunc(ctx, s.bd, func(tx pgx.Tx) error {
		c := ""
		if cuenta > 0 {
			c = itoa(cuenta)
		}
		if _, err := tx.Exec(ctx, "SELECT set_config('app.cuenta', $1, true), set_config('app.servicio', $2, true)", c, servicio); err != nil {
			return err
		}
		return f(tx)
	})
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

// ---- perfiles ----

type perfilAcceso struct {
	ID          int64    `json:"id"`
	Nombre      string   `json:"nombre"`
	Slug        string   `json:"slug"`
	Giro        string   `json:"giro"`
	Descripcion string   `json:"descripcion"`
	Colonia     string   `json:"colonia"`
	Lat         *float64 `json:"lat"`
	Lng         *float64 `json:"lng"`
	Vende       bool     `json:"vende"`
	Estado      string   `json:"estado"`
	TiendaSlug  *string  `json:"tienda_slug"`
	Email       string   `json:"email"`
}

func (s *Servicio) guardarPerfil(ctx context.Context, p perfilAcceso) error {
	_, err := s.bd.Exec(ctx, `INSERT INTO perfiles (cuenta_id, nombre, slug, giro, descripcion, colonia, lat, lng, tienda_slug, vende, actualizado)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, now())
		ON CONFLICT (cuenta_id) DO UPDATE SET nombre = $2, slug = $3, giro = $4, descripcion = $5, colonia = $6,
			lat = $7, lng = $8, tienda_slug = $9, vende = $10, actualizado = now()`,
		p.ID, p.Nombre, p.Slug, p.Giro, p.Descripcion, p.Colonia, p.Lat, p.Lng, p.TiendaSlug, p.Vende)
	return err
}

// asegurarPerfil trae el perfil desde "acceso" si no lo tenemos o tiene más de
// una hora (o si el nombre cambió). Regresa las coordenadas del negocio.
func (s *Servicio) asegurarPerfil(ctx context.Context, id plataforma.Identidad) (lat, lng *float64, err error) {
	var nombre string
	var act time.Time
	err = s.bd.QueryRow(ctx, "SELECT nombre, actualizado, lat, lng FROM perfiles WHERE cuenta_id = $1", id.Cuenta).Scan(&nombre, &act, &lat, &lng)
	if err == nil && nombre == id.Nombre && time.Since(act) < time.Hour {
		return lat, lng, nil
	}
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, nil, err
	}
	var p perfilAcceso
	if e := s.acceso.Llamar(ctx, http.MethodGet, "/interno/cuentas/"+itoa(id.Cuenta), nil, &p); e != nil {
		if err == nil {
			return lat, lng, nil // usamos la copia vieja si acceso no responde
		}
		return nil, nil, e
	}
	if err := s.guardarPerfil(ctx, p); err != nil {
		return nil, nil, err
	}
	return p.Lat, p.Lng, nil
}
