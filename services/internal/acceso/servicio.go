// Package acceso es el servicio de cuentas: entrar, salir, registro por
// invitación, sesiones y el "protocolo de acceso" que deja pasar al mercado
// solo a clientes verificados de Durango.
package acceso

import (
	"context"
	"crypto/sha256"
	"embed"
	"encoding/json"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"time"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migraciones/*.sql
var migracionesFS embed.FS

// Migraciones del esquema "acceso".
func Migraciones() fs.FS { sub, _ := fs.Sub(migracionesFS, "migraciones"); return sub }

// Zona donde opera el mercado (Durango capital y municipios vecinos).
type Zona struct{ LatMin, LatMax, LngMin, LngMax float64 }

func (z Zona) Contiene(lat, lng float64) bool {
	return lat >= z.LatMin && lat <= z.LatMax && lng >= z.LngMin && lng <= z.LngMax
}

var ZonaDurango = Zona{LatMin: 23.0, LatMax: 25.0, LngMin: -105.8, LngMax: -103.6}

type Config struct {
	CookieSegura bool // false solo en desarrollo por http://
	Demo         bool
	Zona         Zona
}

const (
	duracionInactiva = 12 * time.Hour
	duracionMaxima   = 7 * 24 * time.Hour
	refrescoUso      = 5 * time.Minute
)

type Servicio struct {
	bd    *pgxpool.Pool
	cif   *plataforma.Cifrador
	idx   *plataforma.Indice
	firma *plataforma.Firmante
	cfg   Config
}

func Nuevo(bd *pgxpool.Pool, cif *plataforma.Cifrador, idx *plataforma.Indice, firma *plataforma.Firmante, cfg Config) *Servicio {
	if cfg.Zona == (Zona{}) {
		cfg.Zona = ZonaDurango
	}
	return &Servicio{bd: bd, cif: cif, idx: idx, firma: firma, cfg: cfg}
}

func (s *Servicio) Rutas() http.Handler {
	m := http.NewServeMux()
	m.HandleFunc("GET /config", s.config)
	m.HandleFunc("POST /entrar", s.entrar)
	m.HandleFunc("POST /registro", s.registro)
	m.HandleFunc("POST /salir", s.salir)

	m.HandleFunc("GET /yo", s.conSesion(nil, s.yo))
	m.HandleFunc("PUT /yo", s.conSesion(nil, s.actualizarYo))
	m.HandleFunc("PUT /contrasena", s.conSesion(nil, s.cambiarPass))
	m.HandleFunc("GET /sesiones", s.conSesion(nil, s.listarSesiones))
	m.HandleFunc("POST /sesiones/cerrar-otras", s.conSesion(nil, s.cerrarOtras))

	m.HandleFunc("GET /invitaciones", s.conSesion(plataforma.ClienteVerificado, s.listarInvitaciones))
	m.HandleFunc("POST /invitaciones", s.conSesion(plataforma.ClienteVerificado, s.crearInvitacion))

	m.HandleFunc("GET /admin/cuentas", s.conSesion(plataforma.Admin, s.adminCuentas))
	m.HandleFunc("POST /admin/cuentas/{id}/{accion}", s.conSesion(plataforma.Admin, s.adminAccion))

	// Solo para otros servicios (firmado con la clave interna).
	m.HandleFunc("POST /interno/sesion", s.firma.Exigir(plataforma.Servicio("pasarela"), s.internoSesion))
	m.HandleFunc("GET /interno/cuentas/{id}", s.firma.Exigir(plataforma.Servicio("mercado", "reels", "pagos"), s.internoPerfil))
	m.HandleFunc("GET /interno/demo", s.firma.Exigir(plataforma.Servicio("mercado", "reels", "pagos"), s.internoDemo))

	m.HandleFunc("GET /salud", func(w http.ResponseWriter, r *http.Request) {
		plataforma.Manejar(w, r, nil, s.bd.Ping(r.Context()))
	})
	return m
}

// ---- cookie de sesión ----

func (s *Servicio) nombreCookie() string {
	if s.cfg.CookieSegura {
		return "__Host-anaquel_mercado" // __Host-: solo HTTPS, sin Domain, Path=/
	}
	return "anaquel_mercado"
}

// NombreCookie lo usa la pasarela para leer la sesión.
func NombreCookie(segura bool) string {
	if segura {
		return "__Host-anaquel_mercado"
	}
	return "anaquel_mercado"
}

func (s *Servicio) ponerCookie(w http.ResponseWriter, token string) {
	http.SetCookie(w, &http.Cookie{
		Name: s.nombreCookie(), Value: token, Path: "/", MaxAge: int(duracionMaxima.Seconds()),
		HttpOnly: true, Secure: s.cfg.CookieSegura, SameSite: http.SameSiteStrictMode,
	})
}

func (s *Servicio) borrarCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name: s.nombreCookie(), Value: "", Path: "/", MaxAge: -1,
		HttpOnly: true, Secure: s.cfg.CookieSegura, SameSite: http.SameSiteStrictMode,
	})
}

func hashToken(t string) []byte { h := sha256.Sum256([]byte(t)); return h[:] }

// ipDe: la IP que firmó la pasarela; si no hay (llamada directa), la del socket.
func (s *Servicio) ipDe(r *http.Request) string {
	if id, ok := s.firma.Leer(r); ok && id.IP != "" {
		return id.IP
	}
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

// ---- sesión en el contexto ----

type sesion struct {
	tokenHash []byte
	cuenta    cuenta
}

type ctxSesion struct{}

func sesionDe(r *http.Request) sesion { return r.Context().Value(ctxSesion{}).(sesion) }

func (s *Servicio) conSesion(regla plataforma.Regla, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(s.nombreCookie())
		if err != nil || c.Value == "" || len(c.Value) > 128 {
			plataforma.Manejar(w, r, nil, plataforma.FalloClave(401, "sin_sesion", "Inicia sesión para continuar"))
			return
		}
		th := hashToken(c.Value)
		cu, err := s.cuentaDeSesion(r.Context(), th)
		if err != nil {
			s.borrarCookie(w)
			plataforma.Manejar(w, r, nil, plataforma.FalloClave(401, "sin_sesion", "Tu sesión terminó. Vuelve a entrar."))
			return
		}
		if regla != nil {
			if err := regla(cu.identidad()); err != nil {
				plataforma.Manejar(w, r, nil, err)
				return
			}
		}
		h(w, r.WithContext(context.WithValue(r.Context(), ctxSesion{}, sesion{tokenHash: th, cuenta: cu})))
	}
}

// cuentaDeSesion valida vigencia (12 h sin uso, 7 días máximo) y renueva el uso.
func (s *Servicio) cuentaDeSesion(ctx context.Context, th []byte) (cuenta, error) {
	var c cuenta
	var ultimo time.Time
	err := s.bd.QueryRow(ctx, `SELECT `+columnasCuenta+`, s.ultimo_uso FROM sesiones s JOIN cuentas c ON c.id = s.cuenta_id
		WHERE s.token_hash = $1 AND s.expira > now() AND s.ultimo_uso > now() - make_interval(secs => $2)`,
		th, duracionInactiva.Seconds()).Scan(append(c.campos(), &ultimo)...)
	if err != nil {
		return c, err
	}
	if c.Estado == plataforma.EstadoSuspendida {
		return c, plataforma.FalloClave(403, "suspendida", "Tu cuenta está suspendida")
	}
	if time.Since(ultimo) > refrescoUso {
		_, _ = s.bd.Exec(ctx, "UPDATE sesiones SET ultimo_uso = now() WHERE token_hash = $1", th)
	}
	return c, nil
}

func (s *Servicio) crearSesion(ctx context.Context, cuentaID int64, agente string) (string, error) {
	token := plataforma.Aleatorio(32) // 256 bits
	if len(agente) > 120 {
		agente = agente[:120]
	}
	_, err := s.bd.Exec(ctx, `INSERT INTO sesiones (token_hash, cuenta_id, expira, agente) VALUES ($1, $2, now() + make_interval(secs => $3), $4)`,
		hashToken(token), cuentaID, duracionMaxima.Seconds(), aUTF8(agente))
	// Limpieza oportunista de sesiones vencidas.
	_, _ = s.bd.Exec(ctx, "DELETE FROM sesiones WHERE expira < now() - interval '1 day'")
	return token, err
}

// ---- bitácora ----

func (s *Servicio) anotar(ctx context.Context, r *http.Request, cuentaID *int64, evento string, detalle map[string]any) {
	if detalle == nil {
		detalle = map[string]any{}
	}
	j, _ := json.Marshal(detalle)
	if _, err := s.bd.Exec(ctx, "INSERT INTO bitacora (cuenta_id, evento, ip_idx, detalle) VALUES ($1, $2, $3, $4)",
		cuentaID, evento, s.idx.De("bitacora.ip", s.ipDe(r)), j); err != nil {
		slog.Error("bitácora", "err", err)
	}
}
