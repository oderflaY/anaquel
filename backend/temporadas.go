package main

import (
	"fmt"
	"time"
)

// Calendario comercial de México: qué se vende en cada temporada según el giro.
// Lo usan el asesor con IA (como herramienta) y el asesor básico (sin IA).

type evento struct {
	mes, dia int
	nombre   string
	duracion int // días que dura la temporada (0 = un día)
	porGiro  map[string][]string
}

var calendario = []evento{
	{1, 6, "Día de Reyes", 0, map[string][]string{
		"abarrotes": {"rosca", "chocolate", "leche"}, "dulceria": {"juguetes", "dulces"}, "papeleria": {"regalos", "envoltura"},
		"ropa": {"pijamas", "ropa infantil"}, "comida": {"rosca", "chocolate caliente"}}},
	{1, 8, "Cuesta de enero y regreso a clases", 25, map[string][]string{
		"abarrotes": {"arroz", "frijol", "aceite"}, "papeleria": {"cuadernos", "plumas", "colores"},
		"ropa": {"uniformes", "calcetas", "ropa de invierno en rebaja"}, "farmacia": {"antigripales", "vitamina c"}}},
	{2, 2, "Día de la Candelaria", 0, map[string][]string{
		"abarrotes": {"harina de maiz", "hoja para tamal", "manteca", "atole"}, "comida": {"tamales", "atole"}}},
	{2, 14, "Día del Amor y la Amistad", 0, map[string][]string{
		"dulceria": {"chocolates", "globos", "peluches"}, "papeleria": {"tarjetas", "envoltura", "regalos"},
		"ropa": {"blusas rojas", "accesorios", "bolsas"}, "abarrotes": {"chocolates", "flores"}}},
	{3, 20, "Cuaresma y Semana Santa", 30, map[string][]string{
		"abarrotes": {"atun", "sardina", "chiles en vinagre", "pescado"}, "comida": {"capirotada", "pescado", "mariscos"},
		"ropa": {"ropa ligera", "trajes de baño", "sandalias"}, "ferreteria": {"hieleras", "lonas"}}},
	{4, 15, "Temporada de calor", 75, map[string][]string{
		"abarrotes": {"agua", "refrescos", "hielo", "paletas"}, "ropa": {"playeras", "shorts", "gorras", "sandalias"},
		"ferreteria": {"ventiladores", "coolers", "mangueras"}, "farmacia": {"suero oral", "bloqueador"},
		"dulceria": {"paletas", "nieves"}, "comida": {"aguas frescas", "raspados"}}},
	{4, 30, "Día del Niño", 0, map[string][]string{
		"dulceria": {"juguetes", "piñatas", "dulces"}, "papeleria": {"juegos", "regalos"}, "ropa": {"ropa infantil", "gorras"},
		"abarrotes": {"dulces", "botanas"}}},
	{5, 10, "Día de las Madres", 0, map[string][]string{
		"ropa": {"blusas", "bolsas", "accesorios", "perfumes"}, "dulceria": {"flores", "globos", "chocolates"},
		"papeleria": {"tarjetas", "envoltura"}, "abarrotes": {"pastel", "refrescos", "desechables"}}},
	{6, 15, "Día del Padre", 0, map[string][]string{
		"ropa": {"camisas", "gorras", "cinturones", "carteras"}, "ferreteria": {"herramienta", "asadores"},
		"abarrotes": {"carbon", "carne", "cerveza"}}},
	{6, 20, "Temporada de lluvias", 90, map[string][]string{
		"ferreteria": {"impermeabilizante", "lonas", "botas"}, "ropa": {"impermeables", "paraguas", "botas"},
		"farmacia": {"repelente", "antigripales"}, "abarrotes": {"cafe", "pan"}}},
	{7, 5, "Fin de cursos y vacaciones", 35, map[string][]string{
		"dulceria": {"piñatas", "adornos de fiesta"}, "papeleria": {"diplomas", "regalos de graduacion"},
		"ropa": {"ropa de graduacion", "trajes de baño"}}},
	{8, 10, "Regreso a clases", 30, map[string][]string{
		"papeleria": {"cuadernos", "mochilas", "colores", "lapices", "forros"}, "ropa": {"uniformes", "zapatos escolares", "mochilas", "calcetas"},
		"abarrotes": {"jugos", "galletas", "lonche"}}},
	{9, 15, "Fiestas patrias", 0, map[string][]string{
		"abarrotes": {"tostadas", "crema", "maiz pozolero", "refrescos"}, "dulceria": {"banderas", "adornos tricolor", "matracas"},
		"ropa": {"rebozos", "sombreros", "ropa tipica"}, "comida": {"pozole", "antojitos"}}},
	{10, 31, "Halloween y Día de Muertos", 3, map[string][]string{
		"dulceria": {"disfraces", "dulces", "calabazas"}, "papeleria": {"papel picado", "cartulina"},
		"abarrotes": {"pan de muerto", "chocolate", "velas"}, "ropa": {"disfraces"}}},
	{11, 1, "Temporada de frío", 120, map[string][]string{
		"ropa": {"chamarras", "sudaderas", "bufandas", "guantes", "gorros", "pijamas"}, "abarrotes": {"cafe", "chocolate", "atole", "pan"},
		"farmacia": {"antigripales", "vitamina c"}, "ferreteria": {"calentones", "gas"}, "comida": {"atole", "tamales", "pozole"}}},
	{11, 13, "El Buen Fin", 4, map[string][]string{
		"ropa": {"ofertas", "chamarras"}, "abarrotes": {"paquetes", "despensa"}, "ferreteria": {"herramienta"}}},
	{12, 12, "Día de la Virgen de Guadalupe", 0, map[string][]string{
		"abarrotes": {"veladoras", "buñuelos", "chocolate"}, "dulceria": {"flores", "veladoras"}}},
	{12, 16, "Posadas", 9, map[string][]string{
		"dulceria": {"piñatas", "colacion", "dulces", "cacahuates"}, "abarrotes": {"ponche", "tejocote", "caña", "cacahuates", "desechables"},
		"ropa": {"sueteres navideños", "pijamas"}, "papeleria": {"envoltura", "esferas"}}},
	{12, 24, "Navidad y Año Nuevo", 8, map[string][]string{
		"ropa": {"ropa de fiesta", "sueteres navideños", "pijamas", "ropa interior roja"}, "abarrotes": {"uvas", "sidra", "refrescos", "desechables", "bacalao"},
		"dulceria": {"regalos", "envoltura"}, "papeleria": {"envoltura", "moños", "tarjetas"}, "ferreteria": {"series de luces", "extensiones"},
		"comida": {"pierna", "romeritos", "bacalao"}}},
}

type Temporada struct {
	Nombre      string   `json:"nombre"`
	Fecha       string   `json:"fecha"`
	FaltanDias  int      `json:"faltan_dias"` // 0 = ya empezó
	Sugerencias []string `json:"sugerencias"`
}

var mesesTxt = []string{"", "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"}

func fechaTxt(t time.Time) string { return fmt.Sprintf("%d de %s", t.Day(), mesesTxt[t.Month()]) }

// sugerenciasGiro: un proveedor/mayoreo surte a tiendas de todos los giros.
func sugerenciasGiro(e evento, giro string) []string {
	if s, ok := e.porGiro[giro]; ok {
		return s
	}
	if giro == "mayoreo" || giro == "otro" || giro == "servicios" {
		var out []string
		for _, g := range []string{"abarrotes", "ropa", "dulceria", "papeleria"} {
			s := e.porGiro[g]
			out = append(out, s[:min(2, len(s))]...)
		}
		return out
	}
	return nil
}

// temporadasProximas: lo que está vigente o empieza en los próximos `dias`.
func temporadasProximas(giro string, hoy time.Time, dias int) []Temporada {
	hoy = time.Date(hoy.Year(), hoy.Month(), hoy.Day(), 0, 0, 0, 0, hoy.Location())
	var out []Temporada
	for _, e := range calendario {
		sug := sugerenciasGiro(e, giro)
		if len(sug) == 0 {
			continue
		}
		for _, anio := range []int{hoy.Year() - 1, hoy.Year(), hoy.Year() + 1} {
			ini := time.Date(anio, time.Month(e.mes), e.dia, 0, 0, 0, 0, hoy.Location())
			fin := ini.AddDate(0, 0, e.duracion)
			faltan := int(ini.Sub(hoy).Hours() / 24)
			if (faltan <= 0 && !hoy.After(fin)) || (faltan > 0 && faltan <= dias) {
				out = append(out, Temporada{Nombre: e.nombre, Fecha: fechaTxt(ini), FaltanDias: max(0, faltan), Sugerencias: sug})
				break
			}
		}
	}
	// Primero lo vigente, luego por cercanía.
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j].FaltanDias < out[j-1].FaltanDias; j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

func horaMexico() time.Time { return time.Now().In(zonaMX) }
