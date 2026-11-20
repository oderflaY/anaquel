package main

import (
	"net/http"
	"sort"
	"strings"
)

// Mapa de la red: los negocios asociados con ubicación (tiendas, mayoristas,
// servicios) y los productores locales, con lo justo para la tarjeta del mapa.

type PuntoMapa struct {
	TiendaZona
	Lat          float64  `json:"lat"`
	Lng          float64  `json:"lng"`
	Direccion    string   `json:"direccion"`
	Horario      string   `json:"horario,omitempty"`
	Entrega      string   `json:"entrega,omitempty"`
	Verificada   bool     `json:"verificada"`
	Calificacion float64  `json:"calificacion"`
	Resenas      int      `json:"resenas"`
	Tipo         string   `json:"tipo"` // mayorista | tienda | servicio
	Destacados   []string `json:"destacados"`
	Foto         string   `json:"foto,omitempty"`
}

type ProductorMapa struct {
	ID        int64   `json:"id"`
	Nombre    string  `json:"nombre"`
	Productos string  `json:"productos"`
	Lugar     string  `json:"lugar"`
	Telefono  string  `json:"telefono"`
	Lat       float64 `json:"lat"`
	Lng       float64 `json:"lng"`
}

type MapaRed struct {
	Yo *struct {
		Nombre string  `json:"nombre"`
		Lat    float64 `json:"lat"`
		Lng    float64 `json:"lng"`
	} `json:"yo"`
	Tiendas     []PuntoMapa     `json:"tiendas"`
	Productores []ProductorMapa `json:"productores"`
}

func tipoNegocio(giro string, productos int) string {
	switch {
	case giro == "mayoreo" || productos >= 15:
		return "mayorista"
	case giro == "fletes" || giro == "imprenta" || giro == "servicios":
		return "servicio"
	}
	return "tienda"
}

// mapa: GET /api/mapa
func (a *App) mapa(w http.ResponseWriter, r *http.Request) {
	yo := tiendaDe(r)
	out := MapaRed{Tiendas: []PuntoMapa{}, Productores: []ProductorMapa{}}
	var nombre string
	var la, ln *float64
	a.db.QueryRow("SELECT nombre, lat, lng FROM tiendas WHERE id = ?", yo).Scan(&nombre, &la, &ln)
	if la != nil && ln != nil {
		out.Yo = &struct {
			Nombre string  `json:"nombre"`
			Lat    float64 `json:"lat"`
			Lng    float64 `json:"lng"`
		}{nombre, *la, *ln}
	}
	rows, err := a.db.Query(`SELECT t.id, t.nombre, t.slug, t.giro, t.telefono, t.descripcion, t.lat, t.lng, t.direccion,
		t.horario, t.entrega, t.verificada, t.calificacion, t.resenas, t.portada,
		(SELECT COUNT(*) FROM productos p WHERE p.tienda_id = t.id AND p.publicado = 1 AND (p.stock IS NULL OR p.stock > 0) AND `+visibleSQL("p")+`)
		FROM tiendas t WHERE t.id != ? AND t.lat IS NOT NULL AND t.lng IS NOT NULL`, yo, yo, yo)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	for rows.Next() {
		var p PuntoMapa
		rows.Scan(&p.ID, &p.Nombre, &p.Slug, &p.Giro, &p.Telefono, &p.Descripcion, &p.Lat, &p.Lng, &p.Direccion,
			&p.Horario, &p.Entrega, &p.Verificada, &p.Calificacion, &p.Resenas, &p.Foto, &p.Productos)
		if out.Yo != nil {
			d := redondear(distanciaKm(out.Yo.Lat, out.Yo.Lng, p.Lat, p.Lng))
			p.DistanciaKm = &d
		}
		p.Tipo = tipoNegocio(p.Giro, p.Productos)
		p.Destacados = []string{}
		out.Tiendas = append(out.Tiendas, p)
	}
	rows.Close()
	sort.SliceStable(out.Tiendas, func(i, j int) bool { return distanciaPunto(out.Tiendas[i]) < distanciaPunto(out.Tiendas[j]) })
	// Fuera del rows (una sola conexión): chat abierto, foto y lo más fuerte de su vitrina.
	for i := range out.Tiendas {
		t := &out.Tiendas[i]
		x, y := min(yo, t.ID), max(yo, t.ID)
		a.db.QueryRow("SELECT id FROM chats WHERE tienda_a = ? AND tienda_b = ?", x, y).Scan(&t.ChatID)
		pr, err := a.db.Query(`SELECT nombre, foto FROM productos p WHERE p.tienda_id = ? AND p.publicado = 1 AND (p.stock IS NULL OR p.stock > 0)
			AND `+visibleSQL("p")+` ORDER BY (p.foto != '') DESC, p.precio_mayoreo_c > 0 DESC, p.stock DESC LIMIT 3`, t.ID, yo, yo)
		if err != nil {
			continue
		}
		for pr.Next() {
			var n, f string
			pr.Scan(&n, &f)
			t.Destacados = append(t.Destacados, n)
			if t.Foto == "" && f != "" {
				t.Foto = f
			}
		}
		pr.Close()
	}
	prs, err := a.db.Query("SELECT id, nombre, productos, lugar, telefono, lat, lng FROM productores ORDER BY nombre LIMIT 200")
	if err == nil {
		for prs.Next() {
			var p ProductorMapa
			prs.Scan(&p.ID, &p.Nombre, &p.Productos, &p.Lugar, &p.Telefono, &p.Lat, &p.Lng)
			p.Productos = strings.TrimSpace(p.Productos)
			out.Productores = append(out.Productores, p)
		}
		prs.Close()
	}
	manejar(w, out, nil)
}

func distanciaPunto(p PuntoMapa) float64 {
	if p.DistanciaKm == nil {
		return 1e9
	}
	return *p.DistanciaKm
}
