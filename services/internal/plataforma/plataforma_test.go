package plataforma

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func firmante(t *testing.T) *Firmante {
	f, err := NuevoFirmante(bytes.Repeat([]byte("k"), 32))
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func TestIdentidadFirmadaIdaYVuelta(t *testing.T) {
	f := firmante(t)
	id := Identidad{Cuenta: 7, Rol: RolCliente, Estado: EstadoVerificada, Vende: true, Nombre: "Doña Lupita"}
	got, err := f.Verificar(f.Firmar(id))
	if err != nil || got.Cuenta != 7 || !got.Vende || got.Nombre != "Doña Lupita" {
		t.Fatalf("identidad no sobrevivió: %+v %v", got, err)
	}
}

func TestIdentidadAlterada(t *testing.T) {
	f := firmante(t)
	v := f.Firmar(Identidad{Cuenta: 7, Rol: RolCliente})
	d, firma, _ := strings.Cut(v, ".")
	// Cambiar un solo carácter de los datos o de la firma debe invalidarla.
	for _, malo := range []string{
		d[:len(d)-1] + "A" + "." + firma,
		d + "." + firma[:len(firma)-1] + "A",
		d + ".",
		"." + firma,
		"basura",
		"",
	} {
		if _, err := f.Verificar(malo); err == nil {
			t.Errorf("aceptó identidad alterada: %q", malo)
		}
	}
	otro, _ := NuevoFirmante(bytes.Repeat([]byte("x"), 32))
	if _, err := otro.Verificar(v); err == nil {
		t.Error("aceptó identidad firmada con otra clave")
	}
}

func TestIdentidadVencida(t *testing.T) {
	f := firmante(t)
	id := Identidad{Cuenta: 1, Rol: RolCliente, Expira: time.Now().Add(-time.Minute).Unix()}
	// Firmar pone su propio vencimiento; se fabrica una vencida a mano.
	v := f.Firmar(id)
	if _, err := f.Verificar(v); err != nil {
		t.Fatal("una recién firmada debe ser válida")
	}
	j := `{"c":1,"r":"cliente","x":1}`
	d := b64.EncodeToString([]byte(j))
	if _, err := f.Verificar(d + "." + f.mac(d)); err == nil {
		t.Error("aceptó una identidad vencida")
	}
}

func TestClaveCorta(t *testing.T) {
	if _, err := NuevoFirmante([]byte("corta")); err == nil {
		t.Error("aceptó una clave interna corta")
	}
}

func TestReglas(t *testing.T) {
	casos := []struct {
		id     Identidad
		regla  Regla
		pasa   bool
		nombre string
	}{
		{Identidad{Cuenta: 1, Rol: RolCliente, Estado: EstadoVerificada}, ClienteVerificado, true, "cliente verificado"},
		{Identidad{Cuenta: 1, Rol: RolCliente, Estado: EstadoPendiente}, ClienteVerificado, false, "pendiente"},
		{Identidad{Cuenta: 1, Rol: RolCliente, Estado: EstadoSuspendida}, ClienteVerificado, false, "suspendida"},
		{Identidad{Rol: RolVisitante}, ClienteVerificado, false, "visitante"},
		{Identidad{Rol: RolServicio, Nombre: "pagos"}, ClienteVerificado, false, "servicio como cliente"},
		{Identidad{Cuenta: 1, Rol: RolCliente, Estado: EstadoVerificada}, Vendedor, false, "cliente sin permiso de venta"},
		{Identidad{Cuenta: 1, Rol: RolCliente, Estado: EstadoVerificada, Vende: true}, Vendedor, true, "vendedor"},
		{Identidad{Cuenta: 1, Rol: RolCliente, Estado: EstadoVerificada}, Admin, false, "cliente como admin"},
		{Identidad{Cuenta: 1, Rol: RolAdmin, Estado: EstadoVerificada}, Admin, true, "admin"},
		{Identidad{Rol: RolServicio, Nombre: "reels"}, Servicio("pagos"), false, "otro servicio"},
		{Identidad{Rol: RolServicio, Nombre: "pagos"}, Servicio("pagos"), true, "servicio permitido"},
		{Identidad{Cuenta: 1, Rol: RolCliente, Nombre: "pagos"}, Servicio("pagos"), false, "cliente que se llama pagos"},
	}
	for _, c := range casos {
		if err := c.regla(c.id); (err == nil) != c.pasa {
			t.Errorf("%s: esperaba pasa=%v, err=%v", c.nombre, c.pasa, err)
		}
	}
}

func TestExigirSinCabecera(t *testing.T) {
	f := firmante(t)
	h := f.Exigir(ClienteVerificado, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) })
	rec := httptest.NewRecorder()
	h(rec, httptest.NewRequest("GET", "/", nil))
	if rec.Code != 401 {
		t.Errorf("sin identidad debe ser 401, fue %d", rec.Code)
	}
	rec = httptest.NewRecorder()
	r := httptest.NewRequest("GET", "/", nil)
	r.Header.Set(CabeceraIdentidad, `eyJjIjoxLCJyIjoiYWRtaW4iLCJlIjoidmVyaWZpY2FkYSIsIngiOjk5OTk5OTk5OTl9.firmafalsa`)
	h(rec, r)
	if rec.Code != 401 {
		t.Errorf("identidad falsificada debe ser 401, fue %d", rec.Code)
	}
}

func TestCifradoAutenticado(t *testing.T) {
	c, err := NuevoCifrador(bytes.Repeat([]byte("d"), 32))
	if err != nil {
		t.Fatal(err)
	}
	x, _ := c.Cifrar("cuentas.telefono", "6181234567")
	if bytes.Contains(x, []byte("6181234567")) {
		t.Fatal("el texto aparece en claro")
	}
	if v, err := c.Descifrar("cuentas.telefono", x); err != nil || v != "6181234567" {
		t.Fatalf("no descifró: %v", err)
	}
	if _, err := c.Descifrar("cuentas.email", x); err == nil {
		t.Error("descifró en otra columna (contexto no autenticado)")
	}
	x[len(x)-1] ^= 1
	if _, err := c.Descifrar("cuentas.telefono", x); err == nil {
		t.Error("aceptó un dato cifrado alterado")
	}
	y, _ := c.Cifrar("cuentas.telefono", "6181234567")
	z, _ := c.Cifrar("cuentas.telefono", "6181234567")
	if bytes.Equal(y, z) {
		t.Error("el mismo valor cifra igual dos veces (nonce repetido)")
	}
}

func TestLeerJSONEstricto(t *testing.T) {
	var d struct{ A int }
	for _, cuerpo := range []string{`{"A":1,"B":2}`, `{"A":1}{"A":2}`, `no json`, `{"A":"texto"}`} {
		r := httptest.NewRequest("POST", "/", strings.NewReader(cuerpo))
		if err := LeerJSON(httptest.NewRecorder(), r, &d); err == nil {
			t.Errorf("aceptó %q", cuerpo)
		}
	}
	grande := `{"A":1,"x":"` + strings.Repeat("a", 70<<10) + `"}`
	if err := LeerJSON(httptest.NewRecorder(), httptest.NewRequest("POST", "/", strings.NewReader(grande)), &d); err == nil {
		t.Error("aceptó un cuerpo de más de 64 KB")
	}
}

func TestErrorInternoNoSeFiltra(t *testing.T) {
	rec := httptest.NewRecorder()
	Manejar(rec, httptest.NewRequest("GET", "/", nil), nil, errFalso("pq: password authentication failed for user secreto"))
	if rec.Code != 500 || strings.Contains(rec.Body.String(), "secreto") {
		t.Errorf("filtró el error interno: %d %s", rec.Code, rec.Body.String())
	}
}

type errFalso string

func (e errFalso) Error() string { return string(e) }
