package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// La hoja lleva los datos reales de la tienda y el modelo local (llama.cpp
// simulado) recibe exactamente el formato con el que se entrenó.
func TestAsesorLocalHojaYRespuesta(t *testing.T) {
	a := appDePrueba(t)
	yo := idDe(t, a, "demo@anaquel.mx")
	var recibido string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			http.NotFound(w, r)
			return
		}
		var c struct {
			Messages []struct{ Role, Content string } `json:"messages"`
		}
		json.NewDecoder(r.Body).Decode(&c)
		recibido = c.Messages[1].Content
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": "Surte azúcar con la Distribuidora."}}}})
	}))
	defer srv.Close()
	a.local = &modeloLocal{url: srv.URL, nombre: "prueba", http: &http.Client{Timeout: 5 * time.Second}}

	hoja, _ := a.contextoAsesor(yo, "¿dónde compro azúcar?")
	for _, s := range []string{"TIENDA: Abarrotes Doña Lupita", "SE TE ACABA:", "CONVIENE SURTIR CON:", "MÁS VENDIDO EN 30 DÍAS:",
		"TEMPORADAS QUE VIENEN:", "EN LA RED SOBRE TU PREGUNTA:", "PROVEEDORES GRANDES CERCA:", "PENDIENTES:"} {
		if !strings.Contains(hoja, s) {
			t.Errorf("la hoja no trae %q:\n%s", s, hoja)
		}
	}
	m, err := a.asesorLocal(context.Background(), yo, "¿dónde compro azúcar?")
	if err != nil {
		t.Fatal(err)
	}
	if m.Motor != "local" || !strings.Contains(m.Texto, "azúcar") {
		t.Errorf("respuesta inesperada: %+v", m)
	}
	if !strings.HasPrefix(recibido, "DATOS DE MI TIENDA:\nTIENDA: ") || !strings.Contains(recibido, "\n\nPREGUNTA: ¿dónde compro azúcar?") {
		t.Errorf("formato distinto al del entrenamiento:\n%s", recibido)
	}
}

// Si el modelo local no contesta, el asesor no se cae: responde el básico.
func TestAsesorLocalCaidoUsaBasico(t *testing.T) {
	a := appDePrueba(t)
	a.local = &modeloLocal{url: "http://127.0.0.1:1", nombre: "x", http: &http.Client{Timeout: time.Second}}
	yo := idDe(t, a, "demo@anaquel.mx")
	if _, err := a.asesorLocal(context.Background(), yo, "hola"); err == nil {
		t.Fatal("esperaba error con el modelo caído")
	}
}
