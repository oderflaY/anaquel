package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"
)

// Con VOLCAR_TEMPORADAS=archivo.json escribe, por giro y por día del año, las
// líneas de temporada tal como salen en la hoja del asesor. Las usa el
// generador de datos de entrenamiento (AnaquelIA) para no inventar fechas.
func TestVolcarTemporadas(t *testing.T) {
	ruta := os.Getenv("VOLCAR_TEMPORADAS")
	if ruta == "" {
		t.Skip("solo para generar datos de entrenamiento")
	}
	out := map[string]map[string][]map[string]any{}
	for _, g := range []string{"abarrotes", "ropa", "papeleria", "ferreteria", "farmacia", "dulceria", "comida", "mayoreo"} {
		out[g] = map[string][]map[string]any{}
		for d := time.Date(2026, 1, 1, 12, 0, 0, 0, zonaMX); d.Year() == 2026; d = d.AddDate(0, 0, 1) {
			var ls []map[string]any
			for _, x := range temporadasProximas(g, d, 75) {
				if len(ls) >= 3 {
					break
				}
				cuando := "ya empezó"
				if x.FaltanDias > 0 {
					cuando = fmt.Sprintf("%s, faltan %d días", x.Fecha, x.FaltanDias)
				}
				sug := x.Sugerencias[:min(4, len(x.Sugerencias))]
				ls = append(ls, map[string]any{"nombre": x.Nombre, "faltan": x.FaltanDias, "fecha": x.Fecha,
					"sug": strings.Join(sug, ", "), "linea": fmt.Sprintf("%s (%s): %s", x.Nombre, cuando, strings.Join(sug, ", "))})
			}
			out[g][d.Format("01-02")] = ls
		}
	}
	b, _ := json.Marshal(out)
	if err := os.WriteFile(ruta, b, 0o644); err != nil {
		t.Fatal(err)
	}
}
