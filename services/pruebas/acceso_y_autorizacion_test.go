package pruebas

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestProtocoloDeAcceso(t *testing.T) {
	t.Run("sin sesión no se ve nada del mercado", func(t *testing.T) {
		c := nuevo(t)
		for _, r := range []string{"/mercado/publicaciones", "/mercado/categorias", "/mercado/ordenes", "/reels/", "/pagos/registros",
			"/mercado/fotos/0123456789abcdef0123456789abcdef.jpg", "/reels/media/0123456789abcdef0123456789abcdef.mp4"} {
			esperar(t, c.pedir("GET", r, nil), 401)
		}
	})

	t.Run("cuenta en revisión no entra al mercado", func(t *testing.T) {
		c := nuevo(t).entrarOk("revision@anaquel.mx")
		r := c.pedir("GET", "/mercado/publicaciones", nil)
		esperar(t, r, 403)
		if r.clave() != "en_revision" {
			t.Errorf("clave %q", r.clave())
		}
		esperar(t, c.pedir("GET", "/acceso/invitaciones", nil), 403)
		if c.pedir("GET", "/acceso/yo", nil).json()["estado"] != "pendiente" {
			t.Error("no informa el estado pendiente")
		}
	})

	t.Run("registro solo con invitación válida, de un solo uso", func(t *testing.T) {
		lupita := nuevo(t).entrarOk("demo@anaquel.mx")
		inv := lupita.pedir("POST", "/acceso/invitaciones", map[string]any{})
		esperar(t, inv, 200)
		codigo, _ := inv.json()["codigo"].(string)
		sufijo := time.Now().Format("150405.000")
		datos := func(email, pass string, lat float64) map[string]any {
			return map[string]any{"codigo": codigo, "nombre": "Tienda Prueba " + sufijo, "email": email, "password": pass,
				"telefono": "6181112233", "colonia": "Centro", "giro": "abarrotes", "lat": lat, "lng": -104.65}
		}
		nueva := nuevo(t)
		esperar(t, nueva.pedir("POST", "/acceso/registro", map[string]any{"codigo": "AAAAA-BBBBB", "nombre": "X", "email": "x@x.mx", "password": "x", "colonia": "x", "giro": "otro"}), 400)
		esperar(t, nueva.pedir("POST", "/acceso/registro", datos("p"+sufijo+"@prueba.mx", "123456", 24.02)), 400)       // contraseña débil
		r := nueva.pedir("POST", "/acceso/registro", datos("p"+sufijo+"@prueba.mx", "Frase Segura De Prueba 7", 19.43)) // CDMX
		esperar(t, r, 400)
		if r.clave() != "fuera_de_zona" {
			t.Errorf("fuera de Durango debería rechazarse con fuera_de_zona: %s", r)
		}
		esperar(t, nueva.pedir("POST", "/acceso/registro", datos("demo@anaquel.mx", "Frase Segura De Prueba 7", 24.02)), 409)
		r = nueva.pedir("POST", "/acceso/registro", datos("p"+sufijo+"@prueba.mx", "Frase Segura De Prueba 7", 24.02))
		esperar(t, r, 200)
		if cu, _ := r.json()["cuenta"].(map[string]any); cu["estado"] != "pendiente" || cu["vende"] != false {
			t.Errorf("una cuenta nueva debe nacer pendiente y sin venta: %v", cu)
		}
		esperar(t, nueva.pedir("GET", "/mercado/publicaciones", nil), 403)
		// El mismo código ya no sirve.
		esperar(t, nuevo(t).pedir("POST", "/acceso/registro", datos("q"+sufijo+"@prueba.mx", "Frase Segura De Prueba 7", 24.02)), 400)

		// Un administrador la verifica y entonces sí entra.
		admin := nuevo(t).entrarOk("admin@anaquel.mx")
		id := num(r.json()["cuenta"].(map[string]any), "id")
		esperar(t, nuevo(t).entrarOk("ropa@anaquel.mx").pedir("POST", fmt.Sprintf("/acceso/admin/cuentas/%d/verificar", id), map[string]any{}), 403)
		esperar(t, admin.pedir("POST", fmt.Sprintf("/acceso/admin/cuentas/%d/verificar", id), map[string]any{}), 200)
		esperar(t, nueva.pedir("GET", "/mercado/publicaciones", nil), 200)
		// Y si lo suspende, se le cierra la sesión.
		esperar(t, admin.pedir("POST", fmt.Sprintf("/acceso/admin/cuentas/%d/suspender", id), map[string]any{}), 200)
		esperar(t, nueva.pedir("GET", "/mercado/publicaciones", nil), 401)
	})

	t.Run("adivinar códigos de invitación se bloquea", func(t *testing.T) {
		c := nuevo(t)
		bloqueado := false
		for i := range 14 {
			r := c.pedir("POST", "/acceso/registro", map[string]any{"codigo": fmt.Sprintf("ZZZZZ%05d", i), "nombre": "Prueba", "email": fmt.Sprintf("z%d@z.mx", i),
				"password": "Frase Segura De Prueba 7", "colonia": "Centro", "giro": "otro"})
			if r.codigo == 429 {
				bloqueado = true
			}
		}
		if !bloqueado {
			t.Error("14 códigos falsos seguidos no se bloquearon")
		}
	})

	t.Run("solo un admin administra cuentas", func(t *testing.T) {
		esperar(t, nuevo(t).entrarOk("mayoreo@anaquel.mx").pedir("GET", "/acceso/admin/cuentas", nil), 403)
		esperar(t, nuevo(t).pedir("GET", "/acceso/admin/cuentas", nil), 401)
	})

	t.Run("límite de 5 invitaciones activas", func(t *testing.T) {
		c := nuevo(t).entrarOk("textiles@anaquel.mx")
		ultimo := 0
		for range 7 {
			ultimo = c.pedir("POST", "/acceso/invitaciones", map[string]any{}).codigo
		}
		if ultimo != 409 {
			t.Errorf("después de 7 invitaciones: %d", ultimo)
		}
	})
}

func TestAutorizacion(t *testing.T) {
	comprador := nuevo(t).entrarOk("papeleria@anaquel.mx")
	vendedor := nuevo(t).entrarOk("mayoreo@anaquel.mx")
	tercero := nuevo(t).entrarOk("ropa@anaquel.mx")
	pub := publicacionDe(comprador, "gorras-el-tunal")
	orden := comprador.pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": pub, "cantidad": 60})
	esperar(t, orden, 200)
	oid := num(orden.json(), "id")

	t.Run("IDOR: un tercero no ve ni toca órdenes ajenas", func(t *testing.T) {
		esperar(t, tercero.pedir("GET", fmt.Sprintf("/mercado/ordenes/%d", oid), nil), 404)
		esperar(t, tercero.pedir("POST", fmt.Sprintf("/mercado/ordenes/%d/cancelar", oid), map[string]any{}), 404)
		esperar(t, tercero.pedir("POST", fmt.Sprintf("/mercado/ordenes/%d/entregar", oid), map[string]any{}), 404)
		if strings.Contains(string(tercero.pedir("GET", "/mercado/ordenes", nil).cuerpo), fmt.Sprintf(`"id":%d,`, oid)) {
			t.Error("la orden ajena aparece en la lista del tercero")
		}
		esperar(t, comprador.pedir("GET", fmt.Sprintf("/mercado/ordenes/%d", oid), nil), 200)
		esperar(t, vendedor.pedir("GET", fmt.Sprintf("/mercado/ordenes/%d", oid), nil), 200)
	})

	t.Run("IDOR: un tercero no edita publicaciones ajenas", func(t *testing.T) {
		datos := map[string]any{"titulo": "Hackeado", "categoria": "otros", "precio_c": 100, "unidad": "pieza", "minimo": 1}
		esperar(t, tercero.pedir("PUT", fmt.Sprintf("/mercado/publicaciones/%d", pub), datos), 404)
		esperar(t, tercero.pedir("POST", fmt.Sprintf("/mercado/publicaciones/%d/estado", pub), map[string]string{"estado": "pausada"}), 404)
		esperar(t, tercero.pedir("DELETE", fmt.Sprintf("/mercado/publicaciones/%d", pub), nil), 404)
		esperar(t, tercero.subir(fmt.Sprintf("/mercado/publicaciones/%d/fotos", pub), nil, "foto", "a.jpg", jpeg(400, 400)), 404)
		if t2 := comprador.pedir("GET", fmt.Sprintf("/mercado/publicaciones/%d", pub), nil); strings.Contains(string(t2.cuerpo), "Hackeado") {
			t.Error("la publicación fue modificada por un tercero")
		}
	})

	t.Run("asignación masiva: no se puede elegir el vendedor ni campos extra", func(t *testing.T) {
		r := tercero.pedir("POST", "/mercado/publicaciones", map[string]any{"titulo": "Prueba", "categoria": "otros", "precio_c": 1000, "unidad": "pieza", "minimo": 1, "vendedor_id": 1})
		esperar(t, r, 400)
		r = comprador.pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": pub, "cantidad": 50, "precio_c": 1})
		esperar(t, r, 400)
		r = comprador.pedir("PUT", "/acceso/yo", map[string]any{"nombre": "X Y", "colonia": "Centro", "telefono": "", "descripcion": "", "rol": "admin"})
		esperar(t, r, 400)
	})

	t.Run("quien no tiene permiso de venta no publica ni cobra", func(t *testing.T) {
		admin := nuevo(t).entrarOk("admin@anaquel.mx")
		yo := comprador.pedir("GET", "/acceso/yo", nil).json()
		id := num(yo, "id")
		esperar(t, admin.pedir("POST", fmt.Sprintf("/acceso/admin/cuentas/%d/quitar-venta", id), map[string]any{}), 200)
		defer admin.pedir("POST", fmt.Sprintf("/acceso/admin/cuentas/%d/permitir-venta", id), map[string]any{})
		r := comprador.pedir("POST", "/mercado/publicaciones", map[string]any{"titulo": "Prueba", "categoria": "otros", "precio_c": 1000, "unidad": "pieza", "minimo": 1})
		esperar(t, r, 403)
		esperar(t, comprador.pedir("GET", "/pagos/terminales", nil), 403)
		esperar(t, comprador.pedir("GET", "/mercado/publicaciones", nil), 200) // comprar sí puede
	})

	t.Run("pagos: un vendedor no cobra órdenes ni usa terminales ajenas", func(t *testing.T) {
		tv := vendedor.pedir("POST", "/pagos/terminales", map[string]string{"proveedor": "simulador", "nombre": "Mostrador"})
		esperar(t, tv, 200)
		tt := tercero.pedir("POST", "/pagos/terminales", map[string]string{"proveedor": "simulador", "nombre": "Caja"})
		esperar(t, tt, 200)
		esperar(t, tercero.pedir("POST", "/pagos/cobros", map[string]any{"orden_id": oid, "terminal_id": num(tt.json(), "id")}), 404)
		esperar(t, tercero.pedir("POST", "/pagos/cobros", map[string]any{"orden_id": oid, "terminal_id": num(tv.json(), "id")}), 404)
		esperar(t, tercero.pedir("DELETE", fmt.Sprintf("/pagos/terminales/%d", num(tv.json(), "id")), nil), 404)
		// Una marca que no está configurada no se puede vincular.
		esperar(t, vendedor.pedir("POST", "/pagos/terminales", map[string]string{"proveedor": "mercadopago", "nombre": "Caja 2", "dispositivo": "PAX_A910__X1"}), 400)
	})
}

func TestCarreras(t *testing.T) {
	vendedor := nuevo(t).entrarOk("textiles@anaquel.mx")
	compradores := []*cliente{nuevo(t).entrarOk("papeleria@anaquel.mx"), nuevo(t).entrarOk("ropa@anaquel.mx"), nuevo(t).entrarOk("demo@anaquel.mx")}

	t.Run("sobreventa: 30 compras simultáneas de las últimas 5 piezas", func(t *testing.T) {
		pub := nuevaPublicacion(vendedor, "Últimas 5 chamarras", ptr(5), 50000)
		var mu sync.Mutex
		exitos := 0
		var wg sync.WaitGroup
		for i := range 30 {
			wg.Add(1)
			go func() {
				defer wg.Done()
				r := compradores[i%3].pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": pub, "cantidad": 1})
				if r.codigo == 200 {
					mu.Lock()
					exitos++
					mu.Unlock()
				}
			}()
		}
		wg.Wait()
		r := vendedor.pedir("GET", fmt.Sprintf("/mercado/publicaciones/%d", pub), nil)
		quedan := num(r.json()["publicacion"].(map[string]any), "existencias")
		if exitos != 5 || quedan != 0 {
			t.Errorf("se apartaron %d (esperaba 5) y quedan %d", exitos, quedan)
		}
	})

	t.Run("doble cobro: 10 cobros simultáneos de la misma orden", func(t *testing.T) {
		pub := nuevaPublicacion(vendedor, "Sudadera para cobro doble", ptr(10), 26000)
		o := compradores[0].pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": pub, "cantidad": 1})
		term := vendedor.pedir("POST", "/pagos/terminales", map[string]string{"proveedor": "simulador", "nombre": "Mostrador"})
		var mu sync.Mutex
		exitos := 0
		var wg sync.WaitGroup
		for range 10 {
			wg.Add(1)
			go func() {
				defer wg.Done()
				r := vendedor.pedir("POST", "/pagos/cobros", map[string]any{"orden_id": num(o.json(), "id"), "terminal_id": num(term.json(), "id")})
				if r.codigo == 200 {
					mu.Lock()
					exitos++
					mu.Unlock()
				}
			}()
		}
		wg.Wait()
		if exitos != 1 {
			t.Errorf("se crearon %d cobros para una orden (esperaba 1)", exitos)
		}
	})
}

func TestAislamientoDeLaBase(t *testing.T) {
	ctx := context.Background()
	t.Run("cada servicio solo ve su esquema", func(t *testing.T) {
		m := bd(t, "mercado")
		for _, sql := range []string{"SELECT COUNT(*) FROM acceso.cuentas", "SELECT COUNT(*) FROM acceso.sesiones", "SELECT COUNT(*) FROM pagos.cobros"} {
			var n int
			if err := m.QueryRow(ctx, sql).Scan(&n); err == nil {
				t.Errorf("mercado_app pudo leer: %s", sql)
			}
		}
		p := bd(t, "pagos")
		var n int
		if err := p.QueryRow(ctx, "SELECT COUNT(*) FROM acceso.cuentas").Scan(&n); err == nil {
			t.Error("pagos_app pudo leer las cuentas")
		}
	})
	t.Run("seguridad por fila: sin identidad no se ve ninguna orden", func(t *testing.T) {
		m := bd(t, "mercado")
		var total, ajenas int
		_ = m.QueryRow(ctx, "SELECT COUNT(*) FROM ordenes").Scan(&total)
		if total != 0 {
			t.Errorf("sin app.cuenta se ven %d órdenes", total)
		}
		tx, _ := m.Begin(ctx)
		defer tx.Rollback(ctx)
		_, _ = tx.Exec(ctx, "SELECT set_config('app.cuenta', '999999', true)")
		_ = tx.QueryRow(ctx, "SELECT COUNT(*) FROM ordenes").Scan(&ajenas)
		if ajenas != 0 {
			t.Errorf("una cuenta sin órdenes ve %d", ajenas)
		}
	})

}
