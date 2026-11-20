package pagos

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

const secreto = "secreto-de-webhook-de-prueba-123456"

func aviso(dataID, reqID string, ts int64, sec string) *http.Request {
	r := httptest.NewRequest("POST", "/webhooks/mercadopago?data.id="+dataID+"&type=order", strings.NewReader(`{"data":{"id":"`+dataID+`"}}`))
	t := strconv.FormatInt(ts, 10)
	r.Header.Set("x-request-id", reqID)
	r.Header.Set("x-signature", "ts="+t+",v1="+firmaHMAC(sec, dataID, reqID, t))
	return r
}

func TestWebhookFirmaValida(t *testing.T) {
	m := &MercadoPago{SecretoWebhook: secreto}
	ref, ev, err := m.Webhook(aviso("ORD01ABC", "req-1", time.Now().Unix(), secreto), nil)
	if err != nil || ref != "ORD01ABC" || ev != "req-1" {
		t.Fatalf("%q %q %v", ref, ev, err)
	}
	// También en milisegundos.
	if _, _, err := m.Webhook(aviso("ORD01ABC", "req-2", time.Now().UnixMilli(), secreto), nil); err != nil {
		t.Errorf("ts en ms: %v", err)
	}
}

func TestWebhookAtaques(t *testing.T) {
	m := &MercadoPago{SecretoWebhook: secreto}
	ahora := time.Now().Unix()
	casos := map[string]*http.Request{
		"otra clave":       aviso("ORD1", "r", ahora, "otro-secreto-cualquiera-1234567890"),
		"aviso viejo":      aviso("ORD1", "r", ahora-3600, secreto),
		"aviso del futuro": aviso("ORD1", "r", ahora+3600, secreto),
	}
	// Firma válida pero para otro id (se intenta cambiar la orden notificada).
	cambiada := aviso("ORD1", "r", ahora, secreto)
	cambiada.URL.RawQuery = "data.id=ORD2&type=order"
	casos["id cambiado"] = cambiada
	sinFirma := httptest.NewRequest("POST", "/webhooks/mercadopago?data.id=ORD1", nil)
	sinFirma.Header.Set("x-request-id", "r")
	casos["sin firma"] = sinFirma
	for n, r := range casos {
		if _, _, err := m.Webhook(r, nil); err == nil {
			t.Errorf("aceptó webhook: %s", n)
		}
	}
	if _, _, err := (&MercadoPago{}).Webhook(aviso("ORD1", "r", ahora, ""), nil); err == nil {
		t.Error("sin secreto configurado debe rechazar todo")
	}
}

func TestCentavosSinFlotantes(t *testing.T) {
	casos := map[string]int64{"150.00": 15000, "0.10": 10, "123.4": 12340, "99": 9900, "960": 96000}
	for s, c := range casos {
		if v, err := centavos(s); err != nil || v != c {
			t.Errorf("%s → %d (%v), esperaba %d", s, v, err, c)
		}
	}
	for _, s := range []string{"", "-5.00", "1.234", "abc", "1e5", "99999999999.00"} {
		if _, err := centavos(s); err == nil {
			t.Errorf("aceptó %q", s)
		}
	}
	if pesos(96000) != "960.00" || pesos(5) != "0.05" {
		t.Error("pesos() mal")
	}
}

// Cliente de Mercado Pago contra un servidor falso: revisa lo que mandamos.
func TestMercadoPagoCobrarConsultar(t *testing.T) {
	var cuerpo map[string]any
	var idem, auth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "POST" && r.URL.Path == "/v1/orders":
			idem, auth = r.Header.Get("X-Idempotency-Key"), r.Header.Get("Authorization")
			_ = json.NewDecoder(r.Body).Decode(&cuerpo)
			w.Write([]byte(`{"id":"ORD01X","status":"created"}`))
		case r.Method == "GET" && r.URL.Path == "/v1/orders/ORD01X":
			w.Write([]byte(`{"id":"ORD01X","status":"processed","status_detail":"accredited","external_reference":"cobro-1",
				"config":{"point":{"terminal_id":"PAX_A910__SMARTPOS1"}},"transactions":{"payments":[{"amount":"960.00"}]}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	m := &MercadoPago{Token: "TEST-token", Base: srv.URL, HTTP: srv.Client()}
	ref, err := m.Cobrar(context.Background(), Solicitud{CobroID: "cobro-1", Dispositivo: "PAX_A910__SMARTPOS1", MontoC: 96000, Descripcion: "Gorras"})
	if err != nil || ref != "ORD01X" {
		t.Fatal(ref, err)
	}
	if idem != "cobro-1" || auth != "Bearer TEST-token" || cuerpo["type"] != "point" {
		t.Errorf("petición mal formada: idem=%q auth=%q tipo=%v", idem, auth, cuerpo["type"])
	}
	pagos := cuerpo["transactions"].(map[string]any)["payments"].([]any)
	if pagos[0].(map[string]any)["amount"] != "960.00" {
		t.Errorf("monto enviado: %v", pagos[0])
	}
	k, err := m.Consultar(context.Background(), "ORD01X")
	if err != nil || k.Estado != EstadoAprobado || k.MontoC != 96000 || k.Dispositivo != "PAX_A910__SMARTPOS1" || k.Referencia != "cobro-1" {
		t.Errorf("consulta: %+v %v", k, err)
	}
	// Una referencia con caracteres de ruta no puede salir de /v1/orders/.
	if _, err := m.Consultar(context.Background(), "../../users/me"); err == nil {
		t.Error("la referencia inyectó una ruta")
	}
}
