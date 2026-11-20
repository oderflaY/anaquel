package main

import (
	"embed"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Perfiles de tienda (unos más completos que otros, como en la vida real),
// fotos reales de producto para la red demo y notificaciones para el teléfono.

//go:embed demo_fotos/*.jpg
var fotosDemo embed.FS

// Qué foto le toca a un producto según su nombre (la primera coincidencia gana).
var fotoPorNombre = []struct{ trae, archivo string }{
	{"refresco de cola 2", "cola-2l"}, {"refresco de cola", "cola-600"}, {"sabor naranja", "naranja-600"},
	{"jugo de naranja", "jugo-naranja"}, {"agua natural", "agua"}, {"energizante", "energizante"},
	{"arroz", "arroz"}, {"aceite", "aceite"}, {"azúcar", "azucar"}, {"atún", "atun"}, {"harina de maíz", "harina-maiz"},
	{"sal de mesa", "sal"}, {"pasta para sopa", "pasta"}, {"sardina", "sardina"}, {"café", "cafe"},
	{"leche en polvo", "leche-polvo"}, {"leche deslactosada", "leche-deslactosada"}, {"leche entera", "leche"},
	{"galletas marías", "galletas-marias"}, {"galletas con chocolate", "galletas-chocolate"}, {"jabón de lavandería", "jabon"},
	{"papas fritas", "papas"}, {"cacahuate", "cacahuate"}, {"palomitas", "palomitas"}, {"chicles", "chicles"},
	{"paletas", "paletas"}, {"chocolate de leche", "chocolate"}, {"yogurt", "yogurt"}, {"queso panela", "queso-panela"},
	{"queso fresco", "queso-fresco"}, {"crema", "crema"}, {"jamón", "jamon"}, {"salchicha", "salchicha"}, {"chorizo", "chorizo"},
}

func fotoDe(nombre string) string {
	n := strings.ToLower(nombre)
	for _, f := range fotoPorNombre {
		if strings.Contains(n, f.trae) {
			return "/media/demo/" + f.archivo + ".jpg"
		}
	}
	return ""
}

// Perfil «de vitrina» de cada tienda demo. Las que tienen más datos son las
// que más venden; la de Doña Lupita queda sencilla a propósito.
type perfilDemo struct {
	portada, horario, entrega, credito, pagos, zona, etiquetas string
	pedidoMinimoC                                              int64
	desde, clientes, resenas                                   int
	verificada                                                 bool
	calificacion                                               float64
}

var perfilesDemo = map[string]perfilDemo{
	// El mayorista que surte a las tienditas: el perfil más completo.
	"distribuidora@anaquel.mx": {
		portada: "arroz", horario: "Lun a sáb 6:00 – 18:00 · Dom 7:00 – 13:00",
		entrega: "Reparto diario en Durango capital; pedidos antes de las 12 llegan el mismo día",
		credito: "Crédito a 15 días para tiendas con 3 pedidos pagados", pagos: "Efectivo, transferencia, tarjeta y CoDi",
		zona: "Durango, Gómez Palacio, Lerdo y Santiago Papasquiaro", etiquetas: "Surte a tienditas,Crédito,Reparto diario,Factura",
		pedidoMinimoC: 150000, desde: 2004, clientes: 186, resenas: 412, verificada: true, calificacion: 4.8,
	},
	"bebidas@anaquel.mx": {
		portada: "cola-600", horario: "Lun a sáb 7:00 – 17:00", entrega: "Ruta martes, jueves y sábado",
		credito: "Te prestamos refrigerador con compra mínima semanal", pagos: "Efectivo y transferencia",
		zona: "Durango capital", etiquetas: "Refrigerador en comodato,Promociones semanales",
		pedidoMinimoC: 80000, desde: 2011, clientes: 94, resenas: 156, verificada: true, calificacion: 4.6,
	},
	"lacteos@anaquel.mx": {
		portada: "leche", horario: "Lun a sáb 5:30 – 14:00", entrega: "Entrega diaria temprano (cadena de frío)",
		pagos: "Efectivo y transferencia", zona: "Durango capital y Lerdo", etiquetas: "Cadena de frío,Entrega diaria",
		pedidoMinimoC: 50000, desde: 2016, clientes: 61, resenas: 73, verificada: true, calificacion: 4.5,
	},
	"abarrotes.mayoreo@anaquel.mx": {
		portada: "aceite", horario: "Lun a vie 8:00 – 18:00", pagos: "Efectivo",
		etiquetas: "Precios de mayoreo", pedidoMinimoC: 100000, desde: 2019, resenas: 21, calificacion: 4.2,
	},
	"papeleria.mayoreo@anaquel.mx": {
		horario: "Lun a sáb 9:00 – 19:00", entrega: "Envío en 48 h", pagos: "Efectivo, transferencia y tarjeta",
		etiquetas: "Temporada escolar", desde: 2013, clientes: 40, resenas: 38, verificada: true, calificacion: 4.4,
	},
	"mayoreo@anaquel.mx":  {horario: "Lun a sáb 10:00 – 19:00", pagos: "Efectivo y transferencia", etiquetas: "Bordado,Uniformes", desde: 2018, resenas: 12, calificacion: 4.3},
	"textiles@anaquel.mx": {horario: "Lun a vie 9:00 – 18:00", pagos: "Efectivo", desde: 2009, resenas: 8, calificacion: 4.0},
	"ropa@anaquel.mx":     {horario: "Lun a sáb 10:00 – 20:00", resenas: 5, calificacion: 4.7},
	DemoEmail:             {horario: "Todos los días 7:00 – 22:00"},
}

// prepararDemoVisual copia las fotos, llena los perfiles y pone foto a los productos.
// Es idempotente: corre en cada arranque y al reiniciar la demo.
func (a *App) prepararDemoVisual() {
	dir := filepath.Join(a.media, "demo")
	os.MkdirAll(dir, 0o755)
	if ents, err := fotosDemo.ReadDir("demo_fotos"); err == nil {
		for _, e := range ents {
			destino := filepath.Join(dir, e.Name())
			if _, err := os.Stat(destino); err == nil {
				continue
			}
			if b, err := fotosDemo.ReadFile("demo_fotos/" + e.Name()); err == nil {
				os.WriteFile(destino, b, 0o644)
			}
		}
	}
	for email, p := range perfilesDemo {
		portada := ""
		if p.portada != "" {
			portada = "/media/demo/" + p.portada + ".jpg"
		}
		a.db.Exec(`UPDATE tiendas SET portada = ?, horario = ?, entrega = ?, credito = ?, pagos = ?, zona = ?, etiquetas = ?,
			pedido_minimo_c = ?, desde = ?, clientes = ?, resenas = ?, verificada = ?, calificacion = ? WHERE email = ?`,
			portada, p.horario, p.entrega, p.credito, p.pagos, p.zona, p.etiquetas, p.pedidoMinimoC, p.desde, p.clientes,
			p.resenas, p.verificada, p.calificacion, email)
	}
	// Fotos de producto solo a las tiendas demo (y sin pisar fotos puestas a mano).
	type prod struct {
		id     int64
		nombre string
	}
	var ps []prod
	rows, err := a.db.Query(`SELECT p.id, p.nombre FROM productos p JOIN tiendas t ON t.id = p.tienda_id
		WHERE p.foto = '' AND t.email LIKE '%@anaquel.mx'`)
	if err != nil {
		return
	}
	for rows.Next() {
		var p prod
		rows.Scan(&p.id, &p.nombre)
		ps = append(ps, p)
	}
	rows.Close()
	for _, p := range ps {
		if f := fotoDe(p.nombre); f != "" {
			a.db.Exec("UPDATE productos SET foto = ? WHERE id = ?", f, p.id)
		}
	}
}

// ---------------------------------------------------------------- perfil público de una tienda

type PerfilTienda struct {
	TiendaCorta
	Descripcion   string         `json:"descripcion"`
	Direccion     string         `json:"direccion"`
	Portada       string         `json:"portada,omitempty"`
	Horario       string         `json:"horario,omitempty"`
	Entrega       string         `json:"entrega,omitempty"`
	Credito       string         `json:"credito,omitempty"`
	Pagos         string         `json:"pagos,omitempty"`
	Zona          string         `json:"zona,omitempty"`
	PedidoMinimoC int64          `json:"pedido_minimo_c,omitempty"`
	Desde         int            `json:"desde,omitempty"`
	Clientes      int            `json:"clientes,omitempty"`
	Verificada    bool           `json:"verificada"`
	Calificacion  float64        `json:"calificacion,omitempty"`
	Resenas       int            `json:"resenas,omitempty"`
	Etiquetas     []string       `json:"etiquetas"`
	Productos     int            `json:"productos"`
	Tratos        int            `json:"tratos"`   // tratos cerrados en la red
	Completo      int            `json:"completo"` // % del perfil lleno
	Catalogo      []ItemCatalogo `json:"catalogo"`
	Anuncios      []Resultado    `json:"anuncios"`
	DistanciaKm   *float64       `json:"distancia_km,omitempty"`
}

func (a *App) perfilTienda(w http.ResponseWriter, r *http.Request) {
	yo := tiendaDe(r)
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		manejar(w, nil, fallo(http.StatusBadRequest, "tienda inválida"))
		return
	}
	p := PerfilTienda{Etiquetas: []string{}, Anuncios: []Resultado{}}
	var etiquetas string
	var lat, lng *float64
	err = a.db.QueryRow(`SELECT id, nombre, slug, giro, telefono, descripcion, direccion, portada, horario, entrega, credito, pagos, zona,
		pedido_minimo_c, desde, clientes, verificada, calificacion, resenas, etiquetas, lat, lng FROM tiendas WHERE id = ?`, id).Scan(
		&p.ID, &p.Nombre, &p.Slug, &p.Giro, &p.Telefono, &p.Descripcion, &p.Direccion, &p.Portada, &p.Horario, &p.Entrega, &p.Credito,
		&p.Pagos, &p.Zona, &p.PedidoMinimoC, &p.Desde, &p.Clientes, &p.Verificada, &p.Calificacion, &p.Resenas, &etiquetas, &lat, &lng)
	if err != nil {
		manejar(w, nil, fallo(http.StatusNotFound, "no existe esa tienda"))
		return
	}
	for _, e := range strings.Split(etiquetas, ",") {
		if e = strings.TrimSpace(e); e != "" {
			p.Etiquetas = append(p.Etiquetas, e)
		}
	}
	a.db.QueryRow("SELECT COUNT(*) FROM productos WHERE tienda_id = ? AND publicado = 1", id).Scan(&p.Productos)
	a.db.QueryRow("SELECT COUNT(*) FROM tratos WHERE (comprador_id = ? OR vendedor_id = ?) AND estado = 'entregado'", id, id).Scan(&p.Tratos)
	campos := []bool{p.Descripcion != "", p.Direccion != "", p.Portada != "", p.Horario != "", p.Entrega != "", p.Credito != "",
		p.Pagos != "", p.Zona != "", p.Telefono != "", p.Productos > 0}
	for _, c := range campos {
		if c {
			p.Completo += 10
		}
	}
	if mlat, mlng := a.coordsDe(yo); mlat != nil && lat != nil {
		d := distanciaKm(*mlat, *mlng, *lat, *lng)
		p.DistanciaKm = &d
	}
	// Sin vitrina no es error aquí: el perfil se muestra igual, solo sin catálogo.
	cat, _ := a.miCatalogo(id, nil, true)
	p.Catalogo = cat
	if p.Catalogo == nil {
		p.Catalogo = []ItemCatalogo{}
	}
	rows, err := a.db.Query("SELECT id, tipo, titulo, texto, precio, creado FROM anuncios WHERE tienda_id = ? AND activo = 1 ORDER BY creado DESC LIMIT 6", id)
	if err == nil {
		for rows.Next() {
			x := Resultado{Tipo: "anuncio"}
			rows.Scan(&x.ID, &x.Subtipo, &x.Titulo, &x.Detalle, &x.Precio, &x.Creado)
			p.Anuncios = append(p.Anuncios, x)
		}
		rows.Close()
	}
	responder(w, 200, p)
}

// ---------------------------------------------------------------- notificaciones para el teléfono

// Notificacion: lo nuevo desde la última vez que el teléfono preguntó.
type Notificacion struct {
	Clave  string `json:"clave"` // a<id> aviso, c<id> mensaje de chat
	Tipo   string `json:"tipo"`  // trato | encontrado | interesado | chat | sistema
	Titulo string `json:"titulo"`
	Texto  string `json:"texto"`
	ChatID int64  `json:"chat_id,omitempty"`
	Creado int64  `json:"creado"`
}

type Notificaciones struct {
	Items    []Notificacion `json:"items"`
	UltAviso int64          `json:"ult_aviso"`
	UltChat  int64          `json:"ult_chat"`
}

// GET /api/notificaciones?aviso=N&chat=M → avisos y mensajes posteriores a esos ids.
// Sin parámetros solo devuelve los ids actuales (para no notificar lo viejo).
func (a *App) notificaciones(w http.ResponseWriter, r *http.Request) {
	yo := tiendaDe(r)
	desdeAviso, errA := strconv.ParseInt(r.URL.Query().Get("aviso"), 10, 64)
	desdeChat, errC := strconv.ParseInt(r.URL.Query().Get("chat"), 10, 64)
	out := Notificaciones{Items: []Notificacion{}}
	a.db.QueryRow("SELECT COALESCE(MAX(id), 0) FROM avisos WHERE tienda_id = ?", yo).Scan(&out.UltAviso)
	a.db.QueryRow(`SELECT COALESCE(MAX(m.id), 0) FROM chat_mensajes m JOIN chats c ON c.id = m.chat_id
		WHERE (c.tienda_a = ? OR c.tienda_b = ?)`, yo, yo).Scan(&out.UltChat)
	if errA != nil || errC != nil {
		responder(w, 200, out)
		return
	}
	titulos := map[string]string{"trato": "Tu trato", "encontrado": "Encontramos lo que buscas", "interesado": "Alguien quiere lo tuyo", "sistema": "Anaquel"}
	rows, err := a.db.Query("SELECT id, tipo, texto, creado FROM avisos WHERE tienda_id = ? AND id > ? ORDER BY id LIMIT 20", yo, desdeAviso)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	for rows.Next() {
		var n Notificacion
		var id int64
		rows.Scan(&id, &n.Tipo, &n.Texto, &n.Creado)
		n.Clave = "a" + strconv.FormatInt(id, 10)
		n.Titulo = titulos[n.Tipo]
		if n.Titulo == "" {
			n.Titulo = "Anaquel"
		}
		out.Items = append(out.Items, n)
	}
	rows.Close()
	rows, err = a.db.Query(`SELECT m.id, m.chat_id, m.tipo, m.texto, m.creado, t.nombre FROM chat_mensajes m
		JOIN chats c ON c.id = m.chat_id JOIN tiendas t ON t.id = m.tienda_id
		WHERE (c.tienda_a = ? OR c.tienda_b = ?) AND m.tienda_id IS NOT NULL AND m.tienda_id != ? AND m.id > ? ORDER BY m.id LIMIT 20`,
		yo, yo, yo, desdeChat)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	for rows.Next() {
		var n Notificacion
		var id int64
		var tipo, quien string
		rows.Scan(&id, &n.ChatID, &tipo, &n.Texto, &n.Creado, &quien)
		n.Clave, n.Tipo, n.Titulo = "c"+strconv.FormatInt(id, 10), "chat", strings.TrimSuffix(quien, " (demo)")
		switch tipo {
		case "catalogo":
			n.Texto = "Te mandó su catálogo"
		case "pedido":
			n.Texto = "Te mandó un pedido: " + n.Texto
		}
		out.Items = append(out.Items, n)
	}
	rows.Close()
	responder(w, 200, out)
}
