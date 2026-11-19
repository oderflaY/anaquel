package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// llamar hace una petición a un handler como si viniera de esa tienda.
func llamar(t *testing.T, a *App, h http.HandlerFunc, metodo, ruta string, tienda int64, cuerpo any, vals map[string]string) (int, []byte) {
	t.Helper()
	var b []byte
	if cuerpo != nil {
		b, _ = json.Marshal(cuerpo)
	}
	r := httptest.NewRequest(metodo, ruta, bytes.NewReader(b))
	for k, v := range vals {
		r.SetPathValue(k, v)
	}
	r = r.WithContext(context.WithValue(r.Context(), ctxTienda{}, tienda))
	w := httptest.NewRecorder()
	h(w, r)
	return w.Code, w.Body.Bytes()
}

// Ciclo completo: el mayorista manda su catálogo, Lupita pide con cantidades,
// el mayorista acepta y entrega, y el inventario de las dos tiendas se mueve.
func TestChatCatalogoPedidoEntrega(t *testing.T) {
	a := appDePrueba(t)
	lupita, surte := idDe(t, a, DemoEmail), idDe(t, a, "abarrotes.mayoreo@anaquel.mx")
	chat, err := a.chatEntre(surte, lupita)
	if err != nil {
		t.Fatal(err)
	}
	if again, _ := a.chatEntre(lupita, surte); again != chat {
		t.Fatalf("debe ser la misma conversación: %d vs %d", chat, again)
	}
	id := map[string]string{"id": itoa(chat)}

	// La demo deja un mensaje sin leer para Lupita.
	if n := a.chatsSinLeer(lupita); n != 1 {
		t.Fatalf("Lupita debe tener 1 mensaje sin leer, tiene %d", n)
	}

	code, body := llamar(t, a, a.escribirChat, "POST", "/", surte, map[string]any{"catalogo_todo": true}, id)
	if code != 200 {
		t.Fatalf("catálogo: %d %s", code, body)
	}
	var c Chat
	json.Unmarshal(body, &c)
	ultimo := c.Mensajes[len(c.Mensajes)-1]
	if ultimo.Tipo != "catalogo" || len(ultimo.Items) < 5 {
		t.Fatalf("esperaba catálogo con productos: %+v", ultimo)
	}
	var leche ItemCatalogo
	for _, it := range ultimo.Items {
		if it.Nombre == "Leche entera 1 L" {
			leche = it
		}
	}
	if leche.ProductoID == 0 || leche.Disponible == nil {
		t.Fatal("el catálogo debe traer la leche con existencia")
	}

	// Pedir más de lo que hay falla sin crear nada.
	code, _ = llamar(t, a, a.pedirDeChat, "POST", "/", lupita, map[string]any{"catalogo": ultimo.ID,
		"items": []map[string]int64{{"producto_id": leche.ProductoID, "cantidad": *leche.Disponible + 1}}}, id)
	if code != 409 {
		t.Fatalf("pedir de más debe fallar con 409, fue %d", code)
	}

	code, body = llamar(t, a, a.pedirDeChat, "POST", "/", lupita, map[string]any{"catalogo": ultimo.ID,
		"items": []map[string]int64{{"producto_id": leche.ProductoID, "cantidad": 24}}}, id)
	if code != 200 {
		t.Fatalf("pedido: %d %s", code, body)
	}
	json.Unmarshal(body, &c)
	pedido := c.Mensajes[len(c.Mensajes)-1]
	if pedido.Tipo != "pedido" || pedido.Total != 24*2250 || pedido.Rol != "compro" {
		t.Fatalf("pedido mal armado (mayoreo $22.50): %+v", pedido)
	}
	if len(pedido.Puedo) != 1 || pedido.Puedo[0] != "cancelar" {
		t.Fatalf("quien pide solo puede cancelar: %v", pedido.Puedo)
	}
	msg := map[string]string{"id": itoa(chat), "msg": itoa(pedido.ID), "accion": "aceptar"}

	// Lupita no puede aceptar su propio pedido.
	if code, _ := llamar(t, a, a.accionPedido, "POST", "/", lupita, nil, msg); code != 409 {
		t.Fatalf("aceptar lo propio debe fallar, fue %d", code)
	}
	if a.pendientesChat(chat, surte) != 1 {
		t.Fatal("al mayorista le toca aceptar")
	}
	if code, body := llamar(t, a, a.accionPedido, "POST", "/", surte, nil, msg); code != 200 {
		t.Fatalf("aceptar: %d %s", code, body)
	}
	var stockAntes int64
	a.db.QueryRow("SELECT stock FROM productos WHERE tienda_id = ? AND nombre = 'Leche entera 1 L'", lupita).Scan(&stockAntes)
	msg["accion"] = "entregar"
	code, body = llamar(t, a, a.accionPedido, "POST", "/", surte, nil, msg)
	if code != 200 {
		t.Fatalf("entregar: %d %s", code, body)
	}
	json.Unmarshal(body, &c)
	for _, m := range c.Mensajes {
		if m.ID == pedido.ID && m.Estado != "entregado" {
			t.Fatalf("el pedido debe quedar entregado: %+v", m)
		}
	}
	var stockDespues int64
	a.db.QueryRow("SELECT stock FROM productos WHERE tienda_id = ? AND nombre = 'Leche entera 1 L'", lupita).Scan(&stockDespues)
	if stockDespues != stockAntes+24 {
		t.Fatalf("a Lupita le deben entrar 24 leches: %d → %d", stockAntes, stockDespues)
	}

	// Al abrir el chat, Lupita ya no tiene nada sin leer.
	llamar(t, a, a.verChat, "GET", "/", lupita, nil, id)
	if n := a.chatsSinLeer(lupita); n != 0 {
		t.Fatalf("después de abrirlo no debe quedar nada sin leer: %d", n)
	}
	// Otra tienda no puede ver esa conversación.
	if code, _ := llamar(t, a, a.verChat, "GET", "/", idDe(t, a, "ropa@anaquel.mx"), nil, id); code != 404 {
		t.Fatalf("una tienda ajena no debe ver el chat: %d", code)
	}
}

func TestInicioSugiereSurtir(t *testing.T) {
	a := appDePrueba(t)
	lupita := idDe(t, a, DemoEmail)
	code, body := llamar(t, a, a.inicio, "GET", "/", lupita, nil, nil)
	if code != 200 {
		t.Fatalf("%d %s", code, body)
	}
	var in Inicio
	json.Unmarshal(body, &in)
	var azucar *SurtirInicio
	for i, s := range in.ParaSurtir {
		if s.Nombre == "Azúcar estándar 1 kg" {
			azucar = &in.ParaSurtir[i]
		}
	}
	if azucar == nil || len(azucar.Ofertas) == 0 {
		t.Fatalf("el azúcar se acabó y el mayorista la tiene: %+v", in.ParaSurtir)
	}
	if azucar.Ofertas[0].Ganancia <= 0 {
		t.Fatalf("debe decir cuánto gana (compra a $24.50, vende a $32): %+v", azucar.Ofertas[0])
	}
	if len(in.Tiendas) == 0 || in.ChatsSinLeer != 1 {
		t.Fatalf("faltan tiendas cercanas o el mensaje sin leer: %d tiendas, %d sin leer", len(in.Tiendas), in.ChatsSinLeer)
	}
}

func itoa(n int64) string { b, _ := json.Marshal(n); return string(b) }
