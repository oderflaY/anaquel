package main

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"math/rand"
	"os"
	"path/filepath"

	"golang.org/x/crypto/bcrypt"
)

// Cuenta de demostración (para la clase y para probar sin registrarse).
const (
	DemoEmail    = "demo@anaquel.mx"
	DemoPassword = "demo1234"
)

type demoProducto struct {
	nombre, categoria string
	precio            int64
	stock, min        int64
	color             color.RGBA
	productor         int // índice en demoProductores, -1 = ninguno
	proveedor         int // índice en demoProveedores, -1 = ninguno
}

var demoProductores = []Productor{
	{Nombre: "Granja Los Álamos", Telefono: "6181000001", Productos: "huevo, huevo de rancho", Lugar: "Col. Valle del Sur", Lat: 24.0012, Lng: -104.6421, Descripcion: "Gallinas de libre pastoreo, entregamos por reja."},
	{Nombre: "Quesería La Ferrería", Telefono: "6181000002", Productos: "queso fresco, queso menonita, crema", Lugar: "La Ferrería", Lat: 23.9796, Lng: -104.6763, Descripcion: "Queso y crema de leche de vaca del día."},
	{Nombre: "Tortillería El Nixtamal", Telefono: "6181000003", Productos: "tortilla de maíz, tostadas, totopos", Lugar: "Zona Centro", Lat: 24.0251, Lng: -104.6680, Descripcion: "Maíz nixtamalizado, sin harina."},
	{Nombre: "Miel del Mezquital", Telefono: "6181000004", Productos: "miel, miel de abeja", Lugar: "El Mezquital", Lat: 23.4740, Lng: -104.3975, Descripcion: "Miel cruda de mezquite."},
	{Nombre: "Nopalera Doña Chuy", Telefono: "6181000005", Productos: "nopal, nopales, tuna", Lugar: "Col. 20 de Noviembre", Lat: 24.0405, Lng: -104.6338, Descripcion: "Nopal limpio y picado."},
}

var demoProveedores = []Proveedor{
	{Nombre: "Ruta de refrescos", Telefono: "6181000010", Surte: "refresco, agua"},
	{Nombre: "Ruta de botanas", Telefono: "6181000011", Surte: "papas, botana, galletas"},
	{Nombre: "Abarrotes Mayoreo Guadiana", Telefono: "6181000012", Surte: "arroz, frijol, aceite, azúcar"},
}

// Cada fila del anaquel tiene 4 huecos. El orden coincide con las zonas.
var demoProductos = []demoProducto{
	{"Refresco de cola 600 ml", "Bebidas", 2200, 12, 6, color.RGBA{190, 20, 30, 255}, -1, 0},
	{"Agua natural 1 L", "Bebidas", 1500, 10, 6, color.RGBA{60, 140, 220, 255}, -1, 0},
	{"Refresco de naranja 600 ml", "Bebidas", 2000, 8, 6, color.RGBA{240, 130, 20, 255}, -1, 0},
	{"Jugo de manzana 1 L", "Bebidas", 3200, 6, 4, color.RGBA{120, 190, 60, 255}, -1, -1},
	{"Papas fritas 45 g", "Botanas", 1900, 15, 8, color.RGBA{250, 210, 40, 255}, -1, 1},
	{"Galletas de chocolate", "Botanas", 2300, 9, 5, color.RGBA{110, 60, 30, 255}, -1, 1},
	{"Arroz 1 kg", "Abarrotes", 3400, 7, 4, color.RGBA{235, 235, 225, 255}, -1, 2},
	{"Frijol pinto 1 kg", "Abarrotes", 4200, 6, 4, color.RGBA{150, 90, 70, 255}, -1, 2},
	{"Huevo de rancho (docena)", "Productos locales", 5500, 4, 3, color.RGBA{245, 225, 190, 255}, 0, -1},
	{"Queso fresco 500 g", "Productos locales", 7500, 3, 2, color.RGBA{250, 250, 240, 255}, 1, -1},
	{"Miel de mezquite 500 g", "Productos locales", 9000, 4, 2, color.RGBA{200, 140, 20, 255}, 3, -1},
	{"Tostadas de maíz", "Productos locales", 3000, 6, 3, color.RGBA{230, 180, 90, 255}, 2, -1},
}

// sembrarDemo crea la tienda demo una sola vez (si ya existe no hace nada).
func (a *App) sembrarDemo() error {
	var n int
	a.db.QueryRow("SELECT COUNT(*) FROM tiendas WHERE email = ?", DemoEmail).Scan(&n)
	if n > 0 {
		return nil
	}
	tx, err := a.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	t := ahora()
	hash, _ := bcrypt.GenerateFromPassword([]byte(DemoPassword), bcrypt.DefaultCost)
	lat, lng := 24.0249, -104.6545
	res, err := tx.Exec(`INSERT INTO tiendas (nombre, slug, email, pass_hash, telefono, direccion, lat, lng, creado)
		VALUES ('Abarrotes Doña Lupita (demo)', 'dona-lupita', ?, ?, '6181234567', 'Calle Negrete 210, Zona Centro, Durango', ?, ?, ?)`,
		DemoEmail, string(hash), lat, lng, t)
	if err != nil {
		return err
	}
	tienda, _ := res.LastInsertId()

	var productores []int64
	for _, p := range demoProductores {
		var id int64
		// Los productores de demo se comparten si ya existían.
		if tx.QueryRow("SELECT id FROM productores WHERE nombre = ?", p.Nombre).Scan(&id) != nil {
			r, err := tx.Exec(`INSERT INTO productores (nombre, telefono, productos, descripcion, lugar, lat, lng, token_hash, creado)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`, p.Nombre, p.Telefono, p.Productos, p.Descripcion, p.Lugar, p.Lat, p.Lng, hashToken(tokenAleatorio()), t)
			if err != nil {
				return err
			}
			id, _ = r.LastInsertId()
		}
		productores = append(productores, id)
	}
	var proveedores []int64
	for _, p := range demoProveedores {
		r, err := tx.Exec("INSERT INTO proveedores (tienda_id, nombre, telefono, surte, creado) VALUES (?, ?, ?, ?, ?)",
			tienda, p.Nombre, p.Telefono, p.Surte, t)
		if err != nil {
			return err
		}
		id, _ := r.LastInsertId()
		proveedores = append(proveedores, id)
	}
	var productos []int64
	for i, p := range demoProductos {
		var prov, prod any
		if p.proveedor >= 0 {
			prov = proveedores[p.proveedor]
		}
		if p.productor >= 0 {
			prod = productores[p.productor]
		}
		r, err := tx.Exec(`INSERT INTO productos (tienda_id, codigo, nombre, categoria, precio_c, stock, stock_min, proveedor_id, productor_id, actualizado)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, tienda, "75010000"+string(rune('A'+i)), p.nombre, p.categoria, p.precio, p.stock, p.min, prov, prod, t)
		if err != nil {
			return err
		}
		id, _ := r.LastInsertId()
		productos = append(productos, id)
	}

	// Anaquel con foto de referencia (lleno) generada.
	ref := jpegDe(dibujarAnaquel(nil, 0))
	refArchivo := tokenAleatorio() + ".jpg"
	if err := os.WriteFile(filepath.Join(a.media, refArchivo), ref, 0o644); err != nil {
		return err
	}
	res, err = tx.Exec("INSERT INTO anaqueles (tienda_id, nombre, foto_ref, creado) VALUES (?, 'Anaquel principal', ?, ?)", tienda, refArchivo, t)
	if err != nil {
		return err
	}
	anaquel, _ := res.LastInsertId()
	for i, z := range zonasDemo() {
		if _, err := tx.Exec("INSERT INTO zonas (anaquel_id, producto_id, x, y, w, h) VALUES (?, ?, ?, ?, ?, ?)",
			anaquel, productos[i], z.X, z.Y, z.W, z.H); err != nil {
			return err
		}
	}
	// Un pedido de huevo de hace 4 días que no ha llegado (para ver la alerta).
	hace4 := t - 4*24*3600*1000
	res, err = tx.Exec(`INSERT INTO pedidos (tienda_id, productor_id, destino, telefono, estado, creado, actualizado)
		VALUES (?, ?, ?, ?, 'confirmado', ?, ?)`, tienda, productores[0], demoProductores[0].Nombre, demoProductores[0].Telefono, hace4, hace4)
	if err != nil {
		return err
	}
	ped, _ := res.LastInsertId()
	tx.Exec("INSERT INTO pedido_items (pedido_id, producto_id, nombre, cantidad) VALUES (?, ?, ?, 4)", ped, productos[8], demoProductos[8].nombre)
	if err := tx.Commit(); err != nil {
		return err
	}
	// Foto "de hoy": se acabó el agua y el huevo, quedan pocas papas.
	return a.fotoDemo(anaquel, ref)
}

// demoHoy: piezas visibles hoy en cada hueco del anaquel de demo.
var demoHoy = []int{3, 0, 3, 3, 1, 3, 3, 3, 0, 3, 3, 2}

func (a *App) fotoDemo(anaquel int64, ref []byte) error {
	hoy := jpegDe(dibujarAnaquel(demoHoy, 6))
	archivo := tokenAleatorio() + ".jpg"
	if err := os.WriteFile(filepath.Join(a.media, archivo), hoy, 0o644); err != nil {
		return err
	}
	an, err := a.cargarAnaquel(anaquel)
	if err != nil {
		return err
	}
	var zv []ZonaVision
	for _, z := range an.Zonas {
		zv = append(zv, ZonaVision{ID: z.ID, Nombre: z.Producto, X: z.X, Y: z.Y, W: z.W, H: z.H})
	}
	lecturas, err := Comparador{}.Analizar(ref, hoy, zv)
	if err != nil {
		return err
	}
	t := ahora()
	faltan := 0
	for _, l := range lecturas {
		a.db.Exec("UPDATE zonas SET nivel = ?, score = ?, actualizado = ? WHERE id = ?", l.Nivel, l.Score, t, l.ZonaID)
		if l.Nivel != "lleno" {
			faltan++
		}
	}
	a.db.Exec("UPDATE anaqueles SET ultima_foto = ?, ultima_en = ? WHERE id = ?", archivo, t, anaquel)
	_, err = a.db.Exec("INSERT INTO fotos (anaquel_id, archivo, metodo, faltantes, creado) VALUES (?, ?, 'comparacion', ?, ?)", anaquel, archivo, faltan, t)
	return err
}

// Geometría del anaquel de demo: 3 repisas × 4 huecos.
const demoW, demoH = 960, 720

func zonasDemo() []ZonaVision {
	var out []ZonaVision
	for fila := 0; fila < 3; fila++ {
		for col := 0; col < 4; col++ {
			out = append(out, ZonaVision{
				X: (40 + float64(col)*220) / demoW, Y: (30 + float64(fila)*230) / demoH,
				W: 200.0 / demoW, H: 190.0 / demoH,
			})
		}
	}
	return out
}

// dibujarAnaquel genera la foto de un anaquel. unidades[i] = piezas visibles
// en el hueco i (nil = todo lleno, 3 piezas). desplazar mueve toda la foto
// como si se hubiera tomado un poco más a la izquierda.
func dibujarAnaquel(unidades []int, desplazar int) image.Image {
	rnd := rand.New(rand.NewSource(7))
	img := image.NewRGBA(image.Rect(0, 0, demoW, demoH))
	// Fondo del mueble (madera clara con vetas).
	for y := 0; y < demoH; y++ {
		for x := 0; x < demoW; x++ {
			v := uint8(170 + (x/9+y/31)%6 + rnd.Intn(5))
			img.Set(x, y, color.RGBA{v, uint8(int(v) - 35), uint8(int(v) - 80), 255})
		}
	}
	rect := func(x0, y0, x1, y1 int, c color.RGBA) {
		for y := max(0, y0); y < min(demoH, y1); y++ {
			for x := max(0, x0-desplazar); x < min(demoW, x1-desplazar); x++ {
				img.Set(x, y, c)
			}
		}
	}
	for fila := 0; fila < 3; fila++ {
		baseY := 30 + fila*230 + 190
		rect(0+desplazar, baseY, demoW+desplazar, baseY+18, color.RGBA{95, 60, 35, 255}) // repisa
		for col := 0; col < 4; col++ {
			i := fila*4 + col
			n := 3
			if unidades != nil {
				n = unidades[i]
			}
			c := demoProductos[i].color
			for u := 0; u < n; u++ {
				x0 := 50 + col*220 + u*62
				y0 := baseY - 150 - (i%3)*8
				rect(x0, y0, x0+56, baseY, c)
				oscuro := color.RGBA{c.R / 2, c.G / 2, c.B / 2, 255}
				claro := color.RGBA{255, 255, 255, 255}
				// Etiqueta con "letras" para que tenga textura como un empaque real.
				rect(x0+6, y0+40, x0+50, y0+70, claro)
				for k := 0; k < 5; k++ {
					rect(x0+9+k*8, y0+46, x0+14+k*8, y0+64, oscuro)
				}
				rect(x0, y0+95, x0+56, y0+101, oscuro)
				rect(x0+20, y0+8, x0+36, y0+20, oscuro)
			}
		}
	}
	return img
}

func jpegDe(img image.Image) []byte {
	var b bytes.Buffer
	jpeg.Encode(&b, img, &jpeg.Options{Quality: 85})
	return b.Bytes()
}
