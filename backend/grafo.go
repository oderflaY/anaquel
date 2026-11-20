package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/neo4j/neo4j-go-driver/v5/neo4j"
)

// Grafo del comercio local (Neo4j).
//
// La base de cada tienda (SQLite) se clona a un grafo para ver relaciones que en
// tablas cuestan mucho: quién ofrece qué artículo al mayoreo, quién lo vende al
// público, quién le compró a quién y qué venden las tiendas parecidas a la mía.
//
//   (:Tienda)-[:OFRECE]->(:Producto)-[:ES]->(:Articulo)<-[:VENDE {stock, ventas30}]-(:Tienda)
//   (:Producto)-[:EN]->(:Categoria)      (:Producto)-[:VISIBLE_PARA]->(:Tienda)  (precio exclusivo)
//   (:Tienda)-[:COMPRO_A {veces, piezas}]->(:Tienda)
//
// :Producto son SOLO las ofertas de mayoreo publicadas (el clon de la oferta
// masiva); :Articulo es el producto «genérico» (arroz 1 kg) que une las ofertas
// de distintos proveedores con lo que cada tienda vende.
// El clon se rehace completo (son pocos miles de nodos) cada 10 min y unos
// segundos después de cualquier cambio. Si Neo4j no está, todo sigue igual
// pero sin recomendaciones de grafo.

type Grafo struct {
	driver neo4j.DriverWithContext
	avisar chan struct{}
	mu     sync.Mutex
	ultimo time.Time
	nodos  int64
	rels   int64
	error  string
}

func nuevoGrafo() *Grafo {
	url := env("NEO4J_URL", "")
	if url == "" {
		return nil
	}
	pass := env("NEO4J_PASS", "")
	if f := env("NEO4J_PASS_FILE", ""); f != "" {
		if b, err := os.ReadFile(f); err == nil {
			pass = strings.TrimSpace(string(b))
		}
	}
	d, err := neo4j.NewDriverWithContext(url, neo4j.BasicAuth(env("NEO4J_USER", "neo4j"), pass, ""),
		func(c *neo4j.Config) { c.MaxConnectionPoolSize = 8; c.SocketConnectTimeout = 5 * time.Second })
	if err != nil {
		log.Println("grafo: no se pudo crear el driver:", err)
		return nil
	}
	return &Grafo{driver: d, avisar: make(chan struct{}, 1)}
}

// marcarGrafo: algo cambió; el clon se rehace en unos segundos (sin bloquear).
func (a *App) marcarGrafo() {
	if a.grafo == nil {
		return
	}
	select {
	case a.grafo.avisar <- struct{}{}:
	default:
	}
}

// vigilarGrafo: clona al arrancar, cada 10 min y tras cambios (agrupando ráfagas).
func (a *App) vigilarGrafo() {
	if a.grafo == nil {
		return
	}
	// Neo4j tarda en arrancar junto con el núcleo: reintentos cortos al inicio.
	for i := 0; i < 30; i++ {
		if err := a.clonarGrafo(context.Background()); err == nil {
			break
		}
		time.Sleep(10 * time.Second)
	}
	t := time.NewTicker(10 * time.Minute)
	for {
		select {
		case <-t.C:
		case <-a.grafo.avisar:
			time.Sleep(8 * time.Second) // junta varios cambios seguidos en un solo clon
			select {
			case <-a.grafo.avisar:
			default:
			}
		}
		a.clonarGrafo(context.Background())
	}
}

// ---------------------------------------------------------------- clonado

var reglasGrafo = []string{
	"CREATE CONSTRAINT tienda_id IF NOT EXISTS FOR (t:Tienda) REQUIRE t.id IS UNIQUE",
	"CREATE CONSTRAINT producto_id IF NOT EXISTS FOR (p:Producto) REQUIRE p.id IS UNIQUE",
	"CREATE CONSTRAINT articulo_clave IF NOT EXISTS FOR (a:Articulo) REQUIRE a.clave IS UNIQUE",
	"CREATE CONSTRAINT categoria_nombre IF NOT EXISTS FOR (c:Categoria) REQUIRE c.nombre IS UNIQUE",
}

// claveArticulo: el artículo genérico de un producto: su palabra principal y su
// presentación («Arroz súper extra 1 kg» y «Arroz 1 kg» → arroz|1kg).
func claveArticulo(nombre string) string {
	c := clavesDe(nombre)
	if len(c) == 0 {
		return ""
	}
	return c[0] + "|" + tamano(nombre)
}

// nombreArticulo: nombre corto y legible para el artículo genérico.
func nombreArticulo(nombre string) string {
	n := strings.TrimSpace(nombre)
	if i := strings.Index(n, " ("); i > 0 {
		n = n[:i]
	}
	return n
}

func (a *App) clonarGrafo(ctx context.Context) error {
	g := a.grafo
	inicio := time.Now()
	err := a.clonarGrafoEn(ctx)
	g.mu.Lock()
	defer g.mu.Unlock()
	if err != nil {
		g.error = err.Error()
		log.Println("grafo: no se pudo clonar:", err)
		return err
	}
	g.error = ""
	g.ultimo = time.Now()
	log.Printf("grafo: clonado en %v (%d nodos, %d relaciones)", time.Since(inicio).Round(time.Millisecond), g.nodos, g.rels)
	return nil
}

func (a *App) clonarGrafoEn(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 90*time.Second)
	defer cancel()
	v := time.Now().UnixMilli()

	// 1) Leer todo de SQLite (una consulta a la vez: una sola conexión).
	var tiendas []map[string]any
	rows, err := a.db.Query("SELECT id, nombre, slug, giro, lat, lng, verificada, calificacion FROM tiendas")
	if err != nil {
		return err
	}
	for rows.Next() {
		var id int64
		var nombre, slug, giro string
		var lat, lng *float64
		var verif bool
		var calif float64
		rows.Scan(&id, &nombre, &slug, &giro, &lat, &lng, &verif, &calif)
		t := map[string]any{"id": id, "nombre": strings.TrimSuffix(nombre, " (demo)"), "slug": slug, "giro": giro,
			"verificada": verif, "calificacion": calif, "lat": nil, "lng": nil}
		if lat != nil && lng != nil {
			t["lat"], t["lng"] = *lat, *lng
		}
		tiendas = append(tiendas, t)
	}
	rows.Close()

	ventas := map[int64]int64{}
	rows, err = a.db.Query(`SELECT producto_id, -SUM(cantidad) FROM movimientos
		WHERE tipo IN ('venta','salida','trato_salida') AND creado > ? GROUP BY producto_id`, ahora()-30*24*3600*1000)
	if err != nil {
		return err
	}
	for rows.Next() {
		var id, n int64
		rows.Scan(&id, &n)
		ventas[id] = n
	}
	rows.Close()

	pron := a.pronosticar(0) // pronóstico de demanda de 14 días de cada producto (modelo M5)
	articulos := map[string]string{}
	var vende, ofertas []map[string]any
	rows, err = a.db.Query(`SELECT id, tienda_id, nombre, categoria, precio_c, stock, stock_min, publicado, precio_mayoreo_c,
		minimo_mayoreo, unidad, foto, audiencia FROM productos`)
	if err != nil {
		return err
	}
	for rows.Next() {
		var id, tienda, precio, minimo, mayoreo, minMay int64
		var stock *int64
		var nombre, cat, unidad, foto, audiencia string
		var publicado bool
		rows.Scan(&id, &tienda, &nombre, &cat, &precio, &stock, &minimo, &publicado, &mayoreo, &minMay, &unidad, &foto, &audiencia)
		clave := claveArticulo(nombre)
		if clave == "" {
			continue
		}
		if _, ok := articulos[clave]; !ok {
			articulos[clave] = nombreArticulo(nombre)
		}
		var st any
		if stock != nil {
			st = *stock
		}
		var p50, p90 any
		if pr, ok := pron[id]; ok {
			p50, p90 = pr.P50, pr.P90
		}
		vende = append(vende, map[string]any{"t": tienda, "a": clave, "producto": id, "stock": st, "minimo": minimo,
			"ventas30": ventas[id], "precio": precio, "p50": p50, "p90": p90})
		if publicado && (stock == nil || *stock > 0) {
			if cat == "" {
				cat = "Otros"
			}
			ofertas = append(ofertas, map[string]any{"id": id, "t": tienda, "a": clave, "cat": cat, "nombre": nombre, "precio": precio,
				"mayoreo": mayoreo, "minimo": minMay, "stock": st, "unidad": unidad, "foto": foto, "audiencia": audiencia})
		}
	}
	rows.Close()

	var visibles []map[string]any
	rows, err = a.db.Query("SELECT producto_id, tienda_id FROM producto_audiencia")
	if err != nil {
		return err
	}
	for rows.Next() {
		var p, t int64
		rows.Scan(&p, &t)
		visibles = append(visibles, map[string]any{"p": p, "t": t})
	}
	rows.Close()

	var compras []map[string]any
	rows, err = a.db.Query(`SELECT comprador_id, vendedor_id, COUNT(*), SUM(cantidad), MAX(actualizado) FROM tratos
		WHERE estado IN ('aceptado','entregado') GROUP BY comprador_id, vendedor_id`)
	if err != nil {
		return err
	}
	for rows.Next() {
		var c, vd, n, pz, ult int64
		rows.Scan(&c, &vd, &n, &pz, &ult)
		compras = append(compras, map[string]any{"c": c, "v": vd, "veces": n, "piezas": pz, "ultimo": ult})
	}
	rows.Close()

	arts := make([]map[string]any, 0, len(articulos))
	for c, n := range articulos {
		arts = append(arts, map[string]any{"clave": c, "nombre": n})
	}

	// 2) Escribir el grafo en una transacción; al final se borra lo que no se tocó.
	s := a.grafo.driver.NewSession(ctx, neo4j.SessionConfig{AccessMode: neo4j.AccessModeWrite})
	defer s.Close(ctx)
	for _, q := range reglasGrafo {
		if _, err := s.Run(ctx, q, nil); err != nil {
			return err
		}
	}
	_, err = s.ExecuteWrite(ctx, func(tx neo4j.ManagedTransaction) (any, error) {
		pasos := []struct {
			q string
			p map[string]any
		}{
			{`UNWIND $filas AS x MERGE (t:Tienda {id: x.id})
				SET t.nombre = x.nombre, t.slug = x.slug, t.giro = x.giro, t.verificada = x.verificada,
					t.calificacion = x.calificacion, t.v = $v,
					t.ubicacion = CASE WHEN x.lat IS NULL THEN null ELSE point({latitude: x.lat, longitude: x.lng}) END`,
				map[string]any{"filas": tiendas}},
			{`UNWIND $filas AS x MERGE (a:Articulo {clave: x.clave}) SET a.nombre = x.nombre, a.v = $v`, map[string]any{"filas": arts}},
			{`UNWIND $filas AS x MATCH (t:Tienda {id: x.t}), (a:Articulo {clave: x.a})
				MERGE (t)-[r:VENDE]->(a)
				SET r.producto = x.producto, r.stock = x.stock, r.minimo = x.minimo, r.ventas30 = x.ventas30, r.precio = x.precio,
					r.p50 = x.p50, r.p90 = x.p90, r.v = $v`,
				map[string]any{"filas": vende}},
			{`UNWIND $filas AS x MATCH (t:Tienda {id: x.t}), (a:Articulo {clave: x.a})
				MERGE (p:Producto {id: x.id})
				SET p.nombre = x.nombre, p.precio = x.precio, p.mayoreo = x.mayoreo, p.minimo = x.minimo, p.stock = x.stock,
					p.unidad = x.unidad, p.foto = x.foto, p.audiencia = x.audiencia, p.v = $v
				MERGE (c:Categoria {nombre: x.cat}) SET c.v = $v
				MERGE (t)-[o:OFRECE]->(p) SET o.v = $v
				MERGE (p)-[e:ES]->(a) SET e.v = $v
				MERGE (p)-[k:EN]->(c) SET k.v = $v`,
				map[string]any{"filas": ofertas}},
			{`UNWIND $filas AS x MATCH (p:Producto {id: x.p}), (t:Tienda {id: x.t})
				MERGE (p)-[r:VISIBLE_PARA]->(t) SET r.v = $v`, map[string]any{"filas": visibles}},
			{`UNWIND $filas AS x MATCH (c:Tienda {id: x.c}), (vd:Tienda {id: x.v})
				MERGE (c)-[r:COMPRO_A]->(vd) SET r.veces = x.veces, r.piezas = x.piezas, r.ultimo = x.ultimo, r.v = $v`,
				map[string]any{"filas": compras}},
			{`MATCH ()-[r]->() WHERE r.v IS NULL OR r.v <> $v DELETE r`, nil},
			{`MATCH (n) WHERE n.v IS NULL OR n.v <> $v DETACH DELETE n`, nil},
		}
		for _, p := range pasos {
			params := map[string]any{"v": v}
			for k, x := range p.p {
				params[k] = x
			}
			r, err := tx.Run(ctx, p.q, params)
			if err != nil {
				return nil, err
			}
			if _, err := r.Consume(ctx); err != nil {
				return nil, err
			}
		}
		return nil, nil
	})
	if err != nil {
		return err
	}
	var nodos, rels int64
	if r, err := s.Run(ctx, "MATCH (n) RETURN count(n) AS n", nil); err == nil && r.Next(ctx) {
		nodos, _ = r.Record().Values[0].(int64)
	}
	if r, err := s.Run(ctx, "MATCH ()-[r]->() RETURN count(r) AS n", nil); err == nil && r.Next(ctx) {
		rels, _ = r.Record().Values[0].(int64)
	}
	a.grafo.mu.Lock()
	a.grafo.nodos, a.grafo.rels = nodos, rels
	a.grafo.mu.Unlock()
	return nil
}

// ---------------------------------------------------------------- recomendaciones

type OfertaGrafo struct {
	ID         int64  `json:"id"`
	Nombre     string `json:"nombre"`
	Foto       string `json:"foto,omitempty"`
	PrecioC    int64  `json:"precio_c"`
	MayoreoC   int64  `json:"precio_mayoreo_c"`
	Minimo     int64  `json:"minimo_mayoreo"`
	Unidad     string `json:"unidad"`
	Disponible *int64 `json:"disponible"`
}

type Recomendacion struct {
	Tipo     string       `json:"tipo"` // surtir | nuevo | proveedor | vender | exclusivo
	Titulo   string       `json:"titulo"`
	Motivo   string       `json:"motivo"`
	Cantidad int64        `json:"cantidad,omitempty"` // piezas sugeridas
	Ganancia int64        `json:"ganancia,omitempty"` // % que gana por pieza al revender
	Score    float64      `json:"score"`
	Oferta   *OfertaGrafo `json:"oferta,omitempty"`
	Tienda   *TiendaCorta `json:"tienda,omitempty"` // quién lo ofrece (o a quién venderle)
	KM       *float64     `json:"distancia_km,omitempty"`
}

const visibleCypher = `(p.audiencia = 'todos' OR (p)-[:VISIBLE_PARA]->(yo))`
const kmCypher = `CASE WHEN yo.ubicacion IS NULL OR prov.ubicacion IS NULL THEN null
	ELSE round(point.distance(yo.ubicacion, prov.ubicacion) / 100.0) / 10.0 END`

// Consultas de recomendación. Todas reciben $yo y regresan las mismas columnas.
var consultasGrafo = map[string]string{
	// Se te acaba (o te dura menos de 10 días): la oferta más barata que puedes ver.
	"surtir": `MATCH (yo:Tienda {id: $yo})-[v:VENDE]->(a:Articulo)<-[:ES]-(p:Producto)<-[:OFRECE]-(prov:Tienda)
		WHERE prov <> yo AND ` + visibleCypher + ` AND v.stock IS NOT NULL
		  AND (v.stock <= v.minimo OR (v.p50 IS NOT NULL AND v.stock < v.p50 * 10.0 / 14)
		       OR (v.p50 IS NULL AND v.ventas30 > 0 AND v.stock * 30.0 / v.ventas30 < 10))
		WITH yo, v, a, p, prov, CASE WHEN p.mayoreo > 0 THEN p.mayoreo ELSE p.precio END AS costo ORDER BY costo
		WITH yo, v, a, collect({p: p, prov: prov, costo: costo}) AS ops
		WITH yo, v, a, ops[0] AS m, size(ops) AS opciones
		WITH yo, v, a, m.p AS p, m.prov AS prov, m.costo AS costo, opciones
		RETURN a.nombre AS articulo, p AS p, prov AS prov, ` + kmCypher + ` AS km,
			v.stock AS stock, v.minimo AS minimo, v.ventas30 AS ventas30, v.precio AS miPrecio, costo, opciones,
			0 AS tiendas, 0 AS veces, v.p50 AS p50, v.p90 AS p90
		ORDER BY CASE WHEN v.stock <= 0 THEN 0 ELSE 1 END, v.ventas30 DESC LIMIT 8`,
	// Filtrado colaborativo: tiendas parecidas a la mía (venden lo mismo que yo)
	// venden esto con salida y yo no lo tengo; alguien de la red lo ofrece.
	"nuevo": `MATCH (yo:Tienda {id: $yo})-[:VENDE]->(:Articulo)<-[:VENDE]-(par:Tienda)
		WHERE par <> yo AND NOT (par)-[:OFRECE]->() AND yo.giro <> 'mayoreo'
		WITH yo, par, count(*) AS comunes WHERE comunes >= 3
		MATCH (par)-[pv:VENDE]->(a:Articulo) WHERE NOT (yo)-[:VENDE]->(a) AND pv.ventas30 > 0
		WITH yo, a, count(DISTINCT par) AS tiendas, sum(pv.ventas30) AS vendidas, toInteger(avg(pv.precio)) AS precioPar
		MATCH (a)<-[:ES]-(p:Producto)<-[:OFRECE]-(prov:Tienda) WHERE prov <> yo AND ` + visibleCypher + `
		WITH yo, a, tiendas, vendidas, precioPar, p, prov, CASE WHEN p.mayoreo > 0 THEN p.mayoreo ELSE p.precio END AS costo ORDER BY costo
		WITH yo, a, tiendas, vendidas, precioPar, collect({p: p, prov: prov, costo: costo}) AS ops
		WITH yo, a, tiendas, vendidas, precioPar, ops[0].p AS p, ops[0].prov AS prov, ops[0].costo AS costo, size(ops) AS opciones
		RETURN a.nombre AS articulo, p AS p, prov AS prov, ` + kmCypher + ` AS km,
			null AS stock, 0 AS minimo, vendidas AS ventas30, precioPar AS miPrecio, costo, opciones, tiendas, 0 AS veces
		ORDER BY tiendas DESC, vendidas DESC LIMIT 6`,
	// Proveedores de confianza: a quien ya le compré también ofrece cosas que vendo.
	"proveedor": `MATCH (yo:Tienda {id: $yo})-[c:COMPRO_A]->(prov:Tienda)-[:OFRECE]->(p:Producto)-[:ES]->(a:Articulo)<-[v:VENDE]-(yo)
		WHERE ` + visibleCypher + `
		WITH yo, prov, c, a, v, p, CASE WHEN p.mayoreo > 0 THEN p.mayoreo ELSE p.precio END AS costo
		ORDER BY v.ventas30 DESC
		WITH yo, prov, c, collect({a: a, v: v, p: p, costo: costo})[0] AS m
		RETURN m.a.nombre AS articulo, m.p AS p, prov AS prov, ` + kmCypher + ` AS km,
			m.v.stock AS stock, m.v.minimo AS minimo, m.v.ventas30 AS ventas30, m.v.precio AS miPrecio, m.costo AS costo,
			1 AS opciones, 0 AS tiendas, c.veces AS veces, m.v.p50 AS p50, m.v.p90 AS p90
		ORDER BY c.veces DESC LIMIT 4`,
	// Para vender: tiendas que se están quedando sin algo que yo ofrezco al mayoreo.
	"vender": `MATCH (yo:Tienda {id: $yo})-[:OFRECE]->(p:Producto)-[:ES]->(a:Articulo)<-[v:VENDE]-(prov:Tienda)
		WHERE prov <> yo AND v.stock IS NOT NULL AND v.stock <= v.minimo
		  AND (p.audiencia = 'todos' OR (p)-[:VISIBLE_PARA]->(prov))
		RETURN a.nombre AS articulo, p AS p, prov AS prov, ` + kmCypher + ` AS km,
			v.stock AS stock, v.minimo AS minimo, v.ventas30 AS ventas30, v.precio AS miPrecio,
			CASE WHEN p.mayoreo > 0 THEN p.mayoreo ELSE p.precio END AS costo, 1 AS opciones, 0 AS tiendas, 0 AS veces, v.p50 AS p50, v.p90 AS p90
		ORDER BY v.ventas30 DESC LIMIT 6`,
	// Precios que alguien compartió solo conmigo.
	"exclusivo": `MATCH (p:Producto)-[:VISIBLE_PARA]->(yo:Tienda {id: $yo}), (prov:Tienda)-[:OFRECE]->(p)-[:ES]->(a:Articulo)
		OPTIONAL MATCH (yo)-[v:VENDE]->(a)
		RETURN a.nombre AS articulo, p AS p, prov AS prov, ` + kmCypher + ` AS km,
			v.stock AS stock, coalesce(v.minimo, 0) AS minimo, coalesce(v.ventas30, 0) AS ventas30, coalesce(v.precio, 0) AS miPrecio,
			CASE WHEN p.mayoreo > 0 THEN p.mayoreo ELSE p.precio END AS costo, 1 AS opciones, 0 AS tiendas, 0 AS veces, v.p50 AS p50, v.p90 AS p90
		LIMIT 4`,
}

func entero(x any) int64 {
	switch n := x.(type) {
	case int64:
		return n
	case float64:
		return int64(n)
	}
	return 0
}

func (a *App) recomendacionesGrafo(ctx context.Context, yo int64) ([]Recomendacion, error) {
	if a.grafo == nil {
		return nil, fmt.Errorf("sin grafo")
	}
	ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	s := a.grafo.driver.NewSession(ctx, neo4j.SessionConfig{AccessMode: neo4j.AccessModeRead})
	defer s.Close(ctx)
	var out []Recomendacion
	vistos := map[string]bool{}
	for _, tipo := range []string{"surtir", "exclusivo", "nuevo", "proveedor", "vender"} {
		res, err := s.Run(ctx, consultasGrafo[tipo], map[string]any{"yo": yo})
		if err != nil {
			return nil, err
		}
		for res.Next(ctx) {
			r := res.Record()
			get := func(k string) any { x, _ := r.Get(k); return x }
			pn, ok1 := get("p").(neo4j.Node)
			tn, ok2 := get("prov").(neo4j.Node)
			if !ok1 || !ok2 {
				continue
			}
			pp, tp := pn.Props, tn.Props
			o := &OfertaGrafo{ID: entero(pp["id"]), Nombre: fmt.Sprint(pp["nombre"]), PrecioC: entero(pp["precio"]),
				MayoreoC: entero(pp["mayoreo"]), Minimo: entero(pp["minimo"]), Unidad: fmt.Sprint(pp["unidad"])}
			if f, _ := pp["foto"].(string); f != "" {
				o.Foto = f
			}
			if st, ok := pp["stock"].(int64); ok {
				o.Disponible = &st
			}
			t := &TiendaCorta{ID: entero(tp["id"]), Nombre: fmt.Sprint(tp["nombre"]), Slug: fmt.Sprint(tp["slug"]), Giro: fmt.Sprint(tp["giro"])}
			rec := Recomendacion{Tipo: tipo, Oferta: o, Tienda: t}
			if km, ok := get("km").(float64); ok {
				rec.KM = &km
			}
			articulo := fmt.Sprint(get("articulo"))
			stock, tieneStock := get("stock").(int64)
			minimo, ventas, miPrecio, costo := entero(get("minimo")), entero(get("ventas30")), entero(get("miPrecio")), entero(get("costo"))
			opciones, tiendas, veces := entero(get("opciones")), entero(get("tiendas")), entero(get("veces"))
			if miPrecio > 0 && costo > 0 && costo < miPrecio {
				rec.Ganancia = (miPrecio - costo) * 100 / miPrecio
			}
			// Cuántas pedir: lo que venderás en 2 semanas según el modelo de demanda (p90, para no
			// quedarte sin mercancía) menos lo que tienes; sin modelo, la mitad de lo vendido en 30 días.
			p50, conP50 := get("p50").(float64)
			p90, _ := get("p90").(float64)
			sugerida := int64(math.Ceil(float64(ventas) / 2))
			if conP50 {
				sugerida = int64(math.Ceil(p90))
			}
			if tieneStock {
				sugerida -= stock
			}
			if sugerida < minimo {
				sugerida = minimo
			}
			if o.Minimo > 0 && sugerida < o.Minimo {
				sugerida = o.Minimo
			}
			precioTxt := pesosTxt(costo)
			if o.MayoreoC > 0 && o.Minimo > 1 {
				precioTxt += fmt.Sprintf(" desde %d", o.Minimo)
			}
			switch tipo {
			case "surtir":
				rec.Titulo = "Surte " + articulo
				dias := ""
				if tieneStock && stock <= 0 {
					dias = "Se te acabó"
				} else if conP50 && p50 > 0 && tieneStock {
					dias = fmt.Sprintf("Te alcanza para ~%d días: en 2 semanas venderás unas %d (hasta %d)",
						max(1, int64(float64(stock)/(p50/14))), int64(math.Round(p50)), int64(math.Ceil(p90)))
				} else if ventas > 0 && tieneStock {
					dias = fmt.Sprintf("Te alcanza para ~%d días", max(1, stock*30/ventas))
				} else {
					dias = fmt.Sprintf("Te quedan %d", stock)
				}
				rec.Motivo = fmt.Sprintf("%s. %s lo tiene a %s", dias, t.Nombre, precioTxt)
				if opciones > 1 {
					rec.Motivo += fmt.Sprintf(" (el más barato de %d proveedores)", opciones)
				}
				rec.Cantidad = sugerida
				rec.Score = 100 + float64(ventas)
				if tieneStock && stock <= 0 {
					rec.Score += 100
				}
			case "nuevo":
				rec.Titulo = "Empieza a vender " + articulo
				quien := fmt.Sprintf("%d tiendas parecidas a la tuya lo venden", tiendas)
				if tiendas == 1 {
					quien = "Una tienda parecida a la tuya lo vende"
				}
				rec.Motivo = fmt.Sprintf("%s (%s piezas en 30 días) y tú no. %s lo da a %s", quien, cantidadTxt(ventas), t.Nombre, precioTxt)
				if miPrecio > 0 {
					rec.Motivo += fmt.Sprintf("; ellas lo venden a ~%s", pesosTxt(miPrecio))
				}
				rec.Cantidad = max(o.Minimo, 6)
				rec.Score = 60 + float64(tiendas*10)
			case "proveedor":
				rec.Titulo = "Tu proveedor de confianza"
				rec.Motivo = fmt.Sprintf("Ya le compraste %d %s a %s. También tiene %s a %s", veces, plural(veces, "vez", "veces"), t.Nombre, articulo, precioTxt)
				rec.Score = 50 + float64(veces*5)
			case "vender":
				rec.Titulo = "Véndele a " + t.Nombre
				rec.Motivo = fmt.Sprintf("Se está quedando sin %s (le quedan %d) y tú lo ofreces a %s. Mándale tu catálogo", articulo, stock, precioTxt)
				rec.Score = 70 + float64(ventas)
			case "exclusivo":
				rec.Titulo = "Precio solo para ti"
				rec.Motivo = fmt.Sprintf("%s te compartió %s a %s; nadie más ve ese precio", t.Nombre, o.Nombre, precioTxt)
				rec.Score = 90
			}
			clave := fmt.Sprintf("%s:%d:%d", tipo, o.ID, t.ID)
			if tipo == "surtir" || tipo == "nuevo" {
				clave = "art:" + articulo
			}
			if vistos[clave] {
				continue
			}
			vistos[clave] = true
			out = append(out, rec)
		}
		if err := res.Err(); err != nil {
			return nil, err
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	return out, nil
}

func plural(n int64, uno, varios string) string {
	if n == 1 {
		return uno
	}
	return varios
}

// GET /api/grafo/recomendaciones
func (a *App) verRecomendaciones(w http.ResponseWriter, r *http.Request) {
	rs, err := a.recomendacionesGrafo(r.Context(), tiendaDe(r))
	if err != nil {
		log.Println("grafo: recomendaciones:", err)
		manejar(w, map[string]any{"recomendaciones": []Recomendacion{}, "grafo": false}, nil)
		return
	}
	if rs == nil {
		rs = []Recomendacion{}
	}
	manejar(w, map[string]any{"recomendaciones": rs, "grafo": true}, nil)
}

// GET /api/grafo/estado
func (a *App) estadoGrafo(w http.ResponseWriter, r *http.Request) {
	if a.grafo == nil {
		manejar(w, map[string]any{"activo": false}, nil)
		return
	}
	a.grafo.mu.Lock()
	defer a.grafo.mu.Unlock()
	manejar(w, map[string]any{"activo": a.grafo.error == "" && !a.grafo.ultimo.IsZero(), "nodos": a.grafo.nodos,
		"relaciones": a.grafo.rels, "clonado": a.grafo.ultimo.UnixMilli(), "error": a.grafo.error}, nil)
}

// ---------------------------------------------------------------- mapa (para dibujar)

type NodoMapa struct {
	ID       string `json:"id"`
	Tipo     string `json:"tipo"` // yo | tienda | articulo | producto
	Etiqueta string `json:"etiqueta"`
	Foto     string `json:"foto,omitempty"`
	Detalle  string `json:"detalle,omitempty"`
}

type LigaMapa struct {
	De   string `json:"de"`
	A    string `json:"a"`
	Tipo string `json:"tipo"`
}

// GET /api/grafo/mapa: mi vecindario en el grafo (lo que vendo, quién lo ofrece y
// tiendas parecidas), recortado para que se pueda dibujar en el teléfono.
func (a *App) mapaGrafo(w http.ResponseWriter, r *http.Request) {
	if a.grafo == nil {
		manejar(w, nil, fallo(503, "El grafo no está activo"))
		return
	}
	yo := tiendaDe(r)
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	s := a.grafo.driver.NewSession(ctx, neo4j.SessionConfig{AccessMode: neo4j.AccessModeRead})
	defer s.Close(ctx)
	res, err := s.Run(ctx, `
		MATCH (yo:Tienda {id: $yo})
		OPTIONAL MATCH (yo)-[v:VENDE]->(a:Articulo)
		WITH yo, a, v ORDER BY v.ventas30 DESC
		WITH yo, collect(a)[0..14] AS arts
		UNWIND arts AS a
		OPTIONAL MATCH (a)<-[:ES]-(p:Producto)<-[:OFRECE]-(prov:Tienda)
		WHERE prov <> yo AND `+visibleCypher+`
		WITH yo, a, p, prov ORDER BY CASE WHEN p.mayoreo > 0 THEN p.mayoreo ELSE p.precio END
		WITH yo, a, collect({p: p, prov: prov})[0..2] AS ofs
		OPTIONAL MATCH (a)<-[:VENDE]-(par:Tienda) WHERE par <> yo AND NOT (par)-[:OFRECE]->()
		RETURN yo, a, ofs, collect(DISTINCT par)[0..3] AS pares`, map[string]any{"yo": yo})
	if err != nil {
		manejar(w, nil, err)
		return
	}
	nodos := map[string]NodoMapa{}
	var ligas []LigaMapa
	ligaVista := map[string]bool{}
	ligar := func(de, aa, tipo string) {
		k := de + ">" + aa + ":" + tipo
		if !ligaVista[k] {
			ligaVista[k] = true
			ligas = append(ligas, LigaMapa{de, aa, tipo})
		}
	}
	idTienda := func(n neo4j.Node) string { return fmt.Sprintf("t%d", entero(n.Props["id"])) }
	for res.Next(ctx) {
		rec := res.Record()
		yoN, _ := rec.Values[0].(neo4j.Node)
		nodos[idTienda(yoN)] = NodoMapa{ID: idTienda(yoN), Tipo: "yo", Etiqueta: fmt.Sprint(yoN.Props["nombre"])}
		an, ok := rec.Values[1].(neo4j.Node)
		if !ok {
			continue
		}
		aid := "a:" + fmt.Sprint(an.Props["clave"])
		nodos[aid] = NodoMapa{ID: aid, Tipo: "articulo", Etiqueta: fmt.Sprint(an.Props["nombre"])}
		ligar(idTienda(yoN), aid, "VENDE")
		if ofs, ok := rec.Values[2].([]any); ok {
			for _, x := range ofs {
				m, _ := x.(map[string]any)
				pn, ok1 := m["p"].(neo4j.Node)
				tn, ok2 := m["prov"].(neo4j.Node)
				if !ok1 || !ok2 {
					continue
				}
				pid := fmt.Sprintf("p%d", entero(pn.Props["id"]))
				costo := entero(pn.Props["mayoreo"])
				if costo <= 0 {
					costo = entero(pn.Props["precio"])
				}
				foto, _ := pn.Props["foto"].(string)
				nodos[pid] = NodoMapa{ID: pid, Tipo: "producto", Etiqueta: fmt.Sprint(pn.Props["nombre"]), Foto: foto, Detalle: pesosTxt(costo)}
				nodos[idTienda(tn)] = NodoMapa{ID: idTienda(tn), Tipo: "proveedor", Etiqueta: fmt.Sprint(tn.Props["nombre"])}
				ligar(pid, aid, "ES")
				ligar(idTienda(tn), pid, "OFRECE")
			}
		}
		if pares, ok := rec.Values[3].([]any); ok {
			for _, x := range pares {
				if tn, ok := x.(neo4j.Node); ok {
					if _, ya := nodos[idTienda(tn)]; !ya {
						nodos[idTienda(tn)] = NodoMapa{ID: idTienda(tn), Tipo: "tienda", Etiqueta: fmt.Sprint(tn.Props["nombre"])}
					}
					ligar(idTienda(tn), aid, "VENDE")
				}
			}
		}
	}
	if err := res.Err(); err != nil {
		manejar(w, nil, err)
		return
	}
	lista := make([]NodoMapa, 0, len(nodos))
	for _, n := range nodos {
		lista = append(lista, n)
	}
	sort.Slice(lista, func(i, j int) bool { return lista[i].ID < lista[j].ID })
	if ligas == nil {
		ligas = []LigaMapa{}
	}
	manejar(w, map[string]any{"nodos": lista, "ligas": ligas}, nil)
}

// resultadoDe convierte una recomendación en tarjeta de la red (para «Negociar»).
func (r Recomendacion) resultado() Resultado {
	o := r.Oferta
	return Resultado{Tipo: "producto", ID: o.ID, Titulo: o.Nombre, Detalle: r.Motivo, PrecioC: o.PrecioC, PrecioMayoreoC: o.MayoreoC,
		MinimoMayoreo: o.Minimo, Unidad: o.Unidad, Foto: o.Foto, Disponible: o.Disponible, Tienda: r.Tienda, DistanciaKm: r.KM}
}
