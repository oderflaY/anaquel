package main

import "testing"

func TestMismoProducto(t *testing.T) {
	casos := []struct {
		mio, oferta string
		igual       bool
	}{
		{"Leche entera 1 L", "Leche entera 1 L", true},
		{"Leche entera 1 L", "Chocolate de leche 40 g", false},
		{"Agua natural 1 L", "Agua natural 600 ml", false},
		{"Agua natural 1 L", "Agua natural 1 L", true},
		{"Papel higiénico 4 rollos", "Papel china 50 pliegos", false},
		{"Huevo de rancho (docena)", "Huevo blanco 18 piezas", false},
		{"Azúcar estándar 1 kg", "Azúcar estándar 1 kg", true},
		{"Atún en lata 140 g", "Atún en lata 140 g", true},
	}
	for _, c := range casos {
		if got := mismoProducto(c.mio, c.oferta); got != c.igual {
			t.Errorf("mismoProducto(%q, %q) = %v, quería %v", c.mio, c.oferta, got, c.igual)
		}
	}
}
