// Package plataforma reúne lo que comparten todos los servicios de Anaquel:
// respuestas JSON, errores, identidad firmada, base de datos, cifrado y el
// arranque del servidor. Mismo estilo que backend/ (responder, fallo, manejar).
package plataforma

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
)

// ErrHTTP es un error que el usuario puede leer tal cual.
type ErrHTTP struct {
	Codigo int    `json:"-"`
	Msg    string `json:"error"`
	Clave  string `json:"codigo,omitempty"` // para que la web decida qué pantalla mostrar
}

func (e ErrHTTP) Error() string { return e.Msg }

// Fallo crea un error con mensaje para el usuario.
func Fallo(codigo int, msg string) error { return ErrHTTP{Codigo: codigo, Msg: msg} }

// FalloClave es como Fallo pero con una clave estable para la web.
func FalloClave(codigo int, clave, msg string) error {
	return ErrHTTP{Codigo: codigo, Msg: msg, Clave: clave}
}

// Responder escribe v como JSON.
func Responder(w http.ResponseWriter, codigo int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(codigo)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

// Manejar traduce el resultado de un handler a una respuesta. Los errores
// internos nunca se le muestran al usuario: se registran y se responde 500.
func Manejar(w http.ResponseWriter, r *http.Request, v any, err error) {
	if err == nil {
		if v == nil {
			v = map[string]bool{"ok": true}
		}
		Responder(w, http.StatusOK, v)
		return
	}
	var e ErrHTTP
	if errors.As(err, &e) {
		Responder(w, e.Codigo, e)
		return
	}
	if errors.Is(err, pgx.ErrNoRows) {
		Responder(w, http.StatusNotFound, ErrHTTP{Msg: "No lo encontramos"})
		return
	}
	var mb *http.MaxBytesError
	if errors.As(err, &mb) {
		Responder(w, http.StatusRequestEntityTooLarge, ErrHTTP{Msg: "El archivo o los datos son demasiado grandes"})
		return
	}
	slog.Error("error interno", "ruta", r.URL.Path, "metodo", r.Method, "err", err)
	Responder(w, http.StatusInternalServerError, ErrHTTP{Msg: "Algo falló de nuestro lado. Intenta de nuevo."})
}

// LeerJSON decodifica el cuerpo con límite de tamaño y sin campos desconocidos.
func LeerJSON(w http.ResponseWriter, r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		var mb *http.MaxBytesError
		if errors.As(err, &mb) {
			return err
		}
		return Fallo(http.StatusBadRequest, "Los datos enviados no son válidos")
	}
	if d.More() {
		return Fallo(http.StatusBadRequest, "Los datos enviados no son válidos")
	}
	return nil
}

// IDRuta lee {nombre} de la ruta como entero positivo.
func IDRuta(r *http.Request, nombre string) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue(nombre), 10, 64)
	if err != nil || id <= 0 {
		return 0, Fallo(http.StatusBadRequest, "Identificador inválido")
	}
	return id, nil
}

// Aleatorio regresa n bytes aleatorios en hex. Si el sistema no da
// aleatoriedad, el proceso no puede operar con seguridad: se detiene.
func Aleatorio(n int) string {
	b := make([]byte, n)
	if _, err := io.ReadFull(rand.Reader, b); err != nil {
		panic("sin aleatoriedad del sistema: " + err.Error())
	}
	return hex.EncodeToString(b)
}

type grabadora struct {
	http.ResponseWriter
	codigo int
}

func (g *grabadora) WriteHeader(c int)           { g.codigo = c; g.ResponseWriter.WriteHeader(c) }
func (g *grabadora) Unwrap() http.ResponseWriter { return g.ResponseWriter }

// Bitacora registra cada petición (sin query string: ahí podrían ir datos) y
// convierte un pánico en un 500 sin filtrar detalles.
func Bitacora(servicio string, h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ini := time.Now()
		g := &grabadora{ResponseWriter: w, codigo: 200}
		defer func() {
			if p := recover(); p != nil {
				slog.Error("pánico", "servicio", servicio, "ruta", r.URL.Path, "valor", p)
				Responder(g, http.StatusInternalServerError, ErrHTTP{Msg: "Algo falló de nuestro lado. Intenta de nuevo."})
			}
			slog.Info("http", "servicio", servicio, "metodo", r.Method, "ruta", r.URL.Path,
				"estado", g.codigo, "ms", time.Since(ini).Milliseconds())
		}()
		h.ServeHTTP(g, r)
	})
}

// Arrancar levanta el servidor con tiempos límite y lo apaga con calma
// (termina las peticiones en curso) al recibir SIGINT/SIGTERM.
func Arrancar(servicio, addr string, h http.Handler) {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	srv := &http.Server{
		Addr:              addr,
		Handler:           Bitacora(servicio, h),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       2 * time.Minute, // subidas de video
		WriteTimeout:      2 * time.Minute,
		IdleTimeout:       90 * time.Second,
		MaxHeaderBytes:    32 << 10,
	}
	go func() {
		slog.Info("escuchando", "servicio", servicio, "addr", addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("servidor", "err", err)
			os.Exit(1)
		}
	}()
	ctx, parar := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer parar()
	<-ctx.Done()
	apagar, cancelar := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancelar()
	_ = srv.Shutdown(apagar)
}
