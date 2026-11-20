package pruebas

import (
	"fmt"
	"testing"
)

var prioridad = map[string]int{"premium": 3, "destacado": 2, "basico": 1}

func TestPublicidad(t *testing.T) {
	comprador := nuevo(t).entrarOk("papeleria@anaquel.mx")
	tunal := nuevo(t).entrarOk("mayoreo@anaquel.mx")

	espacios := func(c *cliente, query string) (map[string]any, []map[string]any) {
		r := c.pedir("GET", "/mercado/promociones/espacios"+query, nil)
		esperar(t, r, 200)
		m := r.json()
		var prods []map[string]any
		for _, x := range m["productos"].([]any) {
			prods = append(prods, x.(map[string]any))
		}
		b, _ := m["banner"].(map[string]any)
		return b, prods
	}

	t.Run("espacios: banner Premium, máximo 3 patrocinados, sin duplicados y en orden de plan", func(t *testing.T) {
		b, prods := espacios(comprador, "")
		if b == nil || b["plan"] != "premium" {
			t.Fatalf("esperaba banner Premium: %v", b)
		}
		if len(prods) > 3 {
			t.Errorf("%d patrocinados (máximo 3)", len(prods))
		}
		vistos := map[float64]bool{b["id"].(float64): true}
		anterior := 99
		for _, p := range prods {
			id := p["id"].(float64)
			if vistos[id] {
				t.Errorf("producto %v repetido entre los anuncios", id)
			}
			vistos[id] = true
			if pr := prioridad[p["plan"].(string)]; pr > anterior {
				t.Errorf("orden incorrecto: %v después de un plan menor", p["plan"])
			} else {
				anterior = pr
			}
			if p["vendedor"].(map[string]any)["slug"] == b["vendedor"].(map[string]any)["slug"] {
				t.Error("el negocio del banner se repite en la misma vista")
			}
		}
	})

	t.Run("un negocio no ve sus propios anuncios", func(t *testing.T) {
		b, prods := espacios(tunal, "")
		for _, p := range append(prods, b) {
			if p != nil && p["vendedor"].(map[string]any)["slug"] == "gorras-el-tunal" {
				t.Error("Gorras El Tunal ve su propio anuncio")
			}
		}
	})

	t.Run("en búsquedas no hay banner y solo planes con búsquedas", func(t *testing.T) {
		b, prods := espacios(comprador, "?q=chamarra")
		if b != nil {
			t.Error("hubo banner en una búsqueda")
		}
		for _, p := range prods {
			if p["plan"] == "basico" {
				t.Error("un plan Básico apareció en búsquedas")
			}
		}
		_, prods = espacios(comprador, "?categoria=papeleria")
		for _, p := range prods {
			if p["categoria"] != "papeleria" {
				t.Errorf("anuncio fuera de la categoría: %v", p["categoria"])
			}
		}
	})

	t.Run("crear anuncios: validaciones, duplicados y permisos", func(t *testing.T) {
		textiles := nuevo(t).entrarOk("textiles@anaquel.mx")
		pub := nuevaPublicacion(textiles, "Gorro tejido para anunciar", ptr(30), 9000)
		ajena := publicacionDe(comprador, "gorras-el-tunal")
		esperar(t, textiles.pedir("POST", "/mercado/promociones", map[string]any{"publicacion_id": ajena, "plan": "basico", "semanas": 1}), 404)
		esperar(t, textiles.pedir("POST", "/mercado/promociones", map[string]any{"publicacion_id": pub, "plan": "oro", "semanas": 1}), 400)
		esperar(t, textiles.pedir("POST", "/mercado/promociones", map[string]any{"publicacion_id": pub, "plan": "basico", "semanas": 13}), 400)
		esperar(t, textiles.pedir("POST", "/mercado/promociones", map[string]any{"publicacion_id": pub, "plan": "basico", "semanas": 1, "costo_c": 1}), 400)
		r := textiles.pedir("POST", "/mercado/promociones", map[string]any{"publicacion_id": pub, "plan": "destacado", "semanas": 2, "mensaje": "Gorros tejidos a mano"})
		esperar(t, r, 200)
		if num(r.json(), "costo_c") != 19800 {
			t.Errorf("costo: %v (esperaba 2 × $99)", r.json()["costo_c"])
		}
		esperar(t, textiles.pedir("POST", "/mercado/promociones", map[string]any{"publicacion_id": pub, "plan": "basico", "semanas": 1}), 409)
		promo := num(r.json(), "id")
		// Otro negocio no puede pausar ni reactivar el anuncio.
		esperar(t, tunal.pedir("POST", fmt.Sprintf("/mercado/promociones/%d/estado", promo), map[string]string{"estado": "pausada"}), 404)
		esperar(t, textiles.pedir("POST", fmt.Sprintf("/mercado/promociones/%d/estado", promo), map[string]string{"estado": "pausada"}), 200)
		// Pausado: ya no aparece en los espacios.
		for range 5 {
			b, prods := espacios(comprador, "")
			for _, p := range append(prods, b) {
				if p != nil && num(p, "promo_id") == promo {
					t.Fatal("un anuncio pausado siguió apareciendo")
				}
			}
		}
	})

	t.Run("clics: cuentan los de otros, no los del propio negocio", func(t *testing.T) {
		b, _ := espacios(comprador, "")
		promo := num(b, "promo_id")
		antes := clicsDe(t, tunal, promo)
		esperar(t, comprador.pedir("POST", fmt.Sprintf("/mercado/promociones/%d/clic", promo), map[string]any{}), 200)
		esperar(t, tunal.pedir("POST", fmt.Sprintf("/mercado/promociones/%d/clic", promo), map[string]any{}), 200)
		if d := clicsDe(t, tunal, promo) - antes; d != 1 {
			t.Errorf("los clics subieron %d (esperaba 1)", d)
		}
		esperar(t, nuevo(t).pedir("POST", fmt.Sprintf("/mercado/promociones/%d/clic", promo), map[string]any{}), 401)
	})
}

func clicsDe(t *testing.T, c *cliente, promo int64) int64 {
	t.Helper()
	var lista []map[string]any
	_ = jsonUnmarshal(c.pedir("GET", "/mercado/promociones/mias", nil).cuerpo, &lista)
	for _, m := range lista {
		if num(m, "id") == promo {
			return num(m, "clics")
		}
	}
	t.Fatalf("no encontré el anuncio %d entre los del negocio", promo)
	return 0
}
