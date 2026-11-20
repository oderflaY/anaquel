package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/anthropics/anthropic-sdk-go/v2"
	"github.com/anthropics/anthropic-sdk-go/v2/option"
)

func appDePrueba(t *testing.T) *App {
	t.Helper()
	dir := t.TempDir()
	db, err := abrirDB(filepath.Join(dir, "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	a := &App{db: db, media: dir, vision: Comparador{}}
	if err := a.sembrarDemo(); err != nil {
		t.Fatal(err)
	}
	if err := a.sembrarRed(); err != nil {
		t.Fatal(err)
	}
	return a
}

func idDe(t *testing.T, a *App, email string) int64 {
	var id int64
	if err := a.db.QueryRow("SELECT id FROM tiendas WHERE email = ?", email).Scan(&id); err != nil {
		t.Fatal(err)
	}
	return id
}

// El ciclo del agente contra una API de Anthropic simulada: primero pide buscar
// en la red, recibe los resultados reales y luego responde.
func TestAsesorIACicloDeHerramientas(t *testing.T) {
	a := appDePrueba(t)
	var pedidos []map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var cuerpo map[string]any
		json.Unmarshal(b, &cuerpo)
		pedidos = append(pedidos, cuerpo)
		w.Header().Set("content-type", "application/json")
		if len(pedidos) == 1 {
			io.WriteString(w, `{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
				"content":[{"type":"tool_use","id":"tu_1","name":"buscar_en_la_red","input":{"consulta":"gorras","tipo":"producto"}}],
				"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":10}}`)
			return
		}
		io.WriteString(w, `{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5",
			"content":[{"type":"text","text":"Te conviene la gorra beisbolera de El Tunal."}],
			"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":10}}`)
	}))
	defer srv.Close()
	a.claude = &clienteClaude{
		client: anthropic.NewClient(option.WithAPIKey("prueba"), option.WithBaseURL(srv.URL), option.WithMaxRetries(0)),
		modelo: "claude-opus-5-5",
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	papeleria := idDe(t, a, "papeleria@anaquel.mx")
	previos := []MensajeAsesor{{Rol: "assistant", Texto: "hola"}, {Rol: "user", Texto: "¿qué tal?"}, {Rol: "assistant", Texto: "Bien"}}
	m, err := a.asesorIA(ctx, papeleria, previos, "Necesito 1000 gorras")
	if err != nil {
		t.Fatal(err)
	}
	if m.Motor != "ia" || !strings.Contains(m.Texto, "El Tunal") || len(m.Tarjetas) == 0 || len(m.Pasos) == 0 {
		t.Fatalf("respuesta inesperada: %+v", m)
	}
	if len(pedidos) != 2 {
		t.Fatalf("se esperaban 2 llamadas, hubo %d", len(pedidos))
	}
	// 1ª llamada: modelo, herramientas e historial que empieza por el usuario.
	p1 := pedidos[0]
	if p1["model"] != "claude-opus-5-5" || len(p1["tools"].([]any)) != 5 {
		t.Fatalf("primera llamada mal formada: %v", p1)
	}
	msgs := p1["messages"].([]any)
	if msgs[0].(map[string]any)["role"] != "user" || len(msgs) != 3 {
		t.Fatalf("historial mal armado: %v", msgs)
	}
	// 2ª llamada: lleva el tool_result con lo que hay en la red.
	crudo, _ := json.Marshal(pedidos[1]["messages"])
	if !strings.Contains(string(crudo), "tool_result") || !strings.Contains(string(crudo), "Gorra beisbolera") {
		t.Fatalf("no se mandó el resultado de la herramienta: %s", crudo)
	}
}

func TestAsesorBasicoYTemporadas(t *testing.T) {
	a := appDePrueba(t)
	boutique := idDe(t, a, "ropa@anaquel.mx")
	m := a.asesorBasico(boutique, "¿Qué compro para la temporada de frío?")
	if !strings.Contains(m.Texto, "chamarras") || len(m.Tarjetas) == 0 {
		t.Fatalf("asesor básico: %+v", m)
	}
	ts := temporadasProximas("ropa", time.Date(2026, 11, 19, 12, 0, 0, 0, zonaMX), 75)
	if len(ts) == 0 || ts[0].Nombre != "Temporada de frío" || ts[0].FaltanDias != 0 {
		t.Fatalf("temporadas: %+v", ts)
	}
	ts = temporadasProximas("papeleria", time.Date(2026, 7, 25, 12, 0, 0, 0, zonaMX), 30)
	ok := false
	for _, x := range ts {
		ok = ok || x.Nombre == "Regreso a clases"
	}
	if !ok {
		t.Fatalf("en julio la papelería debe ver el regreso a clases: %+v", ts)
	}
}

func TestKardexNoQuedaNegativo(t *testing.T) {
	a := appDePrueba(t)
	boutique := idDe(t, a, "ropa@anaquel.mx")
	var prod, stock int64
	a.db.QueryRow("SELECT id, stock FROM productos WHERE tienda_id = ? AND nombre = 'Sudadera con capucha'", boutique).Scan(&prod, &stock)
	if _, err := mover(a.db, boutique, prod, "venta", -(stock + 1), "", nil); err == nil {
		t.Fatal("dejó vender más de lo que hay")
	}
	saldo, err := mover(a.db, boutique, prod, "entrada", 10, "", nil)
	if err != nil || saldo != stock+10 {
		t.Fatalf("entrada: %v %d", err, saldo)
	}
	var ultimo int64
	a.db.QueryRow("SELECT saldo FROM movimientos WHERE producto_id = ? ORDER BY id DESC LIMIT 1", prod).Scan(&ultimo)
	if ultimo != stock+10 {
		t.Fatalf("el kárdex no guardó el saldo: %d", ultimo)
	}
}
