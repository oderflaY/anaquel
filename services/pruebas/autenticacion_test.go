package pruebas

import (
	"context"
	"crypto/sha256"
	"sort"
	"strings"
	"testing"
	"time"
)

func TestAutenticacion(t *testing.T) {
	t.Run("credenciales incorrectas: mismo mensaje exista o no el correo", func(t *testing.T) {
		a := nuevo(t).entrar("mayoreo@anaquel.mx", "incorrecta-123")
		b := nuevo(t).entrar("nadie-existe@anaquel.mx", "incorrecta-123")
		esperar(t, a, 401)
		esperar(t, b, 401)
		if a.json()["error"] != b.json()["error"] {
			t.Errorf("mensajes distintos permiten saber qué correos existen: %q vs %q", a.json()["error"], b.json()["error"])
		}
	})

	t.Run("tiempo de respuesta no delata si el correo existe", func(t *testing.T) {
		medir := func(email string) time.Duration {
			var ts []time.Duration
			for range 5 {
				c := nuevo(t)
				ini := time.Now()
				c.entrar(email, "incorrecta-123")
				ts = append(ts, time.Since(ini))
			}
			sort.Slice(ts, func(i, j int) bool { return ts[i] < ts[j] })
			return ts[2]
		}
		existe, noExiste := medir("textiles@anaquel.mx"), medir("x-"+time.Now().Format("150405")+"@anaquel.mx")
		r := float64(existe) / float64(noExiste)
		t.Logf("mediana existe=%v no existe=%v (razón %.2f)", existe, noExiste, r)
		if r < 0.5 || r > 2 {
			t.Errorf("diferencia de tiempo explotable: razón %.2f", r)
		}
	})

	t.Run("fuerza bruta: bloqueo progresivo por correo", func(t *testing.T) {
		var ultima resp
		for i := range 6 {
			ultima = nuevo(t).entrar("imprenta@anaquel.mx", "intento-"+string(rune('a'+i)))
		}
		esperar(t, ultima, 429)
		// Ni con la contraseña correcta, mientras dure el bloqueo.
		esperar(t, nuevo(t).entrar("imprenta@anaquel.mx", "demo1234"), 429)
		// Un correo que no existe se bloquea igual (no delata si existe).
		for range 6 {
			ultima = nuevo(t).entrar("fantasma@anaquel.mx", "x-123456789")
		}
		esperar(t, ultima, 429)
	})

	t.Run("fuerza bruta: límite por IP en la pasarela", func(t *testing.T) {
		c := nuevo(t)
		bloqueado := false
		for i := range 14 {
			if c.entrar("correo"+string(rune('a'+i))+"@x.mx", "x").codigo == 429 {
				bloqueado = true
			}
		}
		if !bloqueado {
			t.Error("14 intentos desde la misma IP en segundos no se bloquearon")
		}
	})

	t.Run("inyección SQL en el login", func(t *testing.T) {
		for _, e := range []string{"' OR '1'='1", "admin@anaquel.mx' --", `demo@anaquel.mx"; DROP TABLE cuentas; --`} {
			r := nuevo(t).entrar(e, "' OR '1'='1")
			if r.codigo == 200 || r.codigo >= 500 {
				t.Errorf("%q → %s", e, r)
			}
		}
	})

	t.Run("cookie de sesión HttpOnly y SameSite=Strict", func(t *testing.T) {
		r := nuevo(t).entrar("mayoreo@anaquel.mx", "demo1234")
		sc := r.cab.Get("Set-Cookie")
		for _, x := range []string{"HttpOnly", "SameSite=Strict", "Path=/"} {
			if !strings.Contains(sc, x) {
				t.Errorf("falta %s en %q", x, sc)
			}
		}
		if strings.Contains(string(r.cuerpo), "token") {
			t.Error("el token viaja en el cuerpo (lo leería JavaScript)")
		}
	})

	t.Run("fijación de sesión: el token cambia y el anterior muere", func(t *testing.T) {
		c := nuevo(t).entrarOk("mayoreo@anaquel.mx")
		viejo := c.token()
		c.entrarOk("mayoreo@anaquel.mx")
		if c.token() == viejo || c.token() == "" {
			t.Fatal("el token no cambió al volver a entrar")
		}
		x := nuevo(t)
		x.ponerToken(viejo)
		esperar(t, x.pedir("GET", "/acceso/yo", nil), 401)
		// Un token inventado por el atacante nunca se acepta.
		y := nuevo(t)
		y.ponerToken("token-elegido-por-el-atacante")
		y.entrarOk("mayoreo@anaquel.mx")
		if y.token() == "token-elegido-por-el-atacante" {
			t.Error("se conservó el token impuesto por el atacante")
		}
	})

	t.Run("salir invalida la sesión en el servidor", func(t *testing.T) {
		c := nuevo(t).entrarOk("ropa@anaquel.mx")
		tok := c.token()
		esperar(t, c.pedir("POST", "/acceso/salir", map[string]any{}), 200)
		x := nuevo(t)
		x.ponerToken(tok)
		esperar(t, x.pedir("GET", "/mercado/publicaciones", nil), 401)
	})

	t.Run("tokens falsos o malformados", func(t *testing.T) {
		for _, tok := range []string{"", "a", strings.Repeat("f", 64), strings.Repeat("x", 5000), "' OR 1=1 --", "../../etc/passwd"} {
			c := nuevo(t)
			c.ponerToken(tok)
			if r := c.pedir("GET", "/mercado/publicaciones", nil); r.codigo != 401 && r.codigo != 400 && r.codigo != 431 {
				t.Errorf("token %q → %s", tok[:min(len(tok), 20)], r)
			}
		}
	})

	t.Run("cambiar contraseña cierra las demás sesiones", func(t *testing.T) {
		a := nuevo(t).entrarOk("fletes@anaquel.mx")
		b := nuevo(t).entrarOk("fletes@anaquel.mx")
		esperar(t, a.pedir("PUT", "/acceso/contrasena", map[string]string{"actual": "demo1234", "nueva": "123"}), 400)
		esperar(t, a.pedir("PUT", "/acceso/contrasena", map[string]string{"actual": "mala-mala-mala", "nueva": "Camioneta Segura 2026!"}), 400)
		esperar(t, a.pedir("PUT", "/acceso/contrasena", map[string]string{"actual": "demo1234", "nueva": "Camioneta Segura 2026!"}), 200)
		esperar(t, a.pedir("GET", "/acceso/yo", nil), 200)
		esperar(t, b.pedir("GET", "/acceso/yo", nil), 401)
		esperar(t, nuevo(t).entrar("fletes@anaquel.mx", "demo1234"), 401)
		esperar(t, nuevo(t).entrar("fletes@anaquel.mx", "Camioneta Segura 2026!"), 200)
	})

	t.Run("en la base: contraseñas argon2id, tokens solo como hash, datos cifrados", func(t *testing.T) {
		db := bd(t, "acceso")
		ctx := context.Background()
		var malos int
		_ = db.QueryRow(ctx, "SELECT COUNT(*) FROM cuentas WHERE pass_hash NOT LIKE '$argon2id$%'").Scan(&malos)
		if malos > 0 {
			t.Errorf("%d contraseñas sin argon2id", malos)
		}
		_ = db.QueryRow(ctx, "SELECT COUNT(*) FROM cuentas WHERE position('@' in convert_from(email_cifrado, 'SQL_ASCII')) > 0 OR position('anaquel' in encode(email_cifrado, 'escape')) > 0").Scan(&malos)
		if malos > 0 {
			t.Errorf("%d correos legibles en la base", malos)
		}
		c := nuevo(t).entrarOk("ropa@anaquel.mx")
		var enClaro, conHash int
		_ = db.QueryRow(ctx, "SELECT COUNT(*) FROM sesiones WHERE token_hash = convert_to($1, 'UTF8')", c.token()).Scan(&enClaro)
		h := sha256.Sum256([]byte(c.token()))
		_ = db.QueryRow(ctx, "SELECT COUNT(*) FROM sesiones WHERE token_hash = $1", h[:]).Scan(&conHash)
		if enClaro != 0 || conHash != 1 {
			t.Errorf("token en claro=%d, como hash=%d", enClaro, conHash)
		}
		var tel string
		_ = db.QueryRow(ctx, "SELECT encode(telefono_cifrado, 'escape') FROM cuentas WHERE slug = 'boutique-alondra'").Scan(&tel)
		if strings.Contains(tel, "6182345678") {
			t.Error("el teléfono está en claro")
		}
	})

	t.Run("la bitácora de seguridad no se puede alterar", func(t *testing.T) {
		db := bd(t, "acceso")
		ctx := context.Background()
		var n int
		_ = db.QueryRow(ctx, "SELECT COUNT(*) FROM bitacora WHERE evento = 'entrar_fallido'").Scan(&n)
		if n == 0 {
			t.Error("los intentos fallidos no quedan en la bitácora")
		}
		for _, sql := range []string{"UPDATE bitacora SET evento = 'x'", "DELETE FROM bitacora", "TRUNCATE bitacora"} {
			if _, err := db.Exec(ctx, sql); err == nil {
				t.Errorf("se permitió: %s", sql)
			}
		}
	})
}
