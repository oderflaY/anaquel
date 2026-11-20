// Servicio de reels: videos cortos de los vendedores.
package main

import (
	"context"

	"anaquel/servicios/internal/plataforma"
	"anaquel/servicios/internal/reels"
)

func main() {
	b := plataforma.ArrancarBase("reels", reels.Migraciones())
	s, err := reels.Nuevo(b.BD, b.Firmante, plataforma.Env("URL_MERCADO", "http://127.0.0.1:8102"), plataforma.Env("DATOS", "./datos/reels"))
	if err != nil {
		plataforma.Morir("arranque", err)
	}
	if b.Demo {
		acceso := plataforma.NuevoClienteInterno(plataforma.Env("URL_ACCESO", "http://127.0.0.1:8101"), "reels", b.Firmante)
		go s.SembrarDemo(context.Background(), acceso)
	}
	plataforma.Arrancar("reels", plataforma.Env("ADDR", ":8103"), s.Rutas())
}
