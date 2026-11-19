package main

import "testing"

// El detector debe ver los huecos de la foto de demo aunque esté movida.
func TestComparadorDetectaHuecos(t *testing.T) {
	ref := jpegDe(dibujarAnaquel(nil, 0))
	casos := []struct {
		nombre    string
		unidades  []int
		desplazar int
	}{
		{"misma posición", demoHoy, 0},
		{"movida 6 px", demoHoy, 6},
		{"movida 14 px", demoHoy, 14},
	}
	for _, c := range casos {
		hoy := jpegDe(dibujarAnaquel(c.unidades, c.desplazar))
		zonas := zonasDemo()
		for i := range zonas {
			zonas[i].ID = int64(i)
		}
		l, err := Comparador{}.Analizar(ref, hoy, zonas)
		if err != nil {
			t.Fatal(err)
		}
		for i, x := range l {
			want := "lleno"
			switch c.unidades[i] {
			case 0:
				want = "vacio"
			case 1:
				want = "poco"
			}
			if x.Nivel != want && !(c.unidades[i] == 2 && x.Nivel == "lleno") {
				t.Errorf("%s: hueco %d (%d piezas) = %s (%.2f), quería %s", c.nombre, i, c.unidades[i], x.Nivel, x.Score, want)
			}
		}
		t.Logf("%s: %v", c.nombre, l)
	}
}

func TestCoincide(t *testing.T) {
	si := [][2]string{{"huevo, queso", "Huevo de rancho (docena)"}, {"tortilla de maíz", "Tortillas de maiz 1 kg"}, {"nopales", "Nopal picado"}}
	no := [][2]string{{"huevo", "Refresco de cola"}, {"queso fresco", "Queso menonita"}}
	for _, c := range si {
		if !coincide(c[0], c[1]) {
			t.Errorf("%q debería coincidir con %q", c[0], c[1])
		}
	}
	for _, c := range no {
		if coincide(c[0], c[1]) {
			t.Errorf("%q no debería coincidir con %q", c[0], c[1])
		}
	}
}
