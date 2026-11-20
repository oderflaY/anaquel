package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"os"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Asesor con modelo local: un modelo ligero (Qwen2.5 1.5B afinado con LoRA
// para Anaquel, ver ~/AnaquelIA) corriendo en llama.cpp junto al servidor.
// No inventa: el servidor arma una hoja con los datos reales de la tienda
// (lo que se acaba, con quién conviene surtir, ventas, temporadas, la red) y
// el modelo solo redacta la respuesta a partir de ella. Si tarda o falla,
// contesta el asesor básico.
//
// El formato de la hoja es el mismo con el que se entrenó el modelo
// (AnaquelIA/generar_datos_v2.py): si cambias uno, cambia el otro.

const promptLocal = "Eres «tu socio», el amigo que sabe de negocios y asesora a tienditas y negocios de Durango en Anaquel. " +
	"Contesta en español de México, cálido y directo, breve (máximo 10 renglones), usando SOLO los datos de la tienda que te doy. " +
	"Da cifras exactas (piezas, precios, distancias, porcentajes, pronósticos) tal como aparecen; cuando recomiendes algo, di por qué con un dato. " +
	"Si un dato no está, dilo y sugiere qué hacer en Anaquel; nunca inventes productos, precios ni proveedores. " +
	"Termina con una acción concreta (pedir en el chat, tocar «Negociar», publicar que lo buscas)."

type modeloLocal struct {
	url    string
	nombre string
	http   *http.Client
}

func nuevoModeloLocal() *modeloLocal {
	u := strings.TrimRight(os.Getenv("LLM_URL"), "/")
	if u == "" {
		return nil
	}
	return &modeloLocal{url: u, nombre: env("LLM_MODELO", "anaquel-asesor"), http: &http.Client{Timeout: 45 * time.Second}} // la pasarela corta a los 2 min
}

func pesosHoja(c int64) string {
	if c%100 == 0 {
		return fmt.Sprintf("$%d", c/100)
	}
	return fmt.Sprintf("$%d.%02d", c/100, c%100)
}

func kmHoja(d *float64) string {
	if d == nil {
		return ""
	}
	return fmt.Sprintf(", a %.1f km", *d)
}

// contextoAsesor arma la hoja de datos que lee el modelo y las tarjetas que verá el tendero.
func (a *App) contextoAsesor(tienda int64, pregunta string) (string, *turnoAsesor) {
	turno := &turnoAsesor{vistas: map[string]bool{}}
	neg, giro := a.datosNegocio(tienda)
	in, _ := a.datosInicio(tienda)
	lat, lng := a.coordsDe(tienda)
	var b strings.Builder
	lista := func(titulo string, lineas []string) {
		b.WriteString(titulo + ":\n")
		if len(lineas) == 0 {
			b.WriteString("- (nada)\n")
		}
		for _, l := range lineas {
			b.WriteString("- " + l + "\n")
		}
	}
	fmt.Fprintf(&b, "TIENDA: %s · giro %s · hoy %s\n", neg.Tienda, nombreGiro(giro), fechaTxt(horaMexico()))
	// Números del negocio y pronóstico (modelo de demanda entrenado con ventas reales).
	plan := a.armarPlan(context.Background(), tienda)
	var cifras []string
	for _, c := range plan.Cifras {
		l := c.Etiqueta + ": " + c.Valor
		if c.Detalle != "" {
			l += " (" + c.Detalle + ")"
		}
		cifras = append(cifras, l)
	}
	lista("NÚMEROS DEL NEGOCIO", cifras)
	pron := a.pronosticar(tienda)
	var prons []string
	for _, p := range neg.Productos {
		f, ok := pron[p.ID]
		if !ok || f.P50 < 1 || len(prons) >= 6 {
			continue
		}
		l := fmt.Sprintf("%s: venderás ~%d (hasta %d)", p.Nombre, int64(math.Round(f.P50)), int64(math.Ceil(f.P90)))
		if p.Existencia != nil {
			l += fmt.Sprintf(", tienes %d", *p.Existencia)
			if f.DiasAlcanza != nil {
				l += fmt.Sprintf(", te alcanza ~%d días", *f.DiasAlcanza)
			}
			if f.Pedir > 0 {
				l += fmt.Sprintf(", pide %d", f.Pedir)
			}
		}
		if f.Tendencia != "estable" {
			l += ", va de " + map[string]string{"sube": "subida", "baja": "bajada"}[f.Tendencia]
		}
		prons = append(prons, l)
	}
	lista("PRONÓSTICO 14 DÍAS", prons)
	var recos []string
	for _, r := range plan.Recomendaciones {
		if len(recos) >= 5 {
			break
		}
		l := r.Titulo + ": " + r.Motivo
		if r.Cantidad > 0 && r.Tipo != "vender" {
			l += fmt.Sprintf("; sugiero %d", r.Cantidad)
		}
		if r.Ganancia > 0 {
			l += fmt.Sprintf(" (ganas %d%%)", r.Ganancia)
		}
		recos = append(recos, l)
		if r.Oferta != nil && r.Tipo != "vender" {
			turno.agregar([]Resultado{r.resultado()})
		}
	}
	lista("RECOMENDACIONES DEL GRAFO", recos)

	var acaba, conviene []string
	for _, s := range in.ParaSurtir {
		if len(acaba) >= 5 {
			break
		}
		quedan := "sin conteo"
		if s.Stock != nil {
			quedan = fmt.Sprintf("quedan %d", *s.Stock)
		}
		acaba = append(acaba, fmt.Sprintf("%s: %s (mínimo %d), lo vendes a %s", s.Nombre, quedan, s.StockMin, pesosHoja(s.PrecioC)))
		if len(s.Ofertas) > 0 && len(conviene) < 5 {
			o := s.Ofertas[0]
			l := fmt.Sprintf("%s con %s: %s c/u desde %d %s%s", s.Nombre, o.Tienda.Nombre, pesosHoja(costoUnitario(o.Resultado)),
				max(1, o.MinimoMayoreo), o.Unidad, kmHoja(o.DistanciaKm))
			if o.Disponible != nil {
				l += fmt.Sprintf(", hay %d", *o.Disponible)
			}
			if o.Ganancia > 0 {
				l += fmt.Sprintf(" (ganas %d%% por pieza)", o.Ganancia)
			}
			conviene = append(conviene, l)
			turno.agregar([]Resultado{o.Resultado})
		}
	}
	lista("SE TE ACABA", acaba)
	lista("CONVIENE SURTIR CON", conviene)

	var top, quietos []string
	for _, p := range neg.Productos {
		if p.Vendidos30 > 0 && len(top) < 5 {
			q := "sin conteo"
			if p.Existencia != nil {
				q = fmt.Sprintf("quedan %d", *p.Existencia)
			}
			top = append(top, fmt.Sprintf("%s: %d vendidos, %s", p.Nombre, p.Vendidos30, q))
		}
	}
	for _, p := range neg.Productos {
		if p.Vendidos30 == 0 && p.Existencia != nil && *p.Existencia > 0 && len(quietos) < 5 {
			quietos = append(quietos, fmt.Sprintf("%s: %d en existencia, lo vendes a %s", p.Nombre, *p.Existencia, pesosHoja(p.PrecioC)))
		}
	}
	lista("MÁS VENDIDO EN 30 DÍAS", top)
	lista("SIN VENTA EN 30 DÍAS", quietos)

	var temps []string
	for _, t := range temporadasProximas(giro, horaMexico(), 75) {
		if len(temps) >= 3 {
			break
		}
		cuando := "ya empezó"
		if t.FaltanDias > 0 {
			cuando = fmt.Sprintf("%s, faltan %d días", t.Fecha, t.FaltanDias)
		}
		temps = append(temps, fmt.Sprintf("%s (%s): %s", t.Nombre, cuando, strings.Join(t.Sugerencias[:min(4, len(t.Sugerencias))], ", ")))
	}
	lista("TEMPORADAS QUE VIENEN", temps)

	var red []string
	if len(clavesDe(pregunta)) > 0 {
		rs, _ := a.buscarRed(filtroRed{yo: tienda, q: pregunta, tipo: "todo", lat: lat, lng: lng, limite: 12, solicitudes: true})
		// Primero lo que coincide en el nombre; entre iguales, lo más barato.
		claves := clavesDe(pregunta)
		enTitulo := func(r Resultado) int {
			t, n := normalizar(r.Titulo), 0
			for _, c := range claves {
				if strings.Contains(t, c) {
					n++
				}
			}
			return n
		}
		sort.SliceStable(rs, func(i, j int) bool {
			if ti, tj := enTitulo(rs[i]), enTitulo(rs[j]); ti != tj {
				return ti > tj
			}
			if rs[i].puntos != rs[j].puntos {
				return rs[i].puntos > rs[j].puntos
			}
			return costoUnitario(rs[i]) < costoUnitario(rs[j])
		})
		for _, r := range rs {
			if len(red) >= 5 {
				break
			}
			switch r.Tipo {
			case "producto":
				l := fmt.Sprintf("%s · %s · %s c/u", r.Titulo, r.Tienda.Nombre, pesosHoja(costoUnitario(r)))
				if r.MinimoMayoreo > 0 && r.PrecioMayoreoC > 0 {
					l += fmt.Sprintf(" desde %d (suelto %s)", r.MinimoMayoreo, pesosHoja(r.PrecioC))
				}
				l += kmHoja(r.DistanciaKm)
				if r.Disponible != nil {
					l += fmt.Sprintf(", hay %d", *r.Disponible)
				}
				red = append(red, l)
			case "solicitud":
				red = append(red, fmt.Sprintf("%s busca %d %s (puedes vendérselo)", r.Tienda.Nombre, r.Cantidad, r.Titulo))
			case "anuncio":
				l := fmt.Sprintf("Anuncio de %s: %s", r.Tienda.Nombre, r.Titulo)
				if r.Precio != "" {
					l += " (" + r.Precio + ")"
				}
				red = append(red, l+kmHoja(r.DistanciaKm))
			case "productor":
				red = append(red, fmt.Sprintf("Productor local %s: %s", r.Titulo, recortarTexto(r.Detalle, 80)))
			}
			turno.agregar([]Resultado{r})
		}
	}
	if len(clavesDe(pregunta)) > 0 {
		lista("EN LA RED SOBRE TU PREGUNTA", red)
	}

	var provs []string
	for _, p := range in.Proveedores {
		if len(provs) >= 3 {
			break
		}
		l := fmt.Sprintf("%s%s: %d productos", p.Nombre, kmHoja(p.DistanciaKm), p.Productos)
		if p.Promocion != "" {
			l += "; promoción: " + p.Promocion
		}
		provs = append(provs, l)
	}
	lista("PROVEEDORES GRANDES CERCA", provs)
	fmt.Fprintf(&b, "PENDIENTES: %d pedidos o tratos esperan tu respuesta · %d mensajes sin leer\n", in.Pendientes, in.ChatsSinLeer)
	return b.String(), turno
}

func (m *modeloLocal) responder(ctx context.Context, hoja, pregunta string) (string, error) {
	cuerpo, _ := json.Marshal(map[string]any{
		"model": m.nombre,
		"messages": []map[string]string{
			{"role": "system", "content": promptLocal},
			{"role": "user", "content": "DATOS DE MI TIENDA:\n" + hoja + "\nPREGUNTA: " + pregunta},
		},
		"temperature": 0.2, "top_p": 0.9, "max_tokens": 360, "repeat_penalty": 1.1,
	})
	req, err := http.NewRequestWithContext(ctx, "POST", m.url+"/v1/chat/completions", bytes.NewReader(cuerpo))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	r, err := m.http.Do(req)
	if err != nil {
		return "", err
	}
	defer r.Body.Close()
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.NewDecoder(r.Body).Decode(&out); err != nil {
		return "", err
	}
	if r.StatusCode != 200 || len(out.Choices) == 0 || strings.TrimSpace(out.Choices[0].Message.Content) == "" {
		return "", fmt.Errorf("modelo local respondió %d", r.StatusCode)
	}
	return strings.TrimSpace(out.Choices[0].Message.Content), nil
}

func (a *App) asesorLocal(ctx context.Context, tienda int64, pregunta string) (MensajeAsesor, error) {
	hoja, turno := a.contextoAsesor(tienda, pregunta)
	texto, err := a.local.responder(ctx, hoja, pregunta)
	if err != nil {
		return MensajeAsesor{}, err
	}
	if motivo := contradiceHoja(texto, hoja, pregunta); motivo != "" {
		return MensajeAsesor{}, fmt.Errorf("respuesta descartada (%s): %q", motivo, recortarTexto(texto, 160))
	}
	turno.pasos = append([]string{"Revisé tu inventario, tus ventas, lo que se te acaba y las ofertas de la red"}, turno.pasos...)
	return MensajeAsesor{Texto: texto, Tarjetas: turno.tarjetas, Pasos: turno.pasos, Motor: "local"}, nil
}

// hojaAsesor: GET /api/asesor/hoja?q= → la hoja de datos tal como la lee el modelo (para revisar y para entrenar).
func (a *App) hojaAsesor(w http.ResponseWriter, r *http.Request) {
	hoja, _ := a.contextoAsesor(tiendaDe(r), r.URL.Query().Get("q"))
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Write([]byte(hoja))
}

var (
	reCifra = regexp.MustCompile(`\d[\d,]*(?:\.\d+)?`)
	reNiego = regexp.MustCompile(`(?i)no (?:encontr[ée]|hay|tengo|vi|aparece)[^.\n]{0,60}`)
)

// contradiceHoja revisa la respuesta del modelo chico contra los datos que leyó:
// si inventa cifras o dice que algo «no está» cuando la hoja sí lo trae, se
// descarta y contesta el asesor básico (que siempre sale de los datos).
func contradiceHoja(texto, hoja, pregunta string) string {
	fuente := strings.ReplaceAll(hoja+" "+pregunta, ",", "")
	for _, n := range reCifra.FindAllString(texto, -1) {
		n = strings.TrimSuffix(strings.ReplaceAll(n, ",", ""), ".")
		if len(n) <= 1 { // 1-9: cantidades sueltas («2 semanas»)
			continue
		}
		if !strings.Contains(fuente, n) && !strings.Contains(fuente, strings.TrimSuffix(n, ".00")) {
			return "cifra inventada " + n
		}
	}
	h := normalizar(hoja)
	for _, frase := range reNiego.FindAllString(texto, -1) {
		for _, palabra := range strings.Fields(normalizar(frase)) {
			if len(palabra) >= 5 && palabra != "encontre" && palabra != "aparece" && strings.Count(h, palabra) >= 2 {
				return "niega algo que sí está: " + palabra
			}
		}
	}
	return ""
}
