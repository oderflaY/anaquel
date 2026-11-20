package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"
)

// Una oferta de la red llega al chat; el vendedor contraoferta; a quien compra
// le sale «Pagar»; paga (página de prueba, sin Stripe) y el chat lo muestra.
func TestOfertaEnChatContraofertaYPago(t *testing.T) {
	a := appDePrueba(t)
	lupita, surte := idDe(t, a, DemoEmail), idDe(t, a, "abarrotes.mayoreo@anaquel.mx")
	var producto int64
	if err := a.db.QueryRow("SELECT id FROM productos WHERE tienda_id = ? AND publicado = 1 AND nombre = 'Leche entera 1 L'", surte).Scan(&producto); err != nil {
		t.Fatal("falta la leche del mayorista:", err)
	}
	code, body := llamar(t, a, a.crearTrato, "POST", "/", lupita, map[string]any{"producto_id": producto, "cantidad": 48, "precio_c": 2000, "mensaje": "¿Me la dejas en 20?"}, nil)
	if code != 200 {
		t.Fatalf("trato: %d %s", code, body)
	}
	chat, _ := a.chatEntre(lupita, surte)
	id := map[string]string{"id": itoa(chat)}
	ver := func(quien int64) (Chat, MensajeChat) {
		code, body := llamar(t, a, a.verChat, "GET", "/", quien, nil, id)
		if code != 200 {
			t.Fatalf("chat: %d %s", code, body)
		}
		var c Chat
		json.Unmarshal(body, &c)
		for i := len(c.Mensajes) - 1; i >= 0; i-- {
			if c.Mensajes[i].Tipo == "oferta" {
				return c, c.Mensajes[i]
			}
		}
		t.Fatalf("no hay oferta en el chat: %s", body)
		return c, MensajeChat{}
	}
	_, of := ver(lupita)
	if of.Cantidad != 48 || of.PrecioC != 2000 || of.Texto != "¿Me la dejas en 20?" || !of.Vigente {
		t.Fatalf("oferta inicial mal: %+v", of)
	}
	if strings.Contains(strings.Join(of.Puedo, ","), "pagar") {
		t.Fatal("no se puede pagar tu propia propuesta antes de que la acepten")
	}
	_, ofV := ver(surte)
	if fmt.Sprint(ofV.Puedo) != "[aceptar contra cancelar]" {
		t.Fatalf("el vendedor debe poder aceptar/contraofertar/cancelar: %v", ofV.Puedo)
	}

	// Contraoferta del mayorista desde el chat.
	code, body = llamar(t, a, a.accionPedido, "POST", "/", surte, map[string]any{"precio_c": 2150},
		map[string]string{"id": itoa(chat), "msg": itoa(ofV.ID), "accion": "contra"})
	if code != 200 {
		t.Fatalf("contra: %d %s", code, body)
	}
	_, viejo := ver(lupita)
	if viejo.ID == of.ID || viejo.PrecioC != 2150 || viejo.Cantidad != 48 {
		t.Fatalf("la contraoferta debe ser una tarjeta nueva con $21.50: %+v", viejo)
	}
	if fmt.Sprint(viejo.Puedo) != "[pagar aceptar contra cancelar]" || viejo.PorPagarC != 48*2150 {
		t.Fatalf("a quien compra le debe salir Pagar: %v %d", viejo.Puedo, viejo.PorPagarC)
	}
	// La tarjeta vieja ya no tiene botones.
	c, _ := ver(lupita)
	for _, m := range c.Mensajes {
		if m.ID == of.ID && len(m.Puedo) > 0 {
			t.Fatalf("la oferta vieja no debe tener acciones: %v", m.Puedo)
		}
	}

	// Pagar = aceptar + cobro (sin Stripe: página de prueba).
	code, body = llamar(t, a, a.accionPedido, "POST", "/", lupita, nil, map[string]string{"id": itoa(chat), "msg": itoa(viejo.ID), "accion": "pagar"})
	if code != 200 {
		t.Fatalf("pagar: %d %s", code, body)
	}
	json.Unmarshal(body, &c)
	if c.Cobro == nil || c.Cobro.Modo != "prueba" || c.Cobro.MontoC != 48*2150 || !strings.Contains(c.Cobro.URL, "/api/cobros/") {
		t.Fatalf("cobro mal: %+v", c.Cobro)
	}
	var estado string
	a.db.QueryRow("SELECT estado FROM tratos WHERE id = (SELECT MAX(id) FROM tratos)").Scan(&estado)
	if estado != "aceptado" {
		t.Fatalf("pagar debe aceptar la propuesta, quedó %s", estado)
	}
	// Pagar dos veces reusa el mismo cobro.
	_, body2 := llamar(t, a, a.accionPedido, "POST", "/", lupita, nil, map[string]string{"id": itoa(chat), "msg": itoa(viejo.ID), "accion": "pagar"})
	var c2 Chat
	json.Unmarshal(body2, &c2)
	if c2.Cobro == nil || c2.Cobro.ID != c.Cobro.ID {
		t.Fatalf("debe reusar el cobro pendiente: %+v", c2.Cobro)
	}
	secreto := c.Cobro.URL[strings.Index(c.Cobro.URL, "s=")+2:]
	// Con secreto equivocado no pasa nada.
	code, _ = llamar(t, a, a.pruebaCobro, "POST", "/x?s=mal", 0, nil, map[string]string{"id": itoa(c.Cobro.ID)})
	if code != 404 {
		t.Fatalf("secreto malo debe dar 404, dio %d", code)
	}
	code, _ = llamar(t, a, a.pruebaCobro, "POST", "/x?s="+secreto, 0, nil, map[string]string{"id": itoa(c.Cobro.ID)})
	if code != 303 {
		t.Fatalf("pagar en la página de prueba debe redirigir, dio %d", code)
	}
	code, html := llamar(t, a, a.vueltaCobro, "GET", "/x?s="+secreto, 0, nil, map[string]string{"id": itoa(c.Cobro.ID)})
	if code != 200 || !strings.Contains(string(html), "Pago recibido") {
		t.Fatalf("vuelta: %d %s", code, html)
	}
	c, pagada := ver(surte)
	if !pagada.Pagado || fmt.Sprint(pagada.Puedo) != "[entregar]" {
		t.Fatalf("pagada: al vendedor solo le queda entregar: %+v", pagada)
	}
	if ultimo := c.Mensajes[len(c.Mensajes)-1]; ultimo.Tipo != "sistema" || !strings.Contains(ultimo.Texto, "pagó") {
		t.Fatalf("el chat debe decir que ya pagó: %+v", ultimo)
	}
	// Mapa: el mayorista aparece con su ubicación.
	code, body = llamar(t, a, a.mapa, "GET", "/", lupita, nil, nil)
	var m MapaRed
	json.Unmarshal(body, &m)
	if code != 200 || m.Yo == nil || len(m.Tiendas) < 5 {
		t.Fatalf("mapa: %d %d tiendas", code, len(m.Tiendas))
	}
	hay := false
	for _, p := range m.Tiendas {
		if p.ID == surte {
			hay = p.ChatID == chat && p.Lat != 0 && p.DistanciaKm != nil
		}
	}
	if !hay {
		t.Fatal("el mayorista debe salir en el mapa con su chat")
	}
}

func TestFirmaStripe(t *testing.T) {
	ahora := time.Unix(1_800_000_000, 0)
	cuerpo := []byte(`{"type":"checkout.session.completed"}`)
	m := hmac.New(sha256.New, []byte("whsec_x"))
	m.Write([]byte("1800000000."))
	m.Write(cuerpo)
	cab := "t=1800000000,v1=" + hex.EncodeToString(m.Sum(nil))
	if !firmaValida("whsec_x", cab, cuerpo, ahora) {
		t.Fatal("firma buena rechazada")
	}
	if firmaValida("whsec_y", cab, cuerpo, ahora) || firmaValida("whsec_x", cab, append(cuerpo, ' '), ahora) || firmaValida("whsec_x", cab, cuerpo, ahora.Add(10*time.Minute)) {
		t.Fatal("firma mala aceptada")
	}
}
