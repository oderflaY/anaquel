// Pasarela: la única puerta pública. Enruta al núcleo y a los servicios.
package main

import (
	"log/slog"
	"strings"

	"anaquel/servicios/internal/pasarela"
	"anaquel/servicios/internal/plataforma"
)

func main() {
	clave, err := plataforma.Llave("CLAVE_INTERNA", 32)
	if err != nil {
		plataforma.Morir("configuración", err)
	}
	f, _ := plataforma.NuevoFirmante(clave)
	var origenes []string
	if o := plataforma.Env("ORIGENES_PERMITIDOS", ""); o != "" {
		origenes = strings.Split(o, ",")
	}
	p, err := pasarela.Nueva(pasarela.Config{
		Nucleo:       plataforma.Env("URL_NUCLEO", "http://127.0.0.1:8097"),
		Acceso:       plataforma.Env("URL_ACCESO", "http://127.0.0.1:8101"),
		Mercado:      plataforma.Env("URL_MERCADO", "http://127.0.0.1:8102"),
		Reels:        plataforma.Env("URL_REELS", "http://127.0.0.1:8103"),
		Pagos:        plataforma.Env("URL_PAGOS", "http://127.0.0.1:8104"),
		CookieSegura: plataforma.Env("COOKIE_INSEGURA", "0") != "1",
		ConfiarProxy: plataforma.Env("CONFIAR_PROXY", "0") == "1",
		HSTS:         plataforma.Env("HSTS", "0") == "1",
		Origenes:     origenes,
	}, f)
	if err != nil {
		plataforma.Morir("configuración", err)
	}
	if plataforma.Env("COOKIE_INSEGURA", "0") == "1" {
		slog.Warn("COOKIE_INSEGURA=1: la cookie de sesión NO lleva Secure. Úsalo solo en desarrollo por http://localhost")
	}
	plataforma.Arrancar("pasarela", plataforma.Env("ADDR", ":8080"), p)
}
