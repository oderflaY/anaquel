package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"math/rand"
	"net/http"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

// Red de demostración: varias tiendas, proveedores y servicios de Durango que
// se venden entre ellos. Tres cuentas para entrar (contraseña demo1234):
//   demo@anaquel.mx      Abarrotes Doña Lupita (tiendita)
//   ropa@anaquel.mx      Boutique Alondra (ropa)
//   mayoreo@anaquel.mx   Gorras y Uniformes El Tunal (mayoreo)

type demoArticulo struct {
	nombre, categoria, unidad, desc string
	precio, mayoreo, minimo         int64 // centavos; mínimo de piezas para mayoreo
	stock, min, ventas30            int64
	publicado                       bool
}

type demoAnuncio struct{ tipo, titulo, texto, precio string }

type demoTienda struct {
	email, nombre, slug, giro, desc, tel, dir string
	lat, lng                                  float64
	articulos                                 []demoArticulo
	anuncios                                  []demoAnuncio
	solicitudes                               []Solicitud
}

var redDemo = []demoTienda{
	{
		email: "ropa@anaquel.mx", nombre: "Boutique Alondra (demo)", slug: "boutique-alondra", giro: "ropa",
		desc: "Ropa casual para dama y caballero en el centro.", tel: "6181200001", dir: "Av. 20 de Noviembre 410, Centro",
		lat: 24.0236, lng: -104.6612,
		articulos: []demoArticulo{
			{"Sudadera con capucha", "Invierno", "pza", "", 34900, 0, 0, 6, 15, 31, false},
			{"Chamarra de mezclilla", "Invierno", "pza", "", 59900, 0, 0, 3, 8, 14, false},
			{"Bufanda tejida", "Invierno", "pza", "", 9900, 0, 0, 0, 10, 18, false},
			{"Pijama polar dama", "Invierno", "pza", "", 24900, 0, 0, 4, 10, 11, false},
			{"Playera básica algodón", "Básicos", "pza", "", 12900, 0, 0, 40, 15, 22, false},
			{"Jeans dama corte recto", "Básicos", "pza", "", 39900, 0, 0, 25, 10, 9, false},
			{"Gorra bordada", "Accesorios", "pza", "", 17900, 0, 0, 12, 10, 6, false},
			{"Short deportivo", "Verano", "pza", "Short de poliéster, tallas CH a XG. Últimas piezas de la temporada.", 14900, 9000, 10, 30, 5, 0, true},
		},
		anuncios: []demoAnuncio{{"promocion", "Liquidación de shorts deportivos", "30 shorts de verano a precio de mayoreo para otras tiendas.", "$90 c/u desde 10"}},
	},
	{
		email: "mayoreo@anaquel.mx", nombre: "Gorras y Uniformes El Tunal (demo)", slug: "gorras-el-tunal", giro: "mayoreo",
		desc: "Fabricamos gorras, playeras y uniformes. Mayoreo desde 50 piezas, bordado y sublimado en casa.", tel: "6181200002",
		dir: "Blvd. Domingo Arrieta 1200, Col. El Tunal", lat: 24.0471, lng: -104.6176,
		articulos: []demoArticulo{
			{"Gorra beisbolera de gabardina", "Gorras", "pza", "Ajustable, 8 colores. Se puede bordar tu logotipo.", 8500, 4500, 50, 1200, 200, 380, true},
			{"Gorra trucker con malla", "Gorras", "pza", "Frente de espuma para sublimar o bordar.", 9500, 5200, 50, 450, 100, 120, true},
			{"Gorro tejido de invierno", "Invierno", "pza", "Acrílico, varios colores, talla única.", 8900, 3800, 24, 600, 100, 210, true},
			{"Playera para sublimar", "Playeras", "pza", "Poliéster blanco, tallas niño y adulto.", 7000, 3800, 50, 800, 150, 260, true},
			{"Chaleco de uniforme", "Uniformes", "pza", "Gabardina con bolsas, bordado opcional.", 28000, 19000, 20, 120, 30, 25, true},
		},
		anuncios: []demoAnuncio{
			{"servicio", "Bordado de logotipo en gorras y playeras", "Bordamos el logo de tu escuela, negocio o equipo. Entrega en 5 días.", "$25 por pieza desde 50"},
			{"promocion", "Gorras para eventos escolares y empresas", "Paquetes de 100 a 2,000 gorras con logo. Pregunta por tiempos de entrega.", "Desde $45 la pieza"},
		},
	},
	{
		email: "textiles@anaquel.mx", nombre: "Textiles Guadiana (demo)", slug: "textiles-guadiana", giro: "mayoreo",
		desc: "Distribuidor de ropa de invierno y básicos para tiendas de ropa.", tel: "6181200003",
		dir: "Calle Hidalgo 85, Zona Centro", lat: 24.0262, lng: -104.6701,
		articulos: []demoArticulo{
			{"Chamarra de mezclilla forrada", "Invierno", "pza", "Forro de borrega, tallas CH a XG.", 65000, 38000, 12, 300, 50, 90, true},
			{"Sudadera con capucha unisex", "Invierno", "pza", "Algodón perchado, 6 colores.", 32000, 17500, 12, 500, 80, 160, true},
			{"Bufanda tejida", "Invierno", "pza", "Estambre suave, 10 colores.", 9000, 4200, 24, 900, 100, 240, true},
			{"Guantes de estambre", "Invierno", "par", "Talla única.", 6000, 2500, 24, 700, 100, 150, true},
			{"Pijama polar dama", "Invierno", "pza", "Conjunto de dos piezas.", 28000, 15000, 12, 260, 40, 70, true},
			{"Suéter navideño", "Navidad", "pza", "Diseños de reno y pino, tallas niño y adulto.", 35000, 19000, 12, 180, 30, 40, true},
		},
		anuncios: []demoAnuncio{{"producto", "Todo para el invierno a precio de mayoreo", "Chamarras, sudaderas, bufandas, guantes y pijamas. Surtimos desde 12 piezas.", "Mayoreo desde 12 pzas"}},
	},
	{
		email: "papeleria@anaquel.mx", nombre: "Papelería El Estudiante (demo)", slug: "papeleria-el-estudiante", giro: "papeleria",
		desc: "Papelería escolar y artículos para fiestas frente a la secundaria.", tel: "6181200004",
		dir: "Calle Constitución 300, Col. Nueva Vizcaya", lat: 24.0333, lng: -104.6488,
		articulos: []demoArticulo{
			{"Cuaderno profesional 100 hojas", "Escolar", "pza", "", 4500, 0, 0, 60, 20, 35, false},
			{"Papel china (paquete 10)", "Fiestas", "paquete", "", 2500, 1500, 20, 120, 20, 30, true},
			{"Papel picado navideño", "Fiestas", "tira", "Tiras de 5 m.", 3500, 2000, 20, 200, 30, 12, true},
		},
		solicitudes: []Solicitud{
			{Que: "gorras", Cantidad: 1000, Detalle: "Con el logo de la secundaria bordado, para el festival de fin de año. Las necesito antes del 12 de diciembre."},
			{Que: "piñatas", Cantidad: 30, Detalle: "Piñatas navideñas de estrella para las posadas."},
		},
	},
	{
		email: "abarrotes.mayoreo@anaquel.mx", nombre: "Abarrotes Mayoreo Guadiana (demo)", slug: "abarrotes-mayoreo-guadiana", giro: "mayoreo",
		desc: "Surtimos a tienditas: abarrotes, botanas y temporada. Entrega a domicilio los martes.", tel: "6181000012",
		dir: "Carretera a Mazatlán km 2", lat: 24.0158, lng: -104.6899,
		articulos: []demoArticulo{
			{"Arroz 1 kg", "Abarrotes", "pza", "", 3400, 2600, 20, 400, 50, 150, true},
			{"Frijol pinto 1 kg", "Abarrotes", "pza", "", 4200, 3300, 20, 350, 50, 120, true},
			{"Aceite vegetal 1 L", "Abarrotes", "pza", "", 4800, 3900, 12, 240, 40, 90, true},
			{"Kit de ponche navideño", "Temporada", "kit", "Tejocote, guayaba, caña y piloncillo para 20 tazas.", 12000, 8500, 10, 150, 20, 15, true},
			{"Cacahuate japonés 1 kg", "Botanas", "pza", "", 9000, 6800, 10, 90, 20, 30, true},
			{"Agua natural 1 L (caja 12)", "Bebidas", "caja", "", 14400, 10800, 5, 200, 30, 60, true},
			{"Leche entera 1 L", "Lácteos", "pza", "Caducidad mínima de 10 días.", 2700, 2250, 12, 180, 30, 140, true},
			{"Azúcar estándar 1 kg", "Abarrotes", "pza", "", 3000, 2450, 20, 300, 50, 110, true},
			{"Atún en lata 140 g", "Enlatados", "lata", "", 2300, 1850, 24, 260, 48, 120, true},
			{"Papel higiénico 4 rollos", "Higiene", "paquete", "", 3500, 2700, 10, 120, 20, 45, true},
		},
		anuncios: []demoAnuncio{{"servicio", "Surtimos tu tiendita a domicilio", "Haz tu pedido por la red antes del lunes y te lo llevamos el martes sin costo desde $1,500.", "Envío gratis desde $1,500"}},
	},
	{
		email: "fletes@anaquel.mx", nombre: "Fletes Durango Express (demo)", slug: "fletes-durango-express", giro: "servicios",
		desc: "Fletes y repartos en camioneta dentro de la ciudad.", tel: "6181200006",
		dir: "Col. Valle del Sur", lat: 24.0005, lng: -104.6455,
		anuncios: []demoAnuncio{{"servicio", "Flete local en camioneta 3.5 toneladas", "Movemos tu mercancía entre tiendas y proveedores de la ciudad, el mismo día.", "$350 por viaje"}},
	},
	{
		email: "imprenta@anaquel.mx", nombre: "Imprenta Rápida Guadiana (demo)", slug: "imprenta-rapida-guadiana", giro: "servicios",
		desc: "Volantes, lonas, etiquetas y tarjetas de presentación.", tel: "6181200007",
		dir: "Calle Pino Suárez 120, Centro", lat: 24.0244, lng: -104.6633,
		anuncios: []demoAnuncio{
			{"servicio", "Lonas y volantes para tu temporada", "Anuncia tus ofertas de Navidad: lona 1×2 m y 1,000 volantes.", "Lona $250 · 1,000 volantes $450"},
			{"servicio", "Etiquetas con código de barras", "Imprimimos etiquetas para que escanees tus productos con la pistola.", "$180 el millar"},
		},
	},
}

func esCuentaDemo(email string) bool {
	if email == DemoEmail {
		return true
	}
	for _, t := range redDemo {
		if t.email == email {
			return true
		}
	}
	return false
}

// sembrarRed crea las tiendas de la red demo que falten.
func (a *App) sembrarRed() error {
	hash, _ := bcrypt.GenerateFromPassword([]byte(DemoPassword), bcrypt.DefaultCost)
	rnd := rand.New(rand.NewSource(7))
	t := ahora()
	ids := map[string]int64{}
	nuevas := false
	for _, d := range redDemo {
		var id int64
		if a.db.QueryRow("SELECT id FROM tiendas WHERE email = ?", d.email).Scan(&id) == nil {
			ids[d.slug] = id
			continue
		}
		nuevas = true
		slug := a.slugLibre(d.slug) // antes de la transacción: hay una sola conexión
		tx, err := a.db.Begin()
		if err != nil {
			return err
		}
		res, err := tx.Exec(`INSERT INTO tiendas (nombre, slug, email, pass_hash, telefono, direccion, lat, lng, giro, descripcion, creado)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, d.nombre, slug, d.email, string(hash), d.tel, d.dir, d.lat, d.lng, d.giro, d.desc, t)
		if err != nil {
			tx.Rollback()
			return err
		}
		id, _ = res.LastInsertId()
		ids[d.slug] = id
		for i, p := range d.articulos {
			codigo := fmt.Sprintf("750%04d%05d", id, i+1)
			r, err := tx.Exec(`INSERT INTO productos (tienda_id, codigo, nombre, categoria, precio_c, stock, stock_min, publicado,
				precio_mayoreo_c, minimo_mayoreo, unidad, descripcion, actualizado) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
				id, codigo, p.nombre, p.categoria, p.precio, p.stock, p.min, p.publicado, p.mayoreo, p.minimo, p.unidad, p.desc, t)
			if err != nil {
				tx.Rollback()
				return err
			}
			pid, _ := r.LastInsertId()
			if err := historialVentas(tx, rnd, id, pid, p.stock, p.ventas30, t); err != nil {
				tx.Rollback()
				return err
			}
		}
		for i, an := range d.anuncios {
			tx.Exec("INSERT INTO anuncios (tienda_id, tipo, titulo, texto, precio, creado) VALUES (?, ?, ?, ?, ?, ?)",
				id, an.tipo, an.titulo, an.texto, an.precio, t-int64(i+1)*3600_000)
		}
		for i, s := range d.solicitudes {
			tx.Exec("INSERT INTO solicitudes (tienda_id, que, cantidad, detalle, creado) VALUES (?, ?, ?, ?, ?)",
				id, s.Que, s.Cantidad, s.Detalle, t-int64(i+1)*7200_000)
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	if !nuevas {
		return nil
	}
	// Doña Lupita: giro, descripción e historial de ventas.
	var lupita int64
	if a.db.QueryRow("SELECT id FROM tiendas WHERE email = ?", DemoEmail).Scan(&lupita) == nil {
		a.db.Exec("UPDATE tiendas SET giro = 'abarrotes', descripcion = 'Tiendita de la esquina desde 1998.' WHERE id = ?", lupita)
		var n int
		a.db.QueryRow("SELECT COUNT(*) FROM movimientos WHERE tienda_id = ?", lupita).Scan(&n)
		if n == 0 {
			tx, err := a.db.Begin()
			if err != nil {
				return err
			}
			rows, _ := tx.Query("SELECT id, COALESCE(stock, 0), stock_min FROM productos WHERE tienda_id = ?", lupita)
			type fila struct{ id, stock, min int64 }
			var fs []fila
			for rows.Next() {
				var f fila
				rows.Scan(&f.id, &f.stock, &f.min)
				fs = append(fs, f)
			}
			rows.Close()
			for _, f := range fs {
				historialVentas(tx, rnd, lupita, f.id, f.stock, f.min*4+rnd.Int63n(6), t)
			}
			if err := tx.Commit(); err != nil {
				return err
			}
		}
	}
	// Un trato a medio negociar: la boutique le compra sudaderas a Textiles.
	boutique, textiles := ids["boutique-alondra"], ids["textiles-guadiana"]
	var sudadera int64
	if a.db.QueryRow("SELECT id FROM productos WHERE tienda_id = ? AND nombre = 'Sudadera con capucha unisex'", textiles).Scan(&sudadera) == nil && boutique > 0 {
		hace := t - 5*3600_000
		res, err := a.db.Exec(`INSERT INTO tratos (comprador_id, vendedor_id, producto_id, titulo, cantidad, precio_c, propuso_id, creado, actualizado)
			VALUES (?, ?, ?, 'Sudadera con capucha unisex', 36, 17500, ?, ?, ?)`, boutique, textiles, sudadera, textiles, hace, t-3600_000)
		if err == nil {
			tr, _ := res.LastInsertId()
			a.db.Exec(`INSERT INTO trato_mensajes (trato_id, tienda_id, texto, cantidad, precio_c, creado) VALUES
				(?, ?, 'Hola, necesito 36 sudaderas surtidas en gris, negro y vino. ¿Me las deja en $165?', 36, 16500, ?),
				(?, ?, 'Hola Alondra. A $165 no me sale, pero en 36 piezas se las dejo en $175 y se las llevo el jueves.', 36, 17500, ?)`,
				tr, boutique, hace, tr, textiles, t-3600_000)
			a.avisar(boutique, fmt.Sprintf("trato:%d:msg", tr), "trato", "Textiles Guadiana (demo) respondió en el trato de Sudadera con capucha unisex: te las deja en $175 c/u.",
				fmt.Sprintf("/panel/tratos/%d", tr))
		}
	}
	// Chat del mayorista con Doña Lupita: le mandó su catálogo y quedó una respuesta sin leer.
	if surte := ids["abarrotes-mayoreo-guadiana"]; surte > 0 && lupita > 0 {
		if chat, err := a.chatEntre(lupita, surte); err == nil {
			items, _ := a.miCatalogo(surte, nil, true)
			cat, _ := json.Marshal(datosCatalogo{Items: items})
			msgs := []struct {
				de          int64
				tipo, texto string
				datos       string
				hace        int64
			}{
				{surte, "texto", "Buenas, Doña Lupita. Ya salió la promo de la semana: pida antes del lunes y se lo llevo el martes sin costo desde $1,500.", "", 26},
				{surte, "catalogo", fmt.Sprintf("Le comparto mi catálogo (%d productos)", len(items)), string(cat), 26},
				{lupita, "texto", "Gracias, Don Chuy. ¿La leche sigue a ese precio? Ando corta de leche y azúcar.", "", 25},
				{surte, "texto", "Sí, la leche a $22.50 llevando 12 o más. El azúcar también trae precio de mayoreo. Arme su pedido en el catálogo y se lo confirmo. 👍", "", 2},
			}
			var leida int64
			for _, m := range msgs {
				res, err := a.db.Exec("INSERT INTO chat_mensajes (chat_id, tienda_id, tipo, texto, datos, creado) VALUES (?, ?, ?, ?, ?, ?)",
					chat, m.de, m.tipo, m.texto, m.datos, t-m.hace*3600_000)
				if err == nil && m.de == lupita {
					leida, _ = res.LastInsertId()
				}
			}
			a.db.Exec(`UPDATE chats SET actualizado = ?, leido_a = CASE WHEN tienda_a = ? THEN ? ELSE leido_a END,
				leido_b = CASE WHEN tienda_b = ? THEN ? ELSE leido_b END WHERE id = ?`, t-2*3600_000, lupita, leida, lupita, leida, chat)
			a.avisar(lupita, fmt.Sprintf("chat:%d:demo", chat), "chat", "Abarrotes Mayoreo Guadiana: la leche a $22.50 llevando 12 o más.", fmt.Sprintf("/panel/chats/%d", chat))
		}
	}
	// El trato de las sudaderas también aparece en el chat de la boutique con Textiles.
	if boutique > 0 && textiles > 0 {
		var tr int64
		if a.db.QueryRow("SELECT id FROM tratos WHERE comprador_id = ? AND vendedor_id = ?", boutique, textiles).Scan(&tr) == nil {
			if chat, err := a.chatEntre(boutique, textiles); err == nil {
				d, _ := json.Marshal(datosPedido{Tratos: []int64{tr}})
				a.db.Exec("INSERT INTO chat_mensajes (chat_id, tienda_id, tipo, texto, datos, creado) VALUES (?, ?, 'pedido', 'Pedido de 1 producto', ?, ?)", chat, boutique, string(d), t-5*3600_000)
				a.db.Exec("INSERT INTO chat_mensajes (chat_id, tienda_id, tipo, texto, creado) VALUES (?, ?, 'texto', 'A $165 no me sale, pero en 36 piezas se las dejo en $175 y se las llevo el jueves.', ?)", chat, textiles, t-3600_000)
				a.db.Exec("UPDATE chats SET actualizado = ? WHERE id = ?", t-3600_000, chat)
			}
		}
	}
	// El buscador une las solicitudes con las vitrinas (aquí nacen los avisos de las gorras).
	rows, err := a.db.Query("SELECT id FROM solicitudes WHERE estado = 'abierta'")
	if err != nil {
		return err
	}
	var sols []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		sols = append(sols, id)
	}
	rows.Close()
	for _, s := range sols {
		a.buscarOfertas(s)
	}
	return nil
}

// historialVentas inventa un mes de movimientos que termina en `stock`:
// una entrada hace 35 días y ventas repartidas en los últimos 30.
func historialVentas(tx *sql.Tx, rnd *rand.Rand, tienda, producto, stock, ventas, t int64) error {
	dia := int64(24 * 3600_000)
	saldo := stock + ventas
	if saldo > 0 {
		if _, err := tx.Exec(`INSERT INTO movimientos (tienda_id, producto_id, tipo, cantidad, saldo, nota, creado)
			VALUES (?, ?, 'entrada', ?, ?, 'Compra a proveedor', ?)`, tienda, producto, saldo, saldo, t-35*dia); err != nil {
			return err
		}
	}
	quedan := ventas
	cuando := t - 30*dia
	for quedan > 0 {
		n := min(quedan, 1+rnd.Int63n(max(1, ventas/6+1)))
		quedan -= n
		saldo -= n
		cuando += dia * 30 / max(1, ventas/2+1)
		if cuando > t-3600_000 {
			cuando = t - 3600_000
		}
		if _, err := tx.Exec(`INSERT INTO movimientos (tienda_id, producto_id, tipo, cantidad, saldo, nota, creado)
			VALUES (?, ?, 'venta', ?, ?, '', ?)`, tienda, producto, -n, saldo, cuando); err != nil {
			return err
		}
	}
	return nil
}

// reiniciarDemo deja toda la red demo como nueva (para presentarla otra vez).
// Solo una cuenta demo puede hacerlo.
func (a *App) reiniciarDemo(w http.ResponseWriter, r *http.Request) {
	var email string
	a.db.QueryRow("SELECT email FROM tiendas WHERE id = ?", tiendaDe(r)).Scan(&email)
	if !esCuentaDemo(email) {
		manejar(w, nil, fallo(403, "Solo las tiendas demo se pueden reiniciar"))
		return
	}
	emails := []any{DemoEmail}
	for _, t := range redDemo {
		emails = append(emails, t.email)
	}
	marcas := strings.TrimSuffix(strings.Repeat("?, ", len(emails)), ", ")
	if _, err := a.db.Exec("DELETE FROM tiendas WHERE email IN ("+marcas+")", emails...); err != nil {
		manejar(w, nil, err)
		return
	}
	if err := a.sembrarDemo(); err != nil {
		manejar(w, nil, err)
		return
	}
	if err := a.sembrarRed(); err != nil {
		manejar(w, nil, err)
		return
	}
	var id int64
	a.db.QueryRow("SELECT id FROM tiendas WHERE email = ?", email).Scan(&id)
	a.responderSesion(w, id)
}
