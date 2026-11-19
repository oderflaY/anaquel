// Anaquel: una foto al día y la tienda sabe qué le falta, a quién pedírselo
// y quién de la zona lo produce.
package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type App struct {
	db     *sql.DB
	media  string // carpeta de fotos
	web    string // build de la web (SPA)
	vision Vision
	claude *clienteClaude // nil = asesor básico (sin llave de Anthropic)
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	datos := env("DATOS", "./datos")
	os.MkdirAll(filepath.Join(datos, "media"), 0o755)
	db, err := abrirDB(filepath.Join(datos, "anaquel.db"))
	if err != nil {
		log.Fatal(err)
	}
	app := &App{
		db:     db,
		media:  filepath.Join(datos, "media"),
		web:    env("WEB", "../web/dist"),
		vision: nuevaVision(),
		claude: nuevoClaude(),
	}
	if env("DATOS_DEMO", "1") == "1" {
		if err := app.sembrarDemo(); err != nil {
			log.Println("demo:", err)
		}
		if err := app.sembrarRed(); err != nil {
			log.Println("demo red:", err)
		}
	}
	addr := env("ADDR", ":8080")
	log.Printf("Anaquel escuchando en %s (visión: %s, asesor con IA: %v)", addr, app.vision.Nombre(), app.claude != nil)
	srv := &http.Server{
		Addr:              addr,
		Handler:           app.rutas(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Fatal(srv.ListenAndServe())
}

func (a *App) rutas() http.Handler {
	m := http.NewServeMux()
	// Cuenta
	m.HandleFunc("POST /api/registro", a.registro)
	m.HandleFunc("POST /api/login", a.login)
	m.HandleFunc("GET /api/yo", a.conTienda(a.yo))
	m.HandleFunc("PUT /api/yo", a.conTienda(a.actualizarYo))
	m.HandleFunc("GET /api/resumen", a.conTienda(a.resumen))
	m.HandleFunc("POST /api/demo/reiniciar", a.conTienda(a.reiniciarDemo))
	// Productos
	m.HandleFunc("GET /api/productos", a.conTienda(a.listarProductos))
	m.HandleFunc("POST /api/productos", a.conTienda(a.crearProducto))
	m.HandleFunc("PUT /api/productos/{id}", a.conTienda(a.editarProducto))
	m.HandleFunc("DELETE /api/productos/{id}", a.conTienda(a.borrarProducto))
	m.HandleFunc("POST /api/productos/sync", a.conTienda(a.syncProductos))
	// Anaqueles
	m.HandleFunc("GET /api/anaqueles", a.conTienda(a.listarAnaqueles))
	m.HandleFunc("POST /api/anaqueles", a.conTienda(a.crearAnaquel))
	m.HandleFunc("GET /api/anaqueles/{id}", a.conTienda(a.verAnaquel))
	m.HandleFunc("PUT /api/anaqueles/{id}", a.conTienda(a.renombrarAnaquel))
	m.HandleFunc("DELETE /api/anaqueles/{id}", a.conTienda(a.borrarAnaquel))
	m.HandleFunc("PUT /api/anaqueles/{id}/zonas", a.conTienda(a.guardarZonas))
	m.HandleFunc("POST /api/anaqueles/{id}/referencia", a.conTienda(a.nuevaReferencia))
	m.HandleFunc("POST /api/anaqueles/{id}/fotos", a.conTienda(a.fotoDelDia))
	// Faltantes, proveedores y pedidos
	m.HandleFunc("GET /api/faltantes", a.conTienda(a.faltantes))
	m.HandleFunc("GET /api/proveedores", a.conTienda(a.listarProveedores))
	m.HandleFunc("POST /api/proveedores", a.conTienda(a.crearProveedor))
	m.HandleFunc("PUT /api/proveedores/{id}", a.conTienda(a.editarProveedor))
	m.HandleFunc("DELETE /api/proveedores/{id}", a.conTienda(a.borrarProveedor))
	m.HandleFunc("GET /api/pedidos", a.conTienda(a.listarPedidos))
	m.HandleFunc("POST /api/pedidos", a.conTienda(a.crearPedido))
	m.HandleFunc("POST /api/pedidos/{id}/estado", a.conTienda(a.cambiarEstadoPedido))
	// Público
	// Inventario (kárdex al estilo MatObra)
	m.HandleFunc("GET /api/movimientos", a.conTienda(a.listarMovimientos))
	m.HandleFunc("POST /api/movimientos", a.conTienda(a.crearMovimiento))
	m.HandleFunc("POST /api/movimientos/lote", a.conTienda(a.crearLote))
	m.HandleFunc("GET /api/productos/codigo/{codigo}", a.conTienda(a.productoDeCodigo))

	// Red de comercio local
	// Bodega 3D: plano del local y lugar de cada producto.
	m.HandleFunc("GET /api/bodega", a.conTienda(a.bodega))
	m.HandleFunc("PUT /api/bodega/plano", a.conTienda(a.guardarPlanoHTTP))
	m.HandleFunc("PUT /api/bodega/lugares", a.conTienda(a.guardarLugaresHTTP))
	m.HandleFunc("POST /api/bodega/acomodar", a.conTienda(a.acomodarHTTP))
	m.HandleFunc("GET /api/red", a.conTienda(a.red))
	m.HandleFunc("GET /api/anuncios", a.conTienda(a.listarAnuncios))
	m.HandleFunc("POST /api/anuncios", a.conTienda(a.crearAnuncio))
	m.HandleFunc("PUT /api/anuncios/{id}", a.conTienda(a.editarAnuncio))
	m.HandleFunc("DELETE /api/anuncios/{id}", a.conTienda(a.borrarAnuncio))
	m.HandleFunc("GET /api/solicitudes", a.conTienda(a.listarSolicitudes))
	m.HandleFunc("POST /api/solicitudes", a.conTienda(a.crearSolicitud))
	m.HandleFunc("GET /api/solicitudes/{id}", a.conTienda(a.verSolicitud))
	m.HandleFunc("POST /api/solicitudes/{id}/cerrar", a.conTienda(a.cerrarSolicitud))
	m.HandleFunc("GET /api/tratos", a.conTienda(a.listarTratos))
	m.HandleFunc("POST /api/tratos", a.conTienda(a.crearTrato))
	m.HandleFunc("GET /api/tratos/{id}", a.conTienda(a.verTrato))
	m.HandleFunc("POST /api/tratos/{id}/mensajes", a.conTienda(a.mensajeTrato))
	m.HandleFunc("POST /api/tratos/{id}/{accion}", a.conTienda(a.accionTrato))
	m.HandleFunc("GET /api/inicio", a.conTienda(a.inicio))
	m.HandleFunc("GET /api/chats", a.conTienda(a.listarChats))
	m.HandleFunc("POST /api/chats", a.conTienda(a.abrirChat))
	m.HandleFunc("GET /api/chats/{id}", a.conTienda(a.verChat))
	m.HandleFunc("POST /api/chats/{id}/mensajes", a.conTienda(a.escribirChat))
	m.HandleFunc("POST /api/chats/{id}/pedido", a.conTienda(a.pedirDeChat))
	m.HandleFunc("POST /api/chats/{id}/pedido/{msg}/{accion}", a.conTienda(a.accionPedido))
	m.HandleFunc("GET /api/tiendas/{id}/catalogo", a.conTienda(a.catalogoDe))
	m.HandleFunc("GET /api/avisos", a.conTienda(a.listarAvisos))
	m.HandleFunc("POST /api/avisos/leidos", a.conTienda(a.avisosLeidos))

	// Asesor del negocio
	m.HandleFunc("GET /api/asesor", a.conTienda(a.verAsesor))
	m.HandleFunc("POST /api/asesor", a.conTienda(a.preguntarAsesor))
	m.HandleFunc("DELETE /api/asesor", a.conTienda(a.borrarAsesor))

	m.HandleFunc("GET /api/publico/giros", a.listarGiros)
	m.HandleFunc("GET /api/publico/red", a.redPublica)
	m.HandleFunc("GET /api/publico/productores", a.productoresCercanos)
	m.HandleFunc("POST /api/publico/productores", a.registrarProductor)
	m.HandleFunc("GET /api/publico/tiendas", a.tiendasCercanas)
	m.HandleFunc("GET /api/publico/tiendas/{slug}", a.tiendaPublica)
	m.HandleFunc("GET /api/publico/tiendas/{slug}/bodega", a.bodegaPublica)
	m.HandleFunc("GET /api/salud", func(w http.ResponseWriter, r *http.Request) {
		responder(w, 200, map[string]any{"ok": true, "vision": a.vision.Nombre()})
	})
	// Fotos: nombres aleatorios imposibles de adivinar (así <img> no necesita token).
	m.Handle("GET /media/", http.StripPrefix("/media/", cacheLargo(http.FileServer(http.Dir(a.media)))))
	m.HandleFunc("/", a.spa)
	return cors(conPrefijo(m))
}

// Prefijo bajo el que vive la app en el servidor compartido (https://IP/anaquel/).
const prefijo = "/anaquel"

// conPrefijo acepta las rutas con o sin /anaquel, y manda la raíz a /anaquel/.
func conPrefijo(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		switch {
		case p == "/" || p == prefijo:
			http.Redirect(w, r, prefijo+"/", http.StatusFound)
			return
		case strings.HasPrefix(p, prefijo+"/"):
			r.URL.Path = strings.TrimPrefix(p, prefijo)
			r.URL.RawPath = ""
		}
		h.ServeHTTP(w, r)
	})
}

// spa sirve la web y, para rutas del cliente (/panel, /t/slug…), index.html.
func (a *App) spa(w http.ResponseWriter, r *http.Request) {
	if strings.HasPrefix(r.URL.Path, "/api/") {
		responderError(w, 404, "Ruta no encontrada")
		return
	}
	p := filepath.Join(a.web, filepath.Clean("/"+r.URL.Path))
	if st, err := os.Stat(p); err == nil && !st.IsDir() {
		if strings.HasPrefix(r.URL.Path, "/assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		}
		http.ServeFile(w, r, p)
		return
	}
	w.Header().Set("Cache-Control", "no-cache")
	http.ServeFile(w, r, filepath.Join(a.web, "index.html"))
}

func cacheLargo(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		h.ServeHTTP(w, r)
	})
}

// cors permite usar la API desde la web en desarrollo (Vite en otro puerto).
func cors(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(204)
			return
		}
		h.ServeHTTP(w, r)
	})
}

// ---- utilidades HTTP ----

type errHTTP struct {
	codigo int
	msg    string
}

func (e errHTTP) Error() string { return e.msg }

func fallo(codigo int, msg string) error { return errHTTP{codigo, msg} }

func responder(w http.ResponseWriter, codigo int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(codigo)
	json.NewEncoder(w).Encode(v)
}

func responderError(w http.ResponseWriter, codigo int, msg string) {
	responder(w, codigo, map[string]string{"error": msg})
}

// manejar traduce el error de un handler a una respuesta JSON.
func manejar(w http.ResponseWriter, v any, err error) {
	if err != nil {
		var e errHTTP
		if errors.As(err, &e) {
			responderError(w, e.codigo, e.msg)
			return
		}
		if errors.Is(err, sql.ErrNoRows) {
			responderError(w, 404, "No existe")
			return
		}
		log.Println("error:", err)
		responderError(w, 500, "Error interno, intenta de nuevo")
		return
	}
	responder(w, 200, v)
}

func leerJSON(r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(nil, r.Body, 2<<20)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		return fallo(400, "JSON inválido")
	}
	return nil
}

func idRuta(r *http.Request) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		return 0, fallo(400, "id inválido")
	}
	return id, nil
}

func ahora() int64 { return time.Now().UnixMilli() }
