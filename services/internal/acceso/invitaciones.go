package acceso

import (
	"crypto/rand"
	"errors"
	"math/big"
	"net/http"
	"time"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
)

// Alfabeto sin caracteres que se confunden (0/O, 1/I/L). 31^10 ≈ 8·10^14 códigos.
const alfabetoCodigo = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

const (
	vigenciaInvitacion  = 7 * 24 * time.Hour
	invitacionesActivas = 5
)

func codigoInvitacion() string {
	b := make([]byte, 10)
	n := big.NewInt(int64(len(alfabetoCodigo)))
	for i := range b {
		x, err := rand.Int(rand.Reader, n)
		if err != nil {
			panic("sin aleatoriedad del sistema")
		}
		b[i] = alfabetoCodigo[x.Int64()]
	}
	return string(b)
}

// crearInvitacion: un cliente verificado invita a otro negocio. El código se
// muestra una sola vez; en la base solo queda su HMAC.
func (s *Servicio) crearInvitacion(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	se := sesionDe(r)
	var activas int
	if err := s.bd.QueryRow(ctx, "SELECT COUNT(*) FROM invitaciones WHERE creada_por = $1 AND usada IS NULL AND expira > now()",
		se.cuenta.ID).Scan(&activas); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if activas >= invitacionesActivas {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(409, "limite", "Ya tienes 5 invitaciones sin usar. Espera a que las usen o venzan."))
		return
	}
	codigo := codigoInvitacion()
	expira := time.Now().Add(vigenciaInvitacion)
	if _, err := s.bd.Exec(ctx, "INSERT INTO invitaciones (codigo_hash, creada_por, expira) VALUES ($1, $2, $3)",
		s.idx.De("invitaciones.codigo", codigo), se.cuenta.ID, expira); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	s.anotar(ctx, r, &se.cuenta.ID, "invitacion_creada", nil)
	plataforma.Manejar(w, r, map[string]any{"codigo": codigo[:5] + "-" + codigo[5:], "expira": expira}, nil)
}

func (s *Servicio) listarInvitaciones(w http.ResponseWriter, r *http.Request) {
	filas, err := s.bd.Query(r.Context(), `SELECT i.id, i.creada, i.expira, i.usada, c.nombre FROM invitaciones i
		LEFT JOIN cuentas c ON c.id = i.usada_por WHERE i.creada_por = $1 ORDER BY i.creada DESC LIMIT 50`, sesionDe(r).cuenta.ID)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	type fila struct {
		ID       int64      `json:"id"`
		Creada   time.Time  `json:"creada"`
		Expira   time.Time  `json:"expira"`
		Usada    *time.Time `json:"usada"`
		UsadaPor *string    `json:"usada_por"`
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (fila, error) {
		var x fila
		return x, f.Scan(&x.ID, &x.Creada, &x.Expira, &x.Usada, &x.UsadaPor)
	})
	plataforma.Manejar(w, r, lista, err)
}

// ---- administración: revisar y verificar cuentas ----

func (s *Servicio) adminCuentas(w http.ResponseWriter, r *http.Request) {
	estado := r.URL.Query().Get("estado")
	if estado == "" {
		estado = plataforma.EstadoPendiente
	}
	if estado != plataforma.EstadoPendiente && estado != plataforma.EstadoVerificada && estado != plataforma.EstadoSuspendida {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Estado inválido"))
		return
	}
	filas, err := s.bd.Query(r.Context(), "SELECT "+columnasCuenta+" FROM cuentas c WHERE c.estado = $1 ORDER BY c.creada DESC LIMIT 200", estado)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (CuentaPublica, error) {
		var c cuenta
		if err := f.Scan(c.campos()...); err != nil {
			return CuentaPublica{}, err
		}
		return s.publica(c), nil
	})
	plataforma.Manejar(w, r, lista, err)
}

func (s *Servicio) adminAccion(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	admin := sesionDe(r).cuenta.ID
	if id == admin {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "No puedes cambiar tu propia cuenta desde aquí"))
		return
	}
	var sql string
	switch r.PathValue("accion") {
	case "verificar":
		sql = "UPDATE cuentas SET estado = 'verificada', verificada_por = $2 WHERE id = $1 AND rol = 'cliente'"
	case "suspender":
		sql = "UPDATE cuentas SET estado = 'suspendida' WHERE id = $1 AND rol = 'cliente' AND $2::bigint IS NOT NULL"
	case "permitir-venta":
		sql = "UPDATE cuentas SET vende = true WHERE id = $1 AND $2::bigint IS NOT NULL"
	case "quitar-venta":
		sql = "UPDATE cuentas SET vende = false WHERE id = $1 AND $2::bigint IS NOT NULL"
	default:
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Acción desconocida"))
		return
	}
	err = pgx.BeginFunc(ctx, s.bd, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, sql, id, admin)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return pgx.ErrNoRows
		}
		if r.PathValue("accion") == "suspender" {
			_, err = tx.Exec(ctx, "DELETE FROM sesiones WHERE cuenta_id = $1", id)
		}
		return err
	})
	if err == nil {
		s.anotar(ctx, r, &admin, "admin_"+r.PathValue("accion"), map[string]any{"cuenta": id})
	}
	plataforma.Manejar(w, r, nil, err)
}

// ---- interno ----

// internoSesion: la pasarela pregunta "¿de quién es este token?".
func (s *Servicio) internoSesion(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Token string `json:"token"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil || d.Token == "" || len(d.Token) > 128 {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(401, "sin_sesion", "Inicia sesión para continuar"))
		return
	}
	c, err := s.cuentaDeSesion(r.Context(), hashToken(d.Token))
	if errors.Is(err, pgx.ErrNoRows) {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(401, "sin_sesion", "Tu sesión terminó. Vuelve a entrar."))
		return
	}
	plataforma.Manejar(w, r, c.identidad(), err)
}

// Perfil es lo que otros servicios pueden saber de una cuenta (sin datos personales).
type Perfil struct {
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
	Email       string   `json:"email,omitempty"` // solo en /interno/demo
}

func perfilDe(c cuenta) Perfil {
	return Perfil{ID: c.ID, Nombre: c.Nombre, Slug: c.Slug, Giro: c.Giro, Descripcion: c.Descripcion, Colonia: c.Colonia,
		Lat: c.Lat, Lng: c.Lng, Vende: c.Vende, Estado: c.Estado, TiendaSlug: c.TiendaSlug}
}

func (s *Servicio) internoPerfil(w http.ResponseWriter, r *http.Request) {
	id, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var c cuenta
	err = s.bd.QueryRow(r.Context(), "SELECT "+columnasCuenta+" FROM cuentas c WHERE c.id = $1", id).Scan(c.campos()...)
	plataforma.Manejar(w, r, perfilDe(c), err)
}

func (s *Servicio) internoDemo(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.Demo {
		plataforma.Manejar(w, r, []Perfil{}, nil)
		return
	}
	filas, err := s.bd.Query(r.Context(), "SELECT "+columnasCuenta+" FROM cuentas c WHERE c.demo ORDER BY c.id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (Perfil, error) {
		var c cuenta
		if err := f.Scan(c.campos()...); err != nil {
			return Perfil{}, err
		}
		p := perfilDe(c)
		p.Email, _ = s.cif.Descifrar("cuentas.email", c.EmailCif)
		return p, nil
	})
	plataforma.Manejar(w, r, lista, err)
}
