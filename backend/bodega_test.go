package main

import (
	"math"
	"testing"
)

func huella(m Mueble) (float64, float64) {
	if m.Rot == 90 || m.Rot == 270 {
		return m.Fondo, m.Ancho
	}
	return m.Ancho, m.Fondo
}

func TestPlanoAutoQuedaAdentroYSinEncimarse(t *testing.T) {
	for _, giro := range []string{"abarrotes", "ropa", "mayoreo", "papeleria", "servicios", "otro"} {
		p := planoAuto(giro, nil)
		for i, m := range p.Muebles {
			w, d := huella(m)
			if m.X-w/2 < -0.001 || m.X+w/2 > p.Ancho+0.001 || m.Z-d/2 < -0.001 || m.Z+d/2 > p.Fondo+0.001 {
				t.Errorf("%s: %s se sale del local", giro, m.ID)
			}
			for _, o := range p.Muebles[i+1:] {
				w2, d2 := huella(o)
				if math.Abs(m.X-o.X) < (w+w2)/2-0.01 && math.Abs(m.Z-o.Z) < (d+d2)/2-0.01 {
					t.Errorf("%s: %s y %s se enciman", giro, m.ID, o.ID)
				}
			}
		}
	}
}

func TestAcomodarRespetaPreferencias(t *testing.T) {
	p := planoAuto("abarrotes", nil)
	ls := []Lugar3D{
		{ID: 1, Nombre: "Refresco de cola 600 ml", Categoria: "Bebidas"},
		{ID: 2, Nombre: "Papas fritas 45 g", Categoria: "Botanas"},
		{ID: 3, Nombre: "Arroz 1 kg", Categoria: "Abarrotes", Vendidos30: 50},
		{ID: 4, Nombre: "Frijol pinto 1 kg", Categoria: "Abarrotes", Vendidos30: 5},
		{ID: 5, Nombre: "Queso fresco 500 g", Categoria: "Productos locales"},
	}
	r := acomodar(p, ls, "abarrotes", false)
	tipo := func(id int64) string {
		for _, m := range p.Muebles {
			if m.ID == r[id].Mueble {
				return m.Tipo
			}
		}
		return ""
	}
	if len(r) != 5 {
		t.Fatalf("acomodó %d de 5", len(r))
	}
	if tipo(1) != "refri" || tipo(5) != "refri" {
		t.Errorf("refresco y queso deben ir al refri: %q %q", tipo(1), tipo(5))
	}
	if tipo(2) != "botanero" {
		t.Errorf("las papas van al exhibidor de botana, no a %q", tipo(2))
	}
	if r[3].Mueble != r[4].Mueble {
		t.Errorf("arroz y frijol (misma categoría) deben quedar juntos")
	}
}

func TestNivelDeZona(t *testing.T) {
	zs := []Zona{}
	for f := 0; f < 3; f++ {
		for c := 0; c < 4; c++ {
			zs = append(zs, Zona{X: float64(c) * 0.25, Y: float64(f) * 0.33, W: 0.24, H: 0.3})
		}
	}
	if filasDeZonas(zs) != 3 {
		t.Fatalf("filas = %d", filasDeZonas(zs))
	}
	if n := nivelDeZona(zs[0], zs, 3); n != 3 {
		t.Errorf("la fila de arriba de la foto es el nivel 3, salió %d", n)
	}
	if n := nivelDeZona(zs[11], zs, 3); n != 1 {
		t.Errorf("la fila de abajo es el nivel 1, salió %d", n)
	}
}

func TestLimpiarPlano(t *testing.T) {
	p := Plano{Ancho: 5, Fondo: 5, Muebles: []Mueble{{ID: "a", Tipo: "gondola", X: 99, Z: -3, Rot: 450, Ancho: 2, Fondo: 1, Alto: 1.5, Niveles: 40}}}
	if err := p.limpiar(); err != nil {
		t.Fatal(err)
	}
	m := p.Muebles[0]
	if m.Rot != 90 || m.Niveles != 6 || m.X > 5 || m.Z < 0 {
		t.Errorf("no se limpió: %+v", m)
	}
	p.Muebles[0].Tipo = "nave espacial"
	if p.limpiar() == nil {
		t.Error("debió rechazar un tipo desconocido")
	}
}
