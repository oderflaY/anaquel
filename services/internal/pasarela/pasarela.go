// Package pasarela es la única puerta de entrada pública. Hace cuatro cosas:
//  1. Enruta: /api/acceso, /api/mercado, /api/reels y /api/pagos a sus
//     servicios; todo lo demás (panel, 3D, web) al núcleo de Anaquel, sin cambios.
//  2. Protege: cabeceras de seguridad, límite de peticiones por IP, defensa
//     CSRF (Origin / Sec-Fetch-Site) y tamaño máximo de cuerpo.
//  3. Autentica: convierte la cookie de sesión en una identidad firmada que
//     los servicios verifican. Solo clientes verificados pasan al mercado.
//  4. Limpia: borra cabeceras X-Anaquel-* que vengan de fuera y la cookie de
//     sesión antes de llegar a los servicios de negocio.
package pasarela

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"

	"anaquel/servicios/internal/acceso"
	"anaquel/servicios/internal/plataforma"
)

type Config struct {
	Nucleo, Acceso, Mercado, Reels, Pagos string
	CookieSegura                          bool
	ConfiarProxy                          bool     // usar X-Forwarded-For del proxy de enfrente
	HSTS                                  bool     // solo si se sirve por HTTPS
	Origenes                              []string // orígenes extra permitidos (p. ej. Vite en desarrollo)
}

type Pasarela struct {
	cfg      Config
	firma    *plataforma.Firmante
	acceso   *plataforma.ClienteInterno
	general  *limitador
	sensible *limitador
	rutas    map[string]*httputil.ReverseProxy
	nucleo   *httputil.ReverseProxy
}

const prefijo = "/anaquel"

// Servicios detrás de la pasarela (prefijo /api/<nombre>).
var servicios = []string{"acceso", "mercado", "reels", "pagos"}

func Nueva(cfg Config, firma *plataforma.Firmante) (*Pasarela, error) {
	p := &Pasarela{
		cfg:      cfg,
		firma:    firma,
		acceso:   plataforma.NuevoClienteInterno(cfg.Acceso, "pasarela", firma),
		general:  nuevoLimitador(25, 150),     // 25 peticiones/s, ráfaga de 150 (la web carga muchos archivos)
		sensible: nuevoLimitador(10.0/60, 10), // entrar/registro: 10 por minuto por IP
		rutas:    map[string]*httputil.ReverseProxy{},
	}
	destinos := map[string]string{"acceso": cfg.Acceso, "mercado": cfg.Mercado, "reels": cfg.Reels, "pagos": cfg.Pagos}
	for _, n := range servicios {
		u, err := url.Parse(destinos[n])
		if err != nil || u.Host == "" {
			return nil, errors.New("URL inválida para el servicio " + n)
		}
		p.rutas[n] = proxy(u, "/api/"+n, false)
	}
	u, err := url.Parse(cfg.Nucleo)
	if err != nil || u.Host == "" {
		return nil, errors.New("URL inválida para el núcleo")
	}
	p.nucleo = proxy(u, "", true)
	return p, nil
}

// proxy reenvía a destino quitando quitar del inicio de la ruta.
func proxy(destino *url.URL, quitar string, esNucleo bool) *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			ruta := pr.In.URL.Path
			if quitar != "" {
				ruta = strings.TrimPrefix(strings.TrimPrefix(ruta, prefijo), quitar)
				if ruta == "" {
					ruta = "/"
				}
			}
			pr.Out.URL.Scheme = destino.Scheme
			pr.Out.URL.Host = destino.Host
			pr.Out.URL.Path = ruta
			pr.Out.URL.RawPath = ""
			pr.Out.Host = destino.Host
		},
		ModifyResponse: func(r *http.Response) error {
			// El núcleo responde CORS "*" (para desarrollo); en producción no hace
			// falta (misma origen) y abre la API a cualquier sitio: se quita.
			r.Header.Del("Access-Control-Allow-Origin")
			r.Header.Del("Access-Control-Allow-Headers")
			r.Header.Del("Access-Control-Allow-Methods")
			r.Header.Del("Server")
			return nil
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			var mb *http.MaxBytesError
			if errors.As(err, &mb) { // el cuerpo excedió el límite a media lectura
				plataforma.Responder(w, http.StatusRequestEntityTooLarge, plataforma.ErrHTTP{Msg: "El archivo o los datos son demasiado grandes", Clave: "muy_grande"})
				return
			}
			slog.Error("pasarela: servicio no disponible", "ruta", r.URL.Path, "err", err)
			plataforma.Responder(w, http.StatusBadGateway, plataforma.ErrHTTP{
				Msg: "El servicio no está disponible en este momento. Intenta en unos segundos.", Clave: "no_disponible"})
		},
		FlushInterval: -1,
	}
}

func (p *Pasarela) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// Nada que diga "soy de adentro" puede venir de afuera.
	for k := range r.Header {
		if strings.HasPrefix(strings.ToLower(k), "x-anaquel-") {
			r.Header.Del(k)
		}
	}
	p.cabecerasSeguridad(w.Header())
	ip := p.ipCliente(r)

	if !p.general.permitir(ip) {
		w.Header().Set("Retry-After", "5")
		plataforma.Responder(w, http.StatusTooManyRequests, plataforma.ErrHTTP{Msg: "Vas muy rápido. Espera unos segundos.", Clave: "limite"})
		return
	}

	ruta := strings.TrimPrefix(r.URL.Path, prefijo)
	if ruta == "/salud" {
		plataforma.Responder(w, 200, map[string]bool{"ok": true})
		return
	}
	servicio := ""
	for _, n := range servicios {
		if ruta == "/api/"+n || strings.HasPrefix(ruta, "/api/"+n+"/") {
			servicio = n
			break
		}
	}
	if servicio == "" {
		p.nucleo.ServeHTTP(w, r)
		return
	}

	// Las rutas de los servicios no aceptan rutas raras (//, /../, %2e).
	if strings.Contains(r.URL.Path, "//") || strings.Contains(r.URL.Path, "/..") || strings.Contains(strings.ToLower(r.URL.RawPath), "%2e") {
		plataforma.Responder(w, http.StatusBadRequest, plataforma.ErrHTTP{Msg: "Ruta inválida"})
		return
	}
	interna := strings.TrimPrefix(ruta, "/api/"+servicio)
	if strings.HasPrefix(interna, "/interno") {
		plataforma.Responder(w, http.StatusNotFound, plataforma.ErrHTTP{Msg: "Ruta no encontrada"})
		return
	}
	webhook := servicio == "pagos" && strings.HasPrefix(interna, "/webhooks/")
	if !webhook && !p.mismoOrigen(r) {
		plataforma.Responder(w, http.StatusForbidden, plataforma.ErrHTTP{Msg: "Petición bloqueada por seguridad (origen no permitido)", Clave: "origen"})
		return
	}
	if servicio == "acceso" && r.Method == http.MethodPost && (interna == "/entrar" || interna == "/registro") && !p.sensible.permitir(ip) {
		w.Header().Set("Retry-After", "60")
		plataforma.Responder(w, http.StatusTooManyRequests, plataforma.ErrHTTP{Msg: "Hubo demasiados intentos desde tu red. Espera un minuto.", Clave: "limite"})
		return
	}
	limite := int64(256 << 10)
	if r.Method == http.MethodPost && (strings.HasSuffix(interna, "/fotos") || interna == "/subir") {
		limite = 64 << 20 // fotos y videos
	}
	// Si el tamaño declarado ya excede el límite, se responde 413 sin leer nada.
	if r.ContentLength > limite {
		plataforma.Responder(w, http.StatusRequestEntityTooLarge, plataforma.ErrHTTP{Msg: "El archivo o los datos son demasiado grandes", Clave: "muy_grande"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, limite)

	id := plataforma.Identidad{Rol: plataforma.RolVisitante}
	if servicio != "acceso" && !webhook {
		var err error
		id, err = p.sesion(r.Context(), r)
		if err != nil {
			var e plataforma.ErrHTTP
			if errors.As(err, &e) && e.Codigo == http.StatusUnauthorized {
				http.SetCookie(w, &http.Cookie{Name: acceso.NombreCookie(p.cfg.CookieSegura), Path: "/", MaxAge: -1,
					HttpOnly: true, Secure: p.cfg.CookieSegura, SameSite: http.SameSiteStrictMode})
			}
			plataforma.Manejar(w, r, nil, err)
			return
		}
		// Protocolo de acceso: solo clientes verificados entran al mercado.
		if err := plataforma.ClienteVerificado(id); err != nil {
			plataforma.Manejar(w, r, nil, err)
			return
		}
		// Los servicios de negocio no necesitan (ni deben ver) la credencial de sesión.
		r.Header.Del("Cookie")
		r.Header.Del("Authorization")
	}
	id.IP = ip
	r.Header.Set(plataforma.CabeceraIdentidad, p.firma.Firmar(id))
	p.rutas[servicio].ServeHTTP(w, r)
}

// sesion pregunta al servicio de acceso de quién es la cookie.
func (p *Pasarela) sesion(ctx context.Context, r *http.Request) (plataforma.Identidad, error) {
	var id plataforma.Identidad
	// Navegador: cookie. App nativa: "Authorization: Bearer <token>".
	token := ""
	if c, err := r.Cookie(acceso.NombreCookie(p.cfg.CookieSegura)); err == nil {
		token = c.Value
	} else if t, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer "); ok {
		token = strings.TrimSpace(t)
	}
	if token == "" || len(token) > 128 {
		return id, plataforma.FalloClave(http.StatusUnauthorized, "sin_sesion", "Inicia sesión para continuar")
	}
	ctx, cancelar := context.WithTimeout(ctx, 5*time.Second)
	defer cancelar()
	err := p.acceso.Llamar(ctx, http.MethodPost, "/interno/sesion", map[string]string{"token": token}, &id)
	var e plataforma.ErrHTTP
	if errors.As(err, &e) {
		return id, err
	}
	if err != nil {
		slog.Error("pasarela: acceso no responde", "err", err)
		return id, plataforma.FalloClave(http.StatusBadGateway, "no_disponible", "No pudimos revisar tu sesión. Intenta en unos segundos.")
	}
	return id, nil
}

// mismoOrigen: defensa CSRF para métodos que cambian datos. Además de la
// cookie SameSite=Strict, el navegador debe declarar que la petición sale de
// esta misma página.
func (p *Pasarela) mismoOrigen(r *http.Request) bool {
	switch r.Method {
	case http.MethodGet, http.MethodHead, http.MethodOptions:
		return true
	}
	if sfs := r.Header.Get("Sec-Fetch-Site"); sfs != "" && sfs != "same-origin" && sfs != "none" {
		return false
	}
	origen := r.Header.Get("Origin")
	if origen == "" {
		return r.Header.Get("Sec-Fetch-Site") != "cross-site" // clientes que no son navegador
	}
	u, err := url.Parse(origen)
	if err != nil {
		return false
	}
	if strings.EqualFold(u.Host, r.Host) {
		return true
	}
	for _, o := range p.cfg.Origenes {
		if strings.EqualFold(o, origen) {
			return true
		}
	}
	return false
}

func (p *Pasarela) ipCliente(r *http.Request) string {
	if p.cfg.ConfiarProxy {
		// El último valor lo agregó nuestro proxy; los anteriores los pudo inventar el cliente.
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			partes := strings.Split(xff, ",")
			if ip := net.ParseIP(strings.TrimSpace(partes[len(partes)-1])); ip != nil {
				return ip.String()
			}
		}
		if ip := net.ParseIP(strings.TrimSpace(r.Header.Get("X-Real-IP"))); ip != nil {
			return ip.String()
		}
	}
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

// Política de contenido: solo scripts propios; imágenes también del mapa
// (OpenStreetMap). 'unsafe-inline' en estilos lo necesita Leaflet.
const csp = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
	"img-src 'self' data: blob: https://*.tile.openstreetmap.org; media-src 'self' blob:; " +
	"connect-src 'self'; font-src 'self'; worker-src 'self' blob:; object-src 'none'; " +
	"base-uri 'self'; form-action 'self'; frame-ancestors 'self'"

func (p *Pasarela) cabecerasSeguridad(h http.Header) {
	h.Set("Content-Security-Policy", csp)
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("X-Frame-Options", "SAMEORIGIN")
	h.Set("Referrer-Policy", "strict-origin-when-cross-origin")
	h.Set("Permissions-Policy", "geolocation=(self), camera=(self), microphone=(), payment=(), usb=(), interest-cohort=()")
	h.Set("Cross-Origin-Opener-Policy", "same-origin")
	h.Set("Cross-Origin-Resource-Policy", "same-origin")
	if p.cfg.HSTS {
		h.Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
	}
}
