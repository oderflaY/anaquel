package pruebas

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

func firmar(dataID, reqID, ts string) string {
	m := hmac.New(sha256.New, []byte(os.Getenv("SIMULADOR_SECRETO")))
	fmt.Fprintf(m, "id:%s;request-id:%s;ts:%s;", strings.ToLower(dataID), reqID, ts)
	return hex.EncodeToString(m.Sum(nil))
}

func esperarEstado(t *testing.T, c *cliente, ruta, campo, valor string) map[string]any {
	t.Helper()
	var ultimo map[string]any
	for range 20 {
		ultimo = c.pedir("GET", ruta, nil).json()
		if ultimo[campo] == valor {
			return ultimo
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("%s: %s nunca llegó a %q (quedó %v)", ruta, campo, valor, ultimo[campo])
	return nil
}

func registros(c *cliente) []map[string]any {
	var out []map[string]any
	items, _ := c.pedir("GET", "/pagos/registros", nil).json()["items"].([]any)
	for _, i := range items {
		out = append(out, i.(map[string]any))
	}
	return out
}

func TestPagos(t *testing.T) {
	v := nuevo(t).entrarOk("abarrotes.mayoreo@anaquel.mx")
	c := nuevo(t).entrarOk("papeleria@anaquel.mx")
	otro := nuevo(t).entrarOk("ropa@anaquel.mx")
	term := v.pedir("POST", "/pagos/terminales", map[string]string{"proveedor": "simulador", "nombre": "Mostrador"})
	esperar(t, term, 200)
	tid := num(term.json(), "id")
	pub := nuevaPublicacion(v, "Caja de aceite para pagos", ptr(50), 52000)
	ordenNueva := func(n int) int64 {
		r := c.pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": pub, "cantidad": n})
		esperar(t, r, 200)
		return num(r.json(), "id")
	}

	t.Run("cobro desde la app: terminal → notificación → orden pagada → registro", func(t *testing.T) {
		oid := ordenNueva(2)
		esperar(t, v.pedir("POST", "/pagos/cobros", map[string]any{"orden_id": oid, "terminal_id": tid, "monto_c": 1}), 400) // el monto no lo pone el cliente
		cobro := v.pedir("POST", "/pagos/cobros", map[string]any{"orden_id": oid, "terminal_id": tid})
		esperar(t, cobro, 200)
		if num(cobro.json(), "monto_c") != 104000 {
			t.Errorf("monto del cobro: %v (esperaba el total de la orden, 104000)", cobro.json()["monto_c"])
		}
		cid := cobro.json()["id"].(string)
		esperarEstado(t, c, fmt.Sprintf("/mercado/ordenes/%d", oid), "estado", "por_cobrar")
		esperar(t, c.pedir("POST", fmt.Sprintf("/mercado/ordenes/%d/cancelar", oid), map[string]any{}), 409)
		esperar(t, otro.pedir("GET", "/pagos/cobros/"+cid, nil), 404, 403)

		pend, _ := v.pedir("GET", "/pagos/simulador/pendientes", nil).json2()
		if len(pend) == 0 {
			t.Fatal("la terminal virtual no recibió el cobro")
		}
		tx := pend[0]["id"].(string)
		esperar(t, otro.pedir("POST", "/pagos/simulador/transacciones/"+tx+"/aprobar", map[string]any{}), 409, 404, 403)
		esperar(t, v.pedir("POST", "/pagos/simulador/transacciones/"+tx+"/aprobar", map[string]any{}), 200)
		esperarEstado(t, v, "/pagos/cobros/"+cid, "estado", "aprobado")
		esperarEstado(t, c, fmt.Sprintf("/mercado/ordenes/%d", oid), "estado", "pagada")
		n := 0
		for _, r := range registros(v) {
			if num(r, "orden_id") == oid {
				n++
				if r["origen"] != "app" || num(r, "monto_c") != 104000 {
					t.Errorf("registro: %v", r)
				}
			}
		}
		if n != 1 {
			t.Errorf("la orden quedó %d veces en el registro de cobros (esperaba 1)", n)
		}
		esperar(t, v.pedir("POST", "/pagos/simulador/transacciones/"+tx+"/aprobar", map[string]any{}), 409)
		esperar(t, v.pedir("POST", "/pagos/cobros/"+cid+"/cancelar", map[string]any{}), 409)
		esperar(t, c.pedir("POST", fmt.Sprintf("/mercado/ordenes/%d/entregar", oid), map[string]any{}), 403)
		esperar(t, v.pedir("POST", fmt.Sprintf("/mercado/ordenes/%d/entregar", oid), map[string]any{}), 200)
	})

	t.Run("tarjeta rechazada: la orden vuelve a apartada y no se registra ingreso", func(t *testing.T) {
		oid := ordenNueva(1)
		antes := len(registros(v))
		cid := v.pedir("POST", "/pagos/cobros", map[string]any{"orden_id": oid, "terminal_id": tid}).json()["id"].(string)
		pend, _ := v.pedir("GET", "/pagos/simulador/pendientes", nil).json2()
		esperar(t, v.pedir("POST", "/pagos/simulador/transacciones/"+pend[0]["id"].(string)+"/rechazar", map[string]any{}), 200)
		esperarEstado(t, v, "/pagos/cobros/"+cid, "estado", "rechazado")
		esperarEstado(t, c, fmt.Sprintf("/mercado/ordenes/%d", oid), "estado", "pendiente")
		if len(registros(v)) != antes {
			t.Error("un cobro rechazado se registró como ingreso")
		}
	})

	t.Run("cobro directo en la terminal: se captura solo y se liga a su orden", func(t *testing.T) {
		antes := len(registros(v))
		r := v.pedir("POST", fmt.Sprintf("/pagos/simulador/terminales/%d/cobro-directo", tid), map[string]any{"monto_c": 52000, "concepto": "Venta de mostrador"})
		esperar(t, r, 200)
		var reg map[string]any
		for range 20 {
			if l := registros(v); len(l) > antes {
				reg = l[0]
				break
			}
			time.Sleep(300 * time.Millisecond)
		}
		if reg == nil || reg["origen"] != "terminal" || num(reg, "monto_c") != 52000 {
			t.Fatalf("el cobro directo no se registró: %v", reg)
		}
		rid := num(reg, "id")
		esperar(t, otro.pedir("POST", fmt.Sprintf("/pagos/registros/%d/vincular", rid), map[string]any{"orden_id": 1}), 404, 403)
		oid2 := ordenNueva(2) // total 104000: no coincide
		esperar(t, v.pedir("POST", fmt.Sprintf("/pagos/registros/%d/vincular", rid), map[string]any{"orden_id": oid2}), 409)
		oid := ordenNueva(1)
		esperar(t, v.pedir("POST", fmt.Sprintf("/pagos/registros/%d/vincular", rid), map[string]any{"orden_id": oid}), 200)
		esperarEstado(t, c, fmt.Sprintf("/mercado/ordenes/%d", oid), "estado", "pagada")
		esperar(t, v.pedir("POST", fmt.Sprintf("/pagos/registros/%d/vincular", rid), map[string]any{"orden_id": oid2}), 409)
		// Otro vendedor no ve este ingreso.
		for _, x := range registros(otro) {
			if num(x, "id") == rid {
				t.Error("un vendedor ve los ingresos de otro")
			}
		}
	})

	t.Run("webhooks: sin firma, firma falsa, vieja o repetida", func(t *testing.T) {
		if os.Getenv("SIMULADOR_SECRETO") == "" {
			t.Skip("sin SIMULADOR_SECRETO")
		}
		x := nuevo(t)
		r := v.pedir("POST", fmt.Sprintf("/pagos/simulador/terminales/%d/cobro-directo", tid), map[string]any{"monto_c": 12300})
		txid := r.json()["id"].(string)
		time.Sleep(time.Second)
		cuenta := func() int { return len(registros(v)) }
		total := cuenta()
		aviso := func(id, req, ts, firma string) resp {
			return x.pedir("POST", "/pagos/webhooks/simulador?type=order&data.id="+id, `{"data":{"id":"`+id+`"}}`,
				map[string]string{"x-request-id": req, "x-signature": "ts=" + ts + ",v1=" + firma, "Origin": "https://api.mercadopago.com"})
		}
		ahora := strconv.FormatInt(time.Now().Unix(), 10)
		esperar(t, x.pedir("POST", "/pagos/webhooks/simulador?data.id="+txid, `{}`), 401)
		esperar(t, aviso(txid, "r1", ahora, strings.Repeat("0", 64)), 401)
		viejo := strconv.FormatInt(time.Now().Add(-time.Hour).Unix(), 10)
		esperar(t, aviso(txid, "r2", viejo, firmar(txid, "r2", viejo)), 401)
		esperar(t, aviso("SIMTX-OTRO", "r3", ahora, firmar(txid, "r3", ahora)), 401) // firma de otro id
		// Aviso legítimo repetido 3 veces: se procesa una sola vez.
		for range 3 {
			esperar(t, aviso(txid, "r-repetido", ahora, firmar(txid, "r-repetido", ahora)), 200)
		}
		if cuenta() != total {
			t.Errorf("la repetición del aviso duplicó ingresos: %d → %d", total, cuenta())
		}
		esperar(t, x.pedir("POST", "/pagos/webhooks/inventada", `{}`), 404)
		esperar(t, x.pedir("POST", "/pagos/webhooks/simulador?data.id=SIMTX-NOEXISTE", `{}`, map[string]string{"x-request-id": "r9",
			"x-signature": "ts=" + ahora + ",v1=" + firmar("SIMTX-NOEXISTE", "r9", ahora)}), 500, 200)
		if cuenta() != total {
			t.Error("un aviso firmado de una transacción inexistente creó un ingreso")
		}
	})

	t.Run("el registro de cobros no se puede alterar ni borrar", func(t *testing.T) {
		p := bd(t, "pagos")
		ctx := context.Background()
		var antes, suma int64
		_ = p.QueryRow(ctx, "SELECT COUNT(*), COALESCE(SUM(monto_c), 0) FROM registros").Scan(&antes, &suma)
		if antes == 0 {
			t.Fatal("no hay registros para probar")
		}
		for _, sql := range []string{"UPDATE registros SET monto_c = 1", "UPDATE registros SET orden_id = 1 WHERE orden_id IS NOT NULL",
			"DELETE FROM registros", "TRUNCATE registros"} {
			if _, err := p.Exec(ctx, sql); err == nil {
				t.Errorf("se permitió: %s", sql)
			}
		}
		var despues, suma2 int64
		_ = p.QueryRow(ctx, "SELECT COUNT(*), COALESCE(SUM(monto_c), 0) FROM registros").Scan(&despues, &suma2)
		if despues != antes || suma2 != suma {
			t.Errorf("el registro cambió: %d/%d → %d/%d", antes, suma, despues, suma2)
		}
	})
}

func (r resp) json2() ([]map[string]any, error) {
	var out []map[string]any
	err := jsonUnmarshal(r.cuerpo, &out)
	return out, err
}

func TestPasarelaEnVivo(t *testing.T) {
	t.Run("cabeceras de seguridad en la web y en la API", func(t *testing.T) {
		c := nuevo(t)
		for _, r := range []string{"/mercado/categorias", "/acceso/config"} {
			x := c.pedir("GET", r, nil)
			for _, h := range []string{"Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Permissions-Policy"} {
				if x.cab.Get(h) == "" {
					t.Errorf("%s sin %s", r, h)
				}
			}
			if x.cab.Get("Access-Control-Allow-Origin") != "" || x.cab.Get("Server") != "" {
				t.Errorf("%s expone CORS o Server", r)
			}
		}
	})
	t.Run("identidad interna falsificada desde fuera se ignora", func(t *testing.T) {
		c := nuevo(t)
		esperar(t, c.pedir("GET", "/mercado/publicaciones", nil, map[string]string{"X-Anaquel-Identidad": "eyJjIjoxLCJyIjoiYWRtaW4iLCJlIjoidmVyaWZpY2FkYSIsIngiOjk5OTk5OTk5OTl9.x"}), 401)
		esperar(t, c.pedir("GET", "/mercado/interno/ordenes/1", nil), 404)
		esperar(t, c.pedir("POST", "/acceso/interno/sesion", map[string]string{"token": "x"}), 404)
	})
	t.Run("los servicios no aceptan llamadas directas sin firma", func(t *testing.T) {
		for _, u := range []string{"http://127.0.0.1:8102/publicaciones", "http://127.0.0.1:8102/interno/ordenes/1", "http://127.0.0.1:8101/interno/demo", "http://127.0.0.1:8104/terminales"} {
			res, err := (&cliente{t: t}).directo(u)
			if err == nil && res != 401 {
				t.Errorf("%s → %d", u, res)
			}
		}
	})
	t.Run("CSRF desde otro sitio", func(t *testing.T) {
		c := nuevo(t).entrarOk("papeleria@anaquel.mx")
		esperar(t, c.pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": 1, "cantidad": 1}, map[string]string{"Origin": "https://sitio-malo.example"}), 403)
		esperar(t, c.pedir("POST", "/acceso/salir", map[string]any{}, map[string]string{"Sec-Fetch-Site": "cross-site"}), 403)
	})
	t.Run("límite general de peticiones por IP", func(t *testing.T) {
		c := nuevo(t)
		limitado := false
		for range 300 {
			if c.pedir("GET", "/acceso/config", nil).codigo == 429 {
				limitado = true
				break
			}
		}
		if !limitado {
			t.Error("300 peticiones seguidas de una IP no se limitaron")
		}
	})
}
