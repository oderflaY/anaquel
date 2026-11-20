package main

import (
	"encoding/json"
	"math"
	"os"
	"testing"
)

// Los árboles evaluados en Go deben dar lo mismo que LightGBM en Python.
func TestModeloDemandaIgualQuePython(t *testing.T) {
	p50, p90, err := modelosDeDemanda()
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile("testdata/demanda_casos.json")
	if err != nil {
		t.Fatal(err)
	}
	var casos []struct {
		X   []*float64 `json:"x"`
		P50 float64    `json:"p50"`
		P90 float64    `json:"p90"`
	}
	json.Unmarshal(b, &casos)
	if len(casos) == 0 {
		t.Fatal("sin casos")
	}
	for i, c := range casos {
		x := make([]float64, len(c.X))
		for j, v := range c.X {
			if v == nil {
				x[j] = math.NaN()
			} else {
				x[j] = *v
			}
		}
		if g := p50.predecir(x); math.Abs(g-c.P50) > 1e-6*math.Max(1, c.P50) {
			t.Fatalf("caso %d p50: go %v python %v", i, g, c.P50)
		}
		if g := p90.predecir(x); math.Abs(g-c.P90) > 1e-6*math.Max(1, c.P90) {
			t.Fatalf("caso %d p90: go %v python %v", i, g, c.P90)
		}
	}
}
