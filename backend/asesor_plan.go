package main

import (
	"fmt"
	"net/http"
	"sort"
	"strings"
)

// «Tu socio»: el plan del día del asesor. Junta en una sola vista cómo van las
// ventas, lo que hay que atender hoy (se acaba, no se mueve, tratos), la
// temporada que viene y las recomendaciones del grafo, con el tono de un amigo
// que sabe de negocios.

type CifraPlan struct {
	Etiqueta string `json:"etiqueta"`
	Valor    string `json:"valor"`
	Detalle  string `json:"detalle,omitempty"`
	Tono     string `json:"tono,omitempty"` // bien | ojo | mal
}

type AlertaPlan struct {
	Tipo   string `json:"tipo"` // acaba | parado | trato | temporada | precio
	Titulo string `json:"titulo"`
	Texto  string `json:"texto"`
	Accion string `json:"accion,omitempty"` // pregunta lista para el chat del asesor
}

type PlanAsesor struct {
	Saludo          string          `json:"saludo"`
	Resumen         string          `json:"resumen"`
	Cifras          []CifraPlan     `json:"cifras"`
	Alertas         []AlertaPlan    `json:"alertas"`
	Recomendaciones []Recomendacion `json:"recomendaciones"`
	Consejo         string          `json:"consejo"`
	Grafo           bool            `json:"grafo"`
	Preguntas       []string        `json:"preguntas"`
}

func (a *App) planAsesor(w http.ResponseWriter, r *http.Request) {
	manejar(w, a.armarPlan(r, tiendaDe(r)), nil)
}

func (a *App) armarPlan(r *http.Request, yo int64) PlanAsesor {
	neg, giro := a.datosNegocio(yo)
	p := PlanAsesor{Cifras: []CifraPlan{}, Alertas: []AlertaPlan{}, Recomendaciones: []Recomendacion{}}
	nombre := strings.TrimSuffix(neg.Tienda, " (demo)")
	hora := horaMexico().Hour()
	switch {
	case hora < 12:
		p.Saludo = "¡Buenos días, " + nombre + "!"
	case hora < 19:
		p.Saludo = "¡Buenas tardes, " + nombre + "!"
	default:
		p.Saludo = "¡Buenas noches, " + nombre + "!"
	}

	// ---- Ventas: dinero de los últimos 30 días contra los 30 anteriores.
	dia := int64(24 * 3600_000)
	hoy := ahora()
	precio := map[int64]int64{}
	for _, pr := range neg.Productos {
		precio[pr.ID] = pr.PrecioC
	}
	ventas := func(desde, hasta int64) (dinero, piezas int64) {
		rows, err := a.db.Query(`SELECT producto_id, -SUM(cantidad) FROM movimientos WHERE tienda_id = ?
			AND tipo IN ('venta','salida','trato_salida') AND creado > ? AND creado <= ? GROUP BY producto_id`, yo, desde, hasta)
		if err != nil {
			return
		}
		defer rows.Close()
		for rows.Next() {
			var id, n int64
			rows.Scan(&id, &n)
			dinero += n * precio[id]
			piezas += n
		}
		return
	}
	d30, pz30 := ventas(hoy-30*dia, hoy)
	dAnt, _ := ventas(hoy-60*dia, hoy-30*dia)
	d7, _ := ventas(hoy-7*dia, hoy)
	c := CifraPlan{Etiqueta: "Ventas 30 días", Valor: pesosTxt(d30), Detalle: fmt.Sprintf("%s piezas", cantidadTxt(pz30)), Tono: "bien"}
	if dAnt > 0 {
		cambio := (d30 - dAnt) * 100 / dAnt
		c.Detalle = fmt.Sprintf("%+d%% vs. mes anterior", cambio)
		if cambio < 0 {
			c.Tono = "ojo"
		}
	}
	p.Cifras = append(p.Cifras, c)
	p.Cifras = append(p.Cifras, CifraPlan{Etiqueta: "Al día (promedio)", Valor: pesosTxt(d30 / 3000 * 100), Detalle: "últimos 7 días: " + pesosTxt(d7/100*100)})

	// Valor del inventario y lo que está detenido.
	var valor, parado int64
	for _, pr := range neg.Productos {
		if pr.Existencia != nil && *pr.Existencia > 0 {
			valor += *pr.Existencia * pr.PrecioC
			if pr.Vendidos30 == 0 {
				parado += *pr.Existencia * pr.PrecioC
			}
		}
	}
	ci := CifraPlan{Etiqueta: "Mercancía en tienda", Valor: pesosTxt(valor), Tono: "bien"}
	if valor > 0 && parado > 0 {
		ci.Detalle = fmt.Sprintf("%d%% sin moverse en 30 días", parado*100/valor)
		if parado*100/valor >= 25 {
			ci.Tono = "ojo"
		}
	}
	p.Cifras = append(p.Cifras, ci)
	p.Cifras = append(p.Cifras, CifraPlan{Etiqueta: "Por surtir", Valor: fmt.Sprint(len(neg.BajoMinimo)),
		Detalle: "productos en su mínimo", Tono: map[bool]string{true: "mal", false: "bien"}[len(neg.BajoMinimo) > 0]})

	// ---- Lo que hay que atender hoy.
	if len(neg.BajoMinimo) > 0 {
		p.Alertas = append(p.Alertas, AlertaPlan{Tipo: "acaba", Titulo: "Se te está acabando",
			Texto:  strings.Join(neg.BajoMinimo[:min(4, len(neg.BajoMinimo))], " · "),
			Accion: "¿Dónde consigo más barato lo que se me está acabando?"})
	}
	// Lo que más vende y le queda poco (aunque no haya llegado al mínimo).
	var urgentes []string
	for _, pr := range neg.Productos {
		if pr.Vendidos30 >= 10 && pr.Existencia != nil && *pr.Existencia > pr.Minimo && *pr.Existencia*30/pr.Vendidos30 < 7 {
			urgentes = append(urgentes, fmt.Sprintf("%s (te alcanza ~%d días)", pr.Nombre, max(1, *pr.Existencia*30/pr.Vendidos30)))
		}
	}
	if len(urgentes) > 0 {
		p.Alertas = append(p.Alertas, AlertaPlan{Tipo: "acaba", Titulo: "Tus estrellas van a faltar",
			Texto: strings.Join(urgentes[:min(3, len(urgentes))], " · ") + ". Pídelas antes de quedarte sin ellas."})
	}
	if len(neg.SinVenta30) > 0 {
		p.Alertas = append(p.Alertas, AlertaPlan{Tipo: "parado", Titulo: "Dinero dormido en el anaquel",
			Texto: strings.Join(neg.SinVenta30[:min(4, len(neg.SinVenta30))], " · ") +
				". Ponlo en promoción, en un paquete con lo que más vendes o publícalo al mayoreo para otra tienda.",
			Accion: "¿Qué hago con lo que no se vende?"})
	}
	if len(neg.Tratos) > 0 {
		p.Alertas = append(p.Alertas, AlertaPlan{Tipo: "trato", Titulo: "Tratos esperando",
			Texto: strings.Join(neg.Tratos[:min(3, len(neg.Tratos))], " · ")})
	}
	ts := temporadasProximas(giro, horaMexico(), 45)
	if len(ts) > 0 {
		t := ts[0]
		cuando := "ya empezó"
		if t.FaltanDias > 0 {
			cuando = fmt.Sprintf("faltan %d días", t.FaltanDias)
		}
		p.Alertas = append(p.Alertas, AlertaPlan{Tipo: "temporada", Titulo: t.Nombre + " (" + cuando + ")",
			Texto:  "Lo que más se vende: " + strings.Join(t.Sugerencias[:min(5, len(t.Sugerencias))], ", ") + ".",
			Accion: "¿Qué compro para " + t.Nombre + "?"})
	}

	// ---- Recomendaciones del grafo.
	if rs, err := a.recomendacionesGrafo(r.Context(), yo); err == nil {
		p.Grafo = true
		if len(rs) > 8 {
			rs = rs[:8]
		}
		p.Recomendaciones = append(p.Recomendaciones, rs...)
	}

	// ---- Resumen y consejo, como lo diría un amigo.
	var partes []string
	if d30 > 0 {
		partes = append(partes, fmt.Sprintf("este mes llevas %s en ventas", pesosTxt(d30)))
	}
	if n := len(neg.BajoMinimo); n > 0 {
		partes = append(partes, fmt.Sprintf("%d %s por surtir", n, plural(int64(n), "producto", "productos")))
	}
	if len(p.Recomendaciones) > 0 {
		partes = append(partes, fmt.Sprintf("encontré %d oportunidades en la red para ti", len(p.Recomendaciones)))
	}
	if len(partes) == 0 {
		p.Resumen = "Todavía tengo pocos datos de tu tienda. Registra tus ventas y entradas y te armo un plan con números."
	} else {
		p.Resumen = "Te cuento: " + strings.Join(partes, ", ") + "."
	}
	p.Consejo = consejoDelDia(neg, d30, dAnt, valor, parado)
	p.Preguntas = []string{"¿Cómo va mi negocio?", "¿Qué me recomiendas comprar?", "¿Qué hago con lo que no se vende?", "¿Qué viene de temporada?"}
	return p
}

// consejoDelDia: una sola idea accionable, la que más pesa hoy.
func consejoDelDia(n negocio, d30, dAnt, valor, parado int64) string {
	switch {
	case valor > 0 && parado*100/valor >= 30:
		return fmt.Sprintf("Tienes %s en mercancía que no se mueve. Antes de comprar más de eso, sácalo: un descuento del 10%% o un paquete con tu producto estrella libera efectivo para lo que sí se vende.", pesosTxt(parado))
	case dAnt > 0 && d30 < dAnt:
		return "Tus ventas bajaron contra el mes pasado. Revisa que no te falten tus productos estrella: un anaquel vacío es la venta que se va a la tienda de enfrente."
	case len(n.BajoMinimo) >= 5:
		return "Junta lo que se te acaba en un solo pedido grande a un mayorista: llegas al precio de mayoreo y ahorras viajes."
	}
	top := append([]productoNegocio(nil), n.Productos...)
	sort.SliceStable(top, func(i, j int) bool { return top[i].Vendidos30*top[i].PrecioC > top[j].Vendidos30*top[j].PrecioC })
	if len(top) > 0 && top[0].Vendidos30 > 0 {
		return fmt.Sprintf("Tu producto que más dinero deja es %s. Tenlo siempre a la vista y nunca dejes que se acabe; ponle al lado algo que se lleve con él.", top[0].Nombre)
	}
	return "Empieza registrando cada venta: con dos semanas de datos te digo qué conviene comprar y qué no."
}
