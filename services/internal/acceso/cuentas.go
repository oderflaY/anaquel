package acceso

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/mail"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
)

type cuenta struct {
	ID          int64
	EmailCif    []byte
	PassHash    string
	Nombre      string
	Slug        string
	Giro        string
	Descripcion string
	TelCif      []byte
	Colonia     string
	Lat, Lng    *float64
	Rol         string
	Estado      string
	Vende       bool
	Demo        bool
	TiendaSlug  *string
}

const columnasCuenta = `c.id, c.email_cifrado, c.pass_hash, c.nombre, c.slug, c.giro, c.descripcion, c.telefono_cifrado,
	c.colonia, c.lat, c.lng, c.rol, c.estado, c.vende, c.demo, c.tienda_slug`

func (c *cuenta) campos() []any {
	return []any{&c.ID, &c.EmailCif, &c.PassHash, &c.Nombre, &c.Slug, &c.Giro, &c.Descripcion, &c.TelCif,
		&c.Colonia, &c.Lat, &c.Lng, &c.Rol, &c.Estado, &c.Vende, &c.Demo, &c.TiendaSlug}
}

func (c cuenta) identidad() plataforma.Identidad {
	return plataforma.Identidad{Cuenta: c.ID, Rol: c.Rol, Estado: c.Estado, Vende: c.Vende, Nombre: c.Nombre, Slug: c.Slug}
}

// CuentaPublica es lo que ve el dueño de la cuenta.
type CuentaPublica struct {
	ID          int64   `json:"id"`
	Nombre      string  `json:"nombre"`
	Slug        string  `json:"slug"`
	Email       string  `json:"email"`
	Telefono    string  `json:"telefono"`
	Giro        string  `json:"giro"`
	Descripcion string  `json:"descripcion"`
	Colonia     string  `json:"colonia"`
	Rol         string  `json:"rol"`
	Estado      string  `json:"estado"`
	Vende       bool    `json:"vende"`
	Demo        bool    `json:"demo"`
	TiendaSlug  *string `json:"tienda_slug"`
}

func (s *Servicio) publica(c cuenta) CuentaPublica {
	email, _ := s.cif.Descifrar("cuentas.email", c.EmailCif)
	tel := ""
	if c.TelCif != nil {
		tel, _ = s.cif.Descifrar("cuentas.telefono", c.TelCif)
	}
	return CuentaPublica{ID: c.ID, Nombre: c.Nombre, Slug: c.Slug, Email: email, Telefono: tel, Giro: c.Giro,
		Descripcion: c.Descripcion, Colonia: c.Colonia, Rol: c.Rol, Estado: c.Estado, Vende: c.Vende, Demo: c.Demo, TiendaSlug: c.TiendaSlug}
}

// ---- utilidades de validación ----

func normalizarEmail(e string) (string, bool) {
	e = strings.ToLower(strings.TrimSpace(e))
	if e == "" || len(e) > 254 {
		return "", false
	}
	a, err := mail.ParseAddress(e)
	if err != nil || a.Address != e || a.Name != "" {
		return "", false
	}
	return e, true
}

// aUTF8 descarta bytes inválidos y caracteres de control (evita basura en la base).
func aUTF8(s string) string {
	s = strings.ToValidUTF8(s, "")
	return strings.Map(func(r rune) rune {
		if r < 0x20 && r != '\n' || r == 0x7f || (r >= 0x200b && r <= 0x200f) || (r >= 0x202a && r <= 0x202e) {
			return -1
		}
		return r
	}, s)
}

func textoLimpio(s string, max int) (string, bool) {
	s = strings.TrimSpace(aUTF8(strings.ReplaceAll(s, "\n", " ")))
	return s, utf8.RuneCountInString(s) <= max
}

var soloDigitos = regexp.MustCompile(`\D`)

func normalizarTelefono(t string) (string, bool) {
	t = soloDigitos.ReplaceAllString(t, "")
	if t == "" {
		return "", true
	}
	if strings.HasPrefix(t, "52") && len(t) == 12 {
		t = t[2:]
	}
	return t, len(t) == 10
}

var giros = map[string]bool{"abarrotes": true, "ropa": true, "papeleria": true, "ferreteria": true, "farmacia": true,
	"dulceria": true, "comida": true, "mayoreo": true, "servicios": true, "productor": true, "otro": true}

func slugDe(nombre string) string {
	r := strings.NewReplacer("á", "a", "é", "e", "í", "i", "ó", "o", "ú", "u", "ü", "u", "ñ", "n")
	var b strings.Builder
	guion := false
	for _, c := range r.Replace(strings.ToLower(nombre)) {
		if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') {
			b.WriteRune(c)
			guion = false
		} else if !guion && b.Len() > 0 {
			b.WriteByte('-')
			guion = true
		}
	}
	s := strings.Trim(b.String(), "-")
	if len(s) > 48 {
		s = strings.Trim(s[:48], "-")
	}
	if s == "" {
		s = "negocio"
	}
	return s
}

func (s *Servicio) slugLibre(ctx context.Context, q interface {
	QueryRow(context.Context, string, ...any) pgx.Row
}, base string) (string, error) {
	sl := base
	for i := 2; i < 1000; i++ {
		var n int
		if err := q.QueryRow(ctx, "SELECT COUNT(*) FROM cuentas WHERE slug = $1", sl).Scan(&n); err != nil {
			return "", err
		}
		if n == 0 {
			return sl, nil
		}
		sl = fmt.Sprintf("%s-%d", base, i)
	}
	return base + "-" + plataforma.Aleatorio(3), nil
}

// ---- intentos y bloqueo progresivo ----

const (
	umbralCorreo = 5  // fallos seguidos por correo antes de bloquear
	umbralIP     = 30 // fallos por IP en 30 min
)

func (s *Servicio) esperaBloqueo(ctx context.Context, claves ...[]byte) time.Duration {
	var hasta *time.Time
	_ = s.bd.QueryRow(ctx, "SELECT max(bloqueado_hasta) FROM intentos WHERE clave = ANY($1) AND bloqueado_hasta > now()", claves).Scan(&hasta)
	if hasta == nil {
		return 0
	}
	return time.Until(*hasta)
}

func (s *Servicio) registrarFallo(ctx context.Context, clave []byte, umbral int, maxMin int) {
	var fallos int
	err := s.bd.QueryRow(ctx, `INSERT INTO intentos (clave, fallos) VALUES ($1, 1)
		ON CONFLICT (clave) DO UPDATE SET
			fallos = CASE WHEN intentos.actualizado < now() - interval '30 minutes'
				AND (intentos.bloqueado_hasta IS NULL OR intentos.bloqueado_hasta < now()) THEN 1 ELSE intentos.fallos + 1 END,
			actualizado = now()
		RETURNING fallos`, clave).Scan(&fallos)
	if err != nil || fallos < umbral {
		return
	}
	minutos := 1 << min(fallos-umbral, 10)
	if minutos > maxMin {
		minutos = maxMin
	}
	_, _ = s.bd.Exec(ctx, "UPDATE intentos SET bloqueado_hasta = now() + make_interval(mins => $2) WHERE clave = $1", clave, minutos)
}

func (s *Servicio) limpiarIntentos(ctx context.Context, clave []byte) {
	_, _ = s.bd.Exec(ctx, "DELETE FROM intentos WHERE clave = $1", clave)
}

func errBloqueo(w http.ResponseWriter, espera time.Duration) error {
	min := int(espera.Minutes()) + 1
	w.Header().Set("Retry-After", fmt.Sprint(int(espera.Seconds())+1))
	txt := "1 minuto"
	if min > 1 {
		txt = fmt.Sprintf("%d minutos", min)
	}
	return plataforma.FalloClave(http.StatusTooManyRequests, "bloqueado",
		"Hubo demasiados intentos. Por seguridad espera "+txt+" y vuelve a intentar.")
}

// ---- handlers públicos ----

func (s *Servicio) config(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{"demo": s.cfg.Demo}
	if s.cfg.Demo {
		out["cuentas_demo"] = cuentasDemoPublicas()
	}
	plataforma.Manejar(w, r, out, nil)
}

var errCredenciales = plataforma.FalloClave(http.StatusUnauthorized, "credenciales", "El correo o la contraseña no coinciden")

func (s *Servicio) entrar(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var d struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	email, ok := normalizarEmail(d.Email)
	if !ok || d.Password == "" || len(d.Password) > 256 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Escribe tu correo y tu contraseña"))
		return
	}
	claveCorreo := s.idx.De("intentos.correo", email)
	claveIP := s.idx.De("intentos.ip", s.ipDe(r))
	if espera := s.esperaBloqueo(ctx, claveCorreo, claveIP); espera > 0 {
		s.anotar(ctx, r, nil, "entrar_bloqueado", nil)
		plataforma.Manejar(w, r, nil, errBloqueo(w, espera))
		return
	}

	var c cuenta
	err := s.bd.QueryRow(ctx, "SELECT "+columnasCuenta+" FROM cuentas c WHERE c.email_idx = $1", s.idx.De("cuentas.email", email)).Scan(c.campos()...)
	if errors.Is(err, pgx.ErrNoRows) {
		verificarPass(hashSenuelo, d.Password) // mismo tiempo que con una cuenta real
		s.registrarFallo(ctx, claveCorreo, umbralCorreo, 60)
		s.registrarFallo(ctx, claveIP, umbralIP, 15)
		s.anotar(ctx, r, nil, "entrar_fallido", map[string]any{"motivo": "sin_cuenta"})
		plataforma.Manejar(w, r, nil, errCredenciales)
		return
	}
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	ok, recifrar := verificarPass(c.PassHash, d.Password)
	if !ok {
		s.registrarFallo(ctx, claveCorreo, umbralCorreo, 60)
		s.registrarFallo(ctx, claveIP, umbralIP, 15)
		s.anotar(ctx, r, &c.ID, "entrar_fallido", map[string]any{"motivo": "contrasena"})
		plataforma.Manejar(w, r, nil, errCredenciales)
		return
	}
	s.limpiarIntentos(ctx, claveCorreo)
	if recifrar {
		if h, err := hashArgon(d.Password); err == nil {
			_, _ = s.bd.Exec(ctx, "UPDATE cuentas SET pass_hash = $1 WHERE id = $2", h, c.ID)
		}
	}
	if c.Estado == plataforma.EstadoSuspendida {
		s.anotar(ctx, r, &c.ID, "entrar_suspendida", nil)
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(403, "suspendida", "Tu cuenta está suspendida. Escríbenos para revisarla."))
		return
	}
	// Contra fijación de sesión: la sesión que traía (si traía) se revoca y se crea una nueva.
	if vieja, err := r.Cookie(s.nombreCookie()); err == nil && vieja.Value != "" {
		_, _ = s.bd.Exec(ctx, "DELETE FROM sesiones WHERE token_hash = $1", hashToken(vieja.Value))
	}
	token, err := s.crearSesion(ctx, c.ID, r.UserAgent())
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	s.ponerCookie(w, token)
	s.anotar(ctx, r, &c.ID, "entrar", nil)
	plataforma.Manejar(w, r, map[string]any{"cuenta": s.publica(c)}, nil)
}

func (s *Servicio) salir(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(s.nombreCookie()); err == nil && c.Value != "" && len(c.Value) <= 128 {
		var id *int64
		_ = s.bd.QueryRow(r.Context(), "DELETE FROM sesiones WHERE token_hash = $1 RETURNING cuenta_id", hashToken(c.Value)).Scan(&id)
		if id != nil {
			s.anotar(r.Context(), r, id, "salir", nil)
		}
	}
	s.borrarCookie(w)
	plataforma.Manejar(w, r, nil, nil)
}

type datosRegistro struct {
	Codigo   string   `json:"codigo"`
	Nombre   string   `json:"nombre"`
	Email    string   `json:"email"`
	Password string   `json:"password"`
	Telefono string   `json:"telefono"`
	Colonia  string   `json:"colonia"`
	Giro     string   `json:"giro"`
	Lat      *float64 `json:"lat"`
	Lng      *float64 `json:"lng"`
}

func normalizarCodigo(c string) string {
	var b strings.Builder
	for _, r := range strings.ToUpper(c) {
		if (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func (s *Servicio) registro(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var d datosRegistro
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	claveIP := s.idx.De("registro.ip", s.ipDe(r))
	if espera := s.esperaBloqueo(ctx, claveIP); espera > 0 {
		plataforma.Manejar(w, r, nil, errBloqueo(w, espera))
		return
	}
	nombre, okNombre := textoLimpio(d.Nombre, 80)
	colonia, okColonia := textoLimpio(d.Colonia, 80)
	email, okEmail := normalizarEmail(d.Email)
	tel, okTel := normalizarTelefono(d.Telefono)
	codigo := normalizarCodigo(d.Codigo)
	switch {
	case len(codigo) != 10:
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Escribe el código de invitación completo (10 letras y números)"))
		return
	case utf8.RuneCountInString(nombre) < 2 || !okNombre:
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Escribe el nombre de tu negocio (máximo 80 letras)"))
		return
	case !okEmail:
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Revisa tu correo, parece incompleto"))
		return
	case !okTel:
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "El teléfono debe tener 10 dígitos"))
		return
	case !okColonia || colonia == "":
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Escribe la colonia o localidad de tu negocio"))
		return
	case !giros[d.Giro]:
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Elige el giro de tu negocio"))
		return
	case (d.Lat == nil) != (d.Lng == nil):
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Ubicación incompleta"))
		return
	case d.Lat != nil && !s.cfg.Zona.Contiene(*d.Lat, *d.Lng):
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(400, "fuera_de_zona", "Por ahora el mercado es solo para negocios de Durango"))
		return
	}
	if err := validarPass(d.Password, email); err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, err.Error()))
		return
	}
	hash, err := hashArgon(d.Password)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	emailCif, err := s.cif.Cifrar("cuentas.email", email)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var telCif []byte
	if tel != "" {
		if telCif, err = s.cif.Cifrar("cuentas.telefono", tel); err != nil {
			plataforma.Manejar(w, r, nil, err)
			return
		}
	}

	var nueva cuenta
	err = pgx.BeginFunc(ctx, s.bd, func(tx pgx.Tx) error {
		var invID, invitador int64
		err := tx.QueryRow(ctx, `SELECT i.id, i.creada_por FROM invitaciones i JOIN cuentas c ON c.id = i.creada_por
			WHERE i.codigo_hash = $1 AND i.usada IS NULL AND i.expira > now() AND c.estado = 'verificada'
			FOR UPDATE OF i`, s.idx.De("invitaciones.codigo", codigo)).Scan(&invID, &invitador)
		if errors.Is(err, pgx.ErrNoRows) {
			return plataforma.FalloClave(400, "codigo_invalido", "El código de invitación no es válido o ya se usó")
		}
		if err != nil {
			return err
		}
		slug, err := s.slugLibre(ctx, tx, slugDe(nombre))
		if err != nil {
			return err
		}
		err = tx.QueryRow(ctx, `INSERT INTO cuentas (email_idx, email_cifrado, pass_hash, nombre, slug, giro, telefono_cifrado,
				colonia, lat, lng, invitada_por)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
			ON CONFLICT (email_idx) DO NOTHING
			RETURNING `+strings.ReplaceAll(columnasCuenta, "c.", ""),
			s.idx.De("cuentas.email", email), emailCif, hash, nombre, slug, d.Giro, telCif, colonia, d.Lat, d.Lng, invitador,
		).Scan(nueva.campos()...)
		if errors.Is(err, pgx.ErrNoRows) {
			return plataforma.FalloClave(409, "correo_registrado", "Ese correo ya tiene cuenta. Inicia sesión.")
		}
		if err != nil {
			return err
		}
		_, err = tx.Exec(ctx, "UPDATE invitaciones SET usada = now(), usada_por = $1 WHERE id = $2", nueva.ID, invID)
		return err
	})
	if err != nil {
		var e plataforma.ErrHTTP
		if errors.As(err, &e) && e.Clave == "codigo_invalido" {
			s.registrarFallo(ctx, claveIP, 10, 30)
		}
		plataforma.Manejar(w, r, nil, err)
		return
	}
	token, err := s.crearSesion(ctx, nueva.ID, r.UserAgent())
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	s.ponerCookie(w, token)
	s.anotar(ctx, r, &nueva.ID, "registro", nil)
	plataforma.Manejar(w, r, map[string]any{"cuenta": s.publica(nueva)}, nil)
}

// ---- handlers con sesión ----

func (s *Servicio) yo(w http.ResponseWriter, r *http.Request) {
	plataforma.Manejar(w, r, s.publica(sesionDe(r).cuenta), nil)
}

func (s *Servicio) actualizarYo(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Nombre      string `json:"nombre"`
		Descripcion string `json:"descripcion"`
		Telefono    string `json:"telefono"`
		Colonia     string `json:"colonia"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	nombre, ok1 := textoLimpio(d.Nombre, 80)
	colonia, ok2 := textoLimpio(d.Colonia, 80)
	desc := strings.TrimSpace(aUTF8(d.Descripcion))
	tel, ok3 := normalizarTelefono(d.Telefono)
	if utf8.RuneCountInString(nombre) < 2 || !ok1 || !ok2 || colonia == "" || !ok3 || utf8.RuneCountInString(desc) > 600 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Revisa los datos: nombre, colonia y un teléfono de 10 dígitos"))
		return
	}
	var telCif []byte
	if tel != "" {
		var err error
		if telCif, err = s.cif.Cifrar("cuentas.telefono", tel); err != nil {
			plataforma.Manejar(w, r, nil, err)
			return
		}
	}
	var c cuenta
	err := s.bd.QueryRow(r.Context(), `UPDATE cuentas c SET nombre = $1, descripcion = $2, telefono_cifrado = $3, colonia = $4
		WHERE c.id = $5 RETURNING `+columnasCuenta, nombre, desc, telCif, colonia, sesionDe(r).cuenta.ID).Scan(c.campos()...)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	plataforma.Manejar(w, r, s.publica(c), nil)
}

func (s *Servicio) cambiarPass(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var d struct {
		Actual string `json:"actual"`
		Nueva  string `json:"nueva"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	se := sesionDe(r)
	clave := s.idx.De("intentos.cambio", fmt.Sprint(se.cuenta.ID))
	if espera := s.esperaBloqueo(ctx, clave); espera > 0 {
		plataforma.Manejar(w, r, nil, errBloqueo(w, espera))
		return
	}
	if ok, _ := verificarPass(se.cuenta.PassHash, d.Actual); !ok || len(d.Actual) > 256 {
		s.registrarFallo(ctx, clave, umbralCorreo, 60)
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Tu contraseña actual no coincide"))
		return
	}
	email, _ := s.cif.Descifrar("cuentas.email", se.cuenta.EmailCif)
	if err := validarPass(d.Nueva, email); err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, err.Error()))
		return
	}
	hash, err := hashArgon(d.Nueva)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	err = pgx.BeginFunc(ctx, s.bd, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "UPDATE cuentas SET pass_hash = $1, pass_cambiada = now() WHERE id = $2", hash, se.cuenta.ID); err != nil {
			return err
		}
		// Cambiar la contraseña cierra todas las demás sesiones.
		_, err := tx.Exec(ctx, "DELETE FROM sesiones WHERE cuenta_id = $1 AND token_hash <> $2", se.cuenta.ID, se.tokenHash)
		return err
	})
	if err == nil {
		s.limpiarIntentos(ctx, clave)
		s.anotar(ctx, r, &se.cuenta.ID, "contrasena_cambiada", nil)
	}
	plataforma.Manejar(w, r, nil, err)
}

func (s *Servicio) listarSesiones(w http.ResponseWriter, r *http.Request) {
	se := sesionDe(r)
	filas, err := s.bd.Query(r.Context(), `SELECT token_hash = $2, creada, ultimo_uso, agente FROM sesiones
		WHERE cuenta_id = $1 AND expira > now() ORDER BY ultimo_uso DESC LIMIT 50`, se.cuenta.ID, se.tokenHash)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	type fila struct {
		Actual    bool      `json:"actual"`
		Creada    time.Time `json:"creada"`
		UltimoUso time.Time `json:"ultimo_uso"`
		Agente    string    `json:"agente"`
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (fila, error) {
		var x fila
		return x, f.Scan(&x.Actual, &x.Creada, &x.UltimoUso, &x.Agente)
	})
	plataforma.Manejar(w, r, lista, err)
}

func (s *Servicio) cerrarOtras(w http.ResponseWriter, r *http.Request) {
	se := sesionDe(r)
	_, err := s.bd.Exec(r.Context(), "DELETE FROM sesiones WHERE cuenta_id = $1 AND token_hash <> $2", se.cuenta.ID, se.tokenHash)
	if err == nil {
		s.anotar(r.Context(), r, &se.cuenta.ID, "cerrar_otras_sesiones", nil)
	}
	plataforma.Manejar(w, r, nil, err)
}
