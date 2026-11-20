package mercado

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// Reglas de los espacios publicitarios (las mismas para web y app):
//   - Banner: 1 como máximo, solo de plan Premium, arriba del catálogo.
//   - Productos patrocinados: hasta 3; la web y la app los ponen en las
//     posiciones 1, 9 y 17 de la cuadrícula (nunca dos juntos).
//   - Orden: prioridad del plan (Premium > Destacado > Básico) y, dentro del
//     mismo plan, el que menos veces se ha mostrado (rotación justa).
//   - Un mismo producto nunca aparece dos veces (ni como banner y tarjeta, ni
//     como anuncio y resultado normal: el cliente quita el duplicado).
//   - No se muestran anuncios del propio negocio, agotados o pausados.
const maxPatrocinados = 3

type Plan struct {
	Clave         string `json:"clave"`
	Nombre        string `json:"nombre"`
	PrecioSemanaC int64  `json:"precio_semana_c"`
	Banner        bool   `json:"banner"`
	EnBusquedas   bool   `json:"en_busquedas"`
	Descripcion   string `json:"descripcion"`
}

type Patrocinado struct {
	PromoID int64  `json:"promo_id"`
	Plan    string `json:"plan"`
	Mensaje string `json:"mensaje"`
	Publicacion
}

func (s *Servicio) planes(w http.ResponseWriter, r *http.Request) {
	filas, err := s.bd.Query(r.Context(), "SELECT clave, nombre, precio_semana_c, banner, en_busquedas, descripcion FROM planes_promo ORDER BY orden")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (Plan, error) {
		var p Plan
		return p, f.Scan(&p.Clave, &p.Nombre, &p.PrecioSemanaC, &p.Banner, &p.EnBusquedas, &p.Descripcion)
	})
	plataforma.Manejar(w, r, lista, err)
}

// espacios regresa los anuncios para la vista de Explorar (con su búsqueda y
// categoría) y cuenta una impresión de cada uno.
func (s *Servicio) espacios(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	q := r.URL.Query()
	lat, lng, err := s.asegurarPerfil(ctx, id)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	args := []any{lat, lng, id.Cuenta}
	where := []string{"pr.estado = 'activa'", "now() BETWEEN pr.inicio AND pr.fin", "p.estado = 'activa'",
		"(p.existencias IS NULL OR p.existencias > 0)", "pr.vendedor_id <> $3"}
	if c := q.Get("categoria"); c != "" {
		args = append(args, c)
		where = append(where, "p.categoria = $"+strconv.Itoa(len(args)))
	}
	if b := strings.TrimSpace(q.Get("q")); b != "" {
		if utf8.RuneCountInString(b) > 80 {
			plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "La búsqueda es demasiado larga"))
			return
		}
		// En búsquedas solo entran los planes que lo incluyen, y solo si son relevantes.
		args = append(args, b, "%"+escaparLike(b)+"%")
		n := len(args)
		where = append(where, "pl.en_busquedas", "(p.busqueda @@ websearch_to_tsquery('spanish', $"+strconv.Itoa(n-1)+") OR p.titulo ILIKE $"+strconv.Itoa(n)+")")
	}
	sql := "SELECT pr.id, pr.plan, pr.mensaje, pl.banner, " + columnasTarjeta + desdeTarjeta +
		" JOIN promociones pr ON pr.publicacion_id = p.id JOIN planes_promo pl ON pl.clave = pr.plan WHERE " + strings.Join(where, " AND ") +
		" ORDER BY pl.prioridad DESC, pr.impresiones ASC, random() LIMIT 20"
	filas, err := s.bd.Query(ctx, sql, args...)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	type cand struct {
		Patrocinado
		banner bool
	}
	cands, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (cand, error) {
		var c cand
		var vend int64
		var dist *float64
		var archivo, alt *string
		var ancho, alto *int
		err := f.Scan(&c.PromoID, &c.Plan, &c.Mensaje, &c.banner,
			&c.ID, &c.Titulo, &c.Categoria, &c.PrecioC, &c.Unidad, &c.Existencias, &c.Minimo, &c.Estado, &c.Creada,
			&c.Vendedor.Nombre, &c.Vendedor.Slug, &c.Vendedor.Colonia, &vend, &dist, &archivo, &alt, &ancho, &alto)
		if dist != nil {
			d := float64(int(*dist*10+0.5)) / 10
			c.DistanciaKm = &d
		}
		if archivo != nil {
			c.Foto = &Foto{URL: urlFoto(*archivo), Alt: *alt, Ancho: *ancho, Alto: *alto}
		}
		return c, err
	})
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var banner *Patrocinado
	productos := []Patrocinado{}
	usados := map[int64]bool{}
	vendedoresBanner := map[string]bool{}
	// El banner solo en la portada de Explorar o de una categoría (no en búsquedas).
	if q.Get("q") == "" {
		for _, c := range cands {
			if c.banner {
				b := c.Patrocinado
				banner = &b
				usados[c.ID] = true
				vendedoresBanner[c.Vendedor.Slug] = true
				break
			}
		}
	}
	for _, c := range cands {
		if len(productos) == maxPatrocinados {
			break
		}
		// Sin duplicados y sin repetir al negocio del banner en la misma vista.
		if usados[c.ID] || vendedoresBanner[c.Vendedor.Slug] {
			continue
		}
		usados[c.ID] = true
		productos = append(productos, c.Patrocinado)
	}
	ids := []int64{}
	if banner != nil {
		ids = append(ids, banner.PromoID)
	}
	for _, p := range productos {
		ids = append(ids, p.PromoID)
	}
	if len(ids) > 0 {
		_, _ = s.bd.Exec(ctx, "UPDATE promociones SET impresiones = impresiones + 1 WHERE id = ANY($1)", ids)
	}
	plataforma.Manejar(w, r, map[string]any{"banner": banner, "productos": productos}, nil)
}

func (s *Servicio) clicPromo(w http.ResponseWriter, r *http.Request) {
	pid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	// No cuenta los clics del propio negocio.
	_, err = s.bd.Exec(r.Context(), "UPDATE promociones SET clics = clics + 1 WHERE id = $1 AND vendedor_id <> $2 AND estado = 'activa'",
		pid, plataforma.IdentidadDe(r).Cuenta)
	plataforma.Manejar(w, r, nil, err)
}

type MiPromo struct {
	ID          int64  `json:"id"`
	Plan        string `json:"plan"`
	PlanNombre  string `json:"plan_nombre"`
	Mensaje     string `json:"mensaje"`
	Publicacion struct {
		ID     int64  `json:"id"`
		Titulo string `json:"titulo"`
		Foto   *Foto  `json:"foto"`
		Cat    string `json:"categoria"`
	} `json:"publicacion"`
	Semanas     int       `json:"semanas"`
	CostoC      int64     `json:"costo_c"`
	Inicio      time.Time `json:"inicio"`
	Fin         time.Time `json:"fin"`
	Estado      string    `json:"estado"`
	Vigente     bool      `json:"vigente"`
	Impresiones int64     `json:"impresiones"`
	Clics       int64     `json:"clics"`
}

func (s *Servicio) misPromos(w http.ResponseWriter, r *http.Request) {
	filas, err := s.bd.Query(r.Context(), `SELECT pr.id, pr.plan, pl.nombre, pr.mensaje, p.id, p.titulo, p.categoria, pr.semanas, pr.costo_c,
			pr.inicio, pr.fin, pr.estado, now() BETWEEN pr.inicio AND pr.fin, pr.impresiones, pr.clics, f.archivo, f.alt
		FROM promociones pr JOIN planes_promo pl ON pl.clave = pr.plan JOIN publicaciones p ON p.id = pr.publicacion_id
		LEFT JOIN LATERAL (SELECT archivo, alt FROM fotos WHERE publicacion_id = p.id ORDER BY orden, id LIMIT 1) f ON true
		WHERE pr.vendedor_id = $1 ORDER BY pr.id DESC LIMIT 100`, plataforma.IdentidadDe(r).Cuenta)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (MiPromo, error) {
		var m MiPromo
		var archivo, alt *string
		err := f.Scan(&m.ID, &m.Plan, &m.PlanNombre, &m.Mensaje, &m.Publicacion.ID, &m.Publicacion.Titulo, &m.Publicacion.Cat, &m.Semanas, &m.CostoC,
			&m.Inicio, &m.Fin, &m.Estado, &m.Vigente, &m.Impresiones, &m.Clics, &archivo, &alt)
		if archivo != nil {
			m.Publicacion.Foto = &Foto{URL: urlFoto(*archivo), Alt: *alt}
		}
		return m, err
	})
	if lista == nil {
		lista = []MiPromo{}
	}
	plataforma.Manejar(w, r, lista, err)
}

func (s *Servicio) crearPromo(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	var d struct {
		PublicacionID int64  `json:"publicacion_id"`
		Plan          string `json:"plan"`
		Semanas       int    `json:"semanas"`
		Mensaje       string `json:"mensaje"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	d.Mensaje = limpiar(strings.ReplaceAll(d.Mensaje, "\n", " "))
	if utf8.RuneCountInString(d.Mensaje) > 90 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "El mensaje puede tener hasta 90 letras"))
		return
	}
	if d.Semanas < 1 || d.Semanas > 12 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Elige de 1 a 12 semanas"))
		return
	}
	var precio int64
	if err := s.bd.QueryRow(ctx, "SELECT precio_semana_c FROM planes_promo WHERE clave = $1", d.Plan).Scan(&precio); err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Elige un plan"))
		return
	}
	var estado string
	err := s.bd.QueryRow(ctx, "SELECT estado FROM publicaciones WHERE id = $1 AND vendedor_id = $2", d.PublicacionID, id.Cuenta).Scan(&estado)
	if errors.Is(err, pgx.ErrNoRows) {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Elige uno de tus productos"))
		return
	}
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if estado != "activa" {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(409, "El producto está pausado; actívalo antes de anunciarlo"))
		return
	}
	var nuevo int64
	costo := precio * int64(d.Semanas)
	err = s.bd.QueryRow(ctx, `INSERT INTO promociones (vendedor_id, publicacion_id, plan, mensaje, semanas, costo_c, fin)
		VALUES ($1, $2, $3, $4, $5, $6, now() + make_interval(weeks => $5::int)) RETURNING id`,
		id.Cuenta, d.PublicacionID, d.Plan, d.Mensaje, d.Semanas, costo).Scan(&nuevo)
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23505" {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(409, "ya_anunciado", "Ese producto ya tiene un anuncio activo"))
		return
	}
	plataforma.Manejar(w, r, map[string]any{"id": nuevo, "costo_c": costo}, err)
}

func (s *Servicio) estadoPromo(w http.ResponseWriter, r *http.Request) {
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
	tag, err := s.bd.Exec(r.Context(), "UPDATE promociones SET estado = $1 WHERE id = $2 AND vendedor_id = $3", d.Estado, pid, plataforma.IdentidadDe(r).Cuenta)
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23505" {
		err = plataforma.FalloClave(409, "ya_anunciado", "Ese producto ya tiene otro anuncio activo")
	} else if err == nil && tag.RowsAffected() == 0 {
		err = pgx.ErrNoRows
	}
	plataforma.Manejar(w, r, nil, err)
}

// sembrarPromosDemo: anuncios de ejemplo de los tres planes (sin métricas inventadas).
func (s *Servicio) sembrarPromosDemo(ctx context.Context, tx pgx.Tx, pubs map[string]int64) error {
	ejemplos := []struct{ titulo, plan, mensaje string }{
		{"Gorra bordada con tu logo", "premium", "Gorras para tu evento desde 50 piezas, entrega en Durango"},
		{"Chamarra acolchada de invierno", "destacado", "Llegó el frío: precio de mayoreo desde 6 piezas"},
		{"Queso menonita", "basico", "Queso fresco de Nuevo Ideal cada semana"},
		{"Flete local en camioneta de 3.5 t", "basico", "Llevamos tu mercancía dentro de la ciudad"},
	}
	for _, e := range ejemplos {
		pid, ok := pubs[e.titulo]
		if !ok {
			continue
		}
		if _, err := tx.Exec(ctx, `INSERT INTO promociones (vendedor_id, publicacion_id, plan, mensaje, semanas, costo_c, fin)
			SELECT p.vendedor_id, p.id, $2, $3, 4, pl.precio_semana_c * 4, now() + interval '4 weeks'
			FROM publicaciones p, planes_promo pl WHERE p.id = $1 AND pl.clave = $2`, pid, e.plan, e.mensaje); err != nil {
			return err
		}
	}
	return nil
}
