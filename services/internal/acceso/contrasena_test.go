package acceso

import (
	"strings"
	"testing"

	"golang.org/x/crypto/bcrypt"
)

func TestArgon2id(t *testing.T) {
	h, err := hashArgon("una frase bastante larga")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(h, "$argon2id$v=19$m=19456,t=2,p=1$") {
		t.Fatalf("formato inesperado: %s", h)
	}
	if ok, rehash := verificarPass(h, "una frase bastante larga"); !ok || rehash {
		t.Error("no verificó la contraseña correcta")
	}
	if ok, _ := verificarPass(h, "una frase bastante largA"); ok {
		t.Error("aceptó una contraseña distinta")
	}
	h2, _ := hashArgon("una frase bastante larga")
	if h == h2 {
		t.Error("dos hashes iguales: falta sal aleatoria")
	}
}

func TestBcryptImportadoSeRecifra(t *testing.T) {
	b, _ := bcrypt.GenerateFromPassword([]byte("demo1234"), bcrypt.MinCost)
	ok, rehash := verificarPass(string(b), "demo1234")
	if !ok || !rehash {
		t.Errorf("bcrypt importado: ok=%v rehash=%v", ok, rehash)
	}
}

func TestHashesMalformados(t *testing.T) {
	for _, h := range []string{"", "texto", "$argon2id$v=19$m=999999999,t=1,p=1$c2Fs$aGFzaA", "$argon2id$v=19$m=19456,t=2,p=0$c2Fs$aGFzaA",
		"$argon2id$v=19$m=19456,t=99,p=1$c2Fs$aGFzaA", "$argon2id$v=19$m=19456,t=2,p=1$$"} {
		if ok, _ := verificarPass(h, "x"); ok {
			t.Errorf("aceptó hash malformado %q", h)
		}
	}
}

func TestPoliticaContrasena(t *testing.T) {
	malas := map[string]string{
		"corta1":                   "corta",
		"contraseña123":            "común",
		"1234567890":               "común",
		"aaaaaaaaaaaa":             "repetida",
		"lupita.tienda2026":        "contiene el correo",
		strings.Repeat("Ab1-", 40): "larga",
	}
	for p, por := range malas {
		if validarPass(p, "lupita.tienda@anaquel.mx") == nil {
			t.Errorf("aceptó %q (%s)", p, por)
		}
	}
	for _, p := range []string{"Tienda de la esquina 98", "frase-larga-con-numeros-42"} {
		if err := validarPass(p, "demo@anaquel.mx"); err != nil {
			t.Errorf("rechazó %q: %v", p, err)
		}
	}
}

func TestCorreoNormalizado(t *testing.T) {
	if e, ok := normalizarEmail("  Demo@Anaquel.MX "); !ok || e != "demo@anaquel.mx" {
		t.Errorf("no normalizó: %q", e)
	}
	for _, e := range []string{"", "sin-arroba", "Nombre <a@b.mx>", "a@b.mx\r\nBcc: x@y.mx", strings.Repeat("a", 250) + "@b.mx"} {
		if _, ok := normalizarEmail(e); ok {
			t.Errorf("aceptó %q", e)
		}
	}
}

func TestTextoLimpio(t *testing.T) {
	s, _ := textoLimpio("Tienda‮​OK\x00\x07", 80)
	if s != "TiendaOK" {
		t.Errorf("no quitó caracteres de control / bidi: %q", s)
	}
}

func TestCodigoInvitacion(t *testing.T) {
	vistos := map[string]bool{}
	for range 2000 {
		c := codigoInvitacion()
		if len(c) != 10 || strings.ContainsAny(c, "01ILO") {
			t.Fatalf("código inválido %q", c)
		}
		if vistos[c] {
			t.Fatal("código repetido")
		}
		vistos[c] = true
	}
	if normalizarCodigo(" k7m2q-9xh4t ") != "K7M2Q9XH4T" {
		t.Error("no normalizó el código")
	}
}

func TestZonaDurango(t *testing.T) {
	if !ZonaDurango.Contiene(24.0277, -104.6532) {
		t.Error("Durango capital fuera de zona")
	}
	if ZonaDurango.Contiene(19.4326, -99.1332) {
		t.Error("CDMX dentro de zona")
	}
}
