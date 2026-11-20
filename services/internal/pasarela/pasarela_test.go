package pasarela

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"anaquel/servicios/internal/plataforma"
)

// montaje: pasarela con servicios falsos que reportan lo que reciben.
type recibido struct {
	ruta, identidad, cookie, origen string
}

func montar(t *testing.T, cfg Config) (*Pasarela, *[]recibido) {
	var log []recibido
	falso := func(nombre string) *httptest.Server {
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			log = append(log, recibido{nombre + r.URL.Path, r.Header.Get(plataforma.CabeceraIdentidad), r.Header.Get("Cookie"), r.Header.Get("X-Anaquel-Otro")})
			w.Header().Set("Access-Control-Allow-Origin", "*")
			w.Write([]byte(`{}`))
		}))
	}
	f, _ := plataforma.NuevoFirmante(bytes.Repeat([]byte("k"), 32))
	acceso := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(b), "token-bueno") {
			plataforma.Responder(w, 401, plataforma.ErrHTTP{Msg: "no", Clave: "sin_sesion"})
			return
		}
		estado := "verificada"
		if strings.Contains(string(b), "pendiente") {
			estado = "pendiente"
		}
		plataforma.Responder(w, 200, plataforma.Identidad{Cuenta: 5, Rol: "cliente", Estado: estado})
	}))
	t.Cleanup(acceso.Close)
	n, m, re, pa := falso("nucleo"), falso("mercado"), falso("reels"), falso("pagos")
	t.Cleanup(func() { n.Close(); m.Close(); re.Close(); pa.Close() })
	cfg.Nucleo, cfg.Acceso, cfg.Mercado, cfg.Reels, cfg.Pagos = n.URL, acceso.URL, m.URL, re.URL, pa.URL
	cfg.CookieSegura = false
	p, err := Nueva(cfg, f)
	if err != nil {
		t.Fatal(err)
	}
	return p, &log
}

func pedir(p *Pasarela, metodo, ruta string, cab map[string]string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(metodo, ruta, strings.NewReader("{}"))
	r.RemoteAddr = "203.0.113.9:5555"
	for k, v := range cab {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	p.ServeHTTP(w, r)
	return w
}

func TestCabecerasDeSeguridad(t *testing.T) {
	p, _ := montar(t, Config{})
	w := pedir(p, "GET", "/anaquel/", nil)
	for _, h := range []string{"Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Permissions-Policy"} {
		if w.Header().Get(h) == "" {
			t.Errorf("falta la cabecera %s", h)
		}
	}
	if w.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Error("la pasarela dejó pasar CORS * del núcleo")
	}
}

func TestSinSesionNoPasa(t *testing.T) {
	p, log := montar(t, Config{})
	for _, ruta := range []string{"/anaquel/api/mercado/publicaciones", "/anaquel/api/reels/", "/anaquel/api/pagos/terminales"} {
		if w := pedir(p, "GET", ruta, nil); w.Code != 401 {
			t.Errorf("%s sin sesión: %d", ruta, w.Code)
		}
	}
	if len(*log) != 0 {
		t.Errorf("llegó al servicio sin sesión: %+v", *log)
	}
}

func TestIdentidadFalsificadaSeBorra(t *testing.T) {
	p, log := montar(t, Config{})
	// Alguien manda su propia identidad "de admin" y una cookie válida.
	w := pedir(p, "GET", "/anaquel/api/mercado/publicaciones", map[string]string{
		plataforma.CabeceraIdentidad: "eyJyIjoiYWRtaW4ifQ.falsa", "X-Anaquel-Otro": "x", "Cookie": "anaquel_mercado=token-bueno",
	})
	if w.Code != 200 || len(*log) != 1 {
		t.Fatalf("esperaba pasar con la cookie: %d %v", w.Code, *log)
	}
	r := (*log)[0]
	if r.origen != "" || strings.Contains(r.identidad, "falsa") || r.identidad == "" {
		t.Errorf("la identidad falsa llegó al servicio: %+v", r)
	}
	if r.cookie != "" {
		t.Error("la cookie de sesión llegó al servicio de negocio")
	}
}

func TestCuentaPendienteNoEntra(t *testing.T) {
	p, log := montar(t, Config{})
	w := pedir(p, "GET", "/anaquel/api/mercado/publicaciones", map[string]string{"Cookie": "anaquel_mercado=token-bueno-pendiente"})
	if w.Code != 403 || !strings.Contains(w.Body.String(), "en_revision") || len(*log) != 0 {
		t.Errorf("cuenta pendiente: %d %s", w.Code, w.Body.String())
	}
}

func TestRutasInternasBloqueadas(t *testing.T) {
	p, log := montar(t, Config{})
	for _, ruta := range []string{"/anaquel/api/mercado/interno/ordenes/1", "/api/acceso/interno/sesion", "/anaquel/api/pagos/interno/x"} {
		if w := pedir(p, "GET", ruta, map[string]string{"Cookie": "anaquel_mercado=token-bueno"}); w.Code != 404 {
			t.Errorf("%s: %d", ruta, w.Code)
		}
	}
	for _, ruta := range []string{"/anaquel/api/mercado//publicaciones", "/anaquel/api/mercado/fotos/..%2f..%2fetc"} {
		r := httptest.NewRequest("GET", ruta, nil)
		w := httptest.NewRecorder()
		p.ServeHTTP(w, r)
		if w.Code != 400 && w.Code != 404 {
			t.Errorf("%s: %d", ruta, w.Code)
		}
	}
	if len(*log) != 0 {
		t.Errorf("rutas internas llegaron al servicio: %+v", *log)
	}
}

func TestCSRF(t *testing.T) {
	p, log := montar(t, Config{})
	ataques := []map[string]string{
		{"Origin": "https://sitio-malo.com", "Cookie": "anaquel_mercado=token-bueno"},
		{"Sec-Fetch-Site": "cross-site", "Cookie": "anaquel_mercado=token-bueno"},
		{"Sec-Fetch-Site": "same-site", "Origin": "https://otro.example.com", "Cookie": "anaquel_mercado=token-bueno"},
	}
	for _, a := range ataques {
		if w := pedir(p, "POST", "/anaquel/api/mercado/ordenes", a); w.Code != 403 {
			t.Errorf("CSRF %v: %d", a, w.Code)
		}
	}
	if w := pedir(p, "POST", "/anaquel/api/acceso/entrar", map[string]string{"Origin": "https://sitio-malo.com"}); w.Code != 403 {
		t.Errorf("login CSRF: %d", w.Code)
	}
	ok := pedir(p, "POST", "/anaquel/api/mercado/ordenes", map[string]string{"Origin": "http://example.com", "Sec-Fetch-Site": "same-origin", "Cookie": "anaquel_mercado=token-bueno"})
	if ok.Code != 200 {
		t.Errorf("petición legítima bloqueada: %d", ok.Code)
	}
	if len(*log) != 1 {
		t.Errorf("pasaron %d peticiones, esperaba 1", len(*log))
	}
}

func TestXForwardedForIgnoradoSinProxy(t *testing.T) {
	p, _ := montar(t, Config{})
	r := httptest.NewRequest("GET", "/", nil)
	r.RemoteAddr = "203.0.113.9:5555"
	r.Header.Set("X-Forwarded-For", "1.2.3.4")
	if ip := p.ipCliente(r); ip != "203.0.113.9" {
		t.Errorf("sin CONFIAR_PROXY usó X-Forwarded-For: %s", ip)
	}
	p2, _ := montar(t, Config{ConfiarProxy: true})
	r.Header.Set("X-Forwarded-For", "6.6.6.6, 198.51.100.7")
	if ip := p2.ipCliente(r); ip != "198.51.100.7" {
		t.Errorf("con proxy debe tomar el último valor (el que agregó el proxy): %s", ip)
	}
}

func TestLimiteDeLogin(t *testing.T) {
	p, _ := montar(t, Config{})
	bloqueos := 0
	for range 15 {
		if pedir(p, "POST", "/anaquel/api/acceso/entrar", nil).Code == 429 {
			bloqueos++
		}
	}
	if bloqueos < 5 {
		t.Errorf("15 intentos de login seguidos solo se bloquearon %d veces", bloqueos)
	}
}

func TestLimitador(t *testing.T) {
	l := nuevoLimitador(1, 3)
	n := 0
	for range 10 {
		if l.permitir("ip") {
			n++
		}
	}
	if n != 3 {
		t.Errorf("ráfaga de 3 dejó pasar %d", n)
	}
	if !l.permitir("otra-ip") {
		t.Error("una IP no debe afectar a otra")
	}
}
