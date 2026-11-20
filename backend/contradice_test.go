package main

import "testing"

func TestContradiceHoja(t *testing.T) {
	hoja := "SE TE ACABA:\n- Bufanda tejida: quedan 0 (mínimo 10), lo vendes a $99\nCONVIENE SURTIR CON:\n- Bufanda tejida con Textiles Guadiana (demo): $42 c/u desde 24 pza, a 0.9 km, hay 900\nMÁS VENDIDO:\n- Sudadera con capucha: 31 vendidos, quedan 6\n- Ventas 30 días: $31,229\n"
	malas := []string{
		"No encontré Bufanda tejida en la red de las vitrinas de la red.",
		"Surte Bufanda tejida con Textiles Guadiana a $35 c/u.",
	}
	for _, m := range malas {
		if contradiceHoja(m, hoja, "¿qué surto?") == "" {
			t.Errorf("debió descartar: %s", m)
		}
	}
	buenas := []string{
		"Surte Bufanda tejida: quedan 0. Textiles Guadiana la tiene a $42 desde 24 pza, a 0.9 km.",
		"Lo que más se vende es Sudadera con capucha (31). Vendiste $31,229 en 30 días.",
	}
	for _, m := range buenas {
		if r := contradiceHoja(m, hoja, "¿qué surto?"); r != "" {
			t.Errorf("no debió descartar %q: %s", m, r)
		}
	}
}
