package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// El mayorista tiene perfil completo con fotos reales; Lupita queda sencilla.
func TestPerfilesYFotosDemo(t *testing.T) {
	a := appDePrueba(t)
	a.prepararDemoVisual()
	lupita, dist := idDe(t, a, DemoEmail), idDe(t, a, "distribuidora@anaquel.mx")
	if _, err := os.Stat(filepath.Join(a.media, "demo", "arroz.jpg")); err != nil {
		t.Fatal("no copió las fotos demo:", err)
	}
	code, b := llamar(t, a, a.perfilTienda, "GET", "/", lupita, nil, map[string]string{"id": itoa(dist)})
	if code != 200 {
		t.Fatalf("perfil: %d %s", code, b)
	}
	var p PerfilTienda
	json.Unmarshal(b, &p)
	if !p.Verificada || p.Clientes < 100 || p.Completo < 90 || len(p.Etiquetas) == 0 {
		t.Errorf("el mayorista debe tener perfil completo: %+v", p)
	}
	fotos := 0
	for _, it := range p.Catalogo {
		if it.Foto != "" {
			fotos++
		}
	}
	if fotos < 10 {
		t.Errorf("el catálogo del mayorista debe traer fotos reales, trae %d", fotos)
	}
	code, b = llamar(t, a, a.perfilTienda, "GET", "/", dist, nil, map[string]string{"id": itoa(lupita)})
	json.Unmarshal(b, &p)
	if code != 200 || p.Completo >= 90 {
		t.Errorf("Lupita debe tener un perfil más sencillo: %d %d %s", code, p.Completo, b)
	}
	// Las ofertas de la red traen foto.
	res, _ := a.buscarRed(filtroRed{yo: lupita, q: "arroz", tipo: "producto", limite: 5})
	if len(res) == 0 || res[0].Foto == "" {
		t.Errorf("la oferta de arroz debe traer foto: %+v", res)
	}
}

// El teléfono pregunta qué hay nuevo: primero solo los ids, luego lo posterior.
func TestNotificaciones(t *testing.T) {
	a := appDePrueba(t)
	lupita, dist := idDe(t, a, DemoEmail), idDe(t, a, "distribuidora@anaquel.mx")
	var n Notificaciones
	_, b := llamar(t, a, a.notificaciones, "GET", "/api/notificaciones", lupita, nil, nil)
	json.Unmarshal(b, &n)
	if len(n.Items) != 0 {
		t.Fatalf("sin parámetros no debe mandar items: %+v", n)
	}
	chat, _ := a.chatEntre(dist, lupita)
	a.mensajeChat(chat, &dist, "texto", "¿Le surto el arroz mañana?", nil)
	a.avisar(lupita, "prueba", "trato", "Te aceptaron el trato", "")
	_, b = llamar(t, a, a.notificaciones, "GET", "/api/notificaciones?aviso="+itoa(n.UltAviso)+"&chat="+itoa(n.UltChat), lupita, nil, nil)
	var m Notificaciones
	json.Unmarshal(b, &m)
	if len(m.Items) != 2 || m.UltChat <= n.UltChat || m.UltAviso <= n.UltAviso {
		t.Fatalf("debe avisar del mensaje y del trato: %s", b)
	}
	// Lo que escribe uno mismo no se notifica.
	a.mensajeChat(chat, &lupita, "texto", "Sí, gracias", nil)
	_, b = llamar(t, a, a.notificaciones, "GET", "/api/notificaciones?aviso="+itoa(m.UltAviso)+"&chat="+itoa(m.UltChat), lupita, nil, nil)
	json.Unmarshal(b, &n)
	if len(n.Items) != 0 {
		t.Errorf("no debe notificar mis propios mensajes: %s", b)
	}
}
