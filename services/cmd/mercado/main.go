// Servicio de mercado: publicaciones, fotos, búsqueda y órdenes.
package main

import (
	"context"

	"anaquel/servicios/internal/mercado"
	"anaquel/servicios/internal/plataforma"
)

func main() {
	b := plataforma.ArrancarBase("mercado", mercado.Migraciones())
	s, err := mercado.Nuevo(b.BD, b.Firmante, plataforma.Env("URL_ACCESO", "http://127.0.0.1:8101"),
		plataforma.Env("DATOS", "./datos/mercado"), b.Demo)
	if err != nil {
		plataforma.Morir("arranque", err)
	}
	if b.Demo {
		go s.SembrarDemo(context.Background())
	}
	plataforma.Arrancar("mercado", plataforma.Env("ADDR", ":8102"), s.Rutas())
}
