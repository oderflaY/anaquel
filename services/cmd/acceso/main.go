// Servicio de acceso: cuentas, sesiones e invitaciones.
package main

import (
	"context"
	"log/slog"

	"anaquel/servicios/internal/acceso"
	"anaquel/servicios/internal/plataforma"
)

func main() {
	b := plataforma.ArrancarBase("acceso", acceso.Migraciones())
	llaveDatos, err := plataforma.Llave("CLAVE_DATOS", 32)
	if err != nil {
		plataforma.Morir("configuración", err)
	}
	llaveIndice, err := plataforma.Llave("CLAVE_INDICE", 32)
	if err != nil {
		plataforma.Morir("configuración", err)
	}
	cif, err := plataforma.NuevoCifrador(llaveDatos[:32])
	if err != nil {
		plataforma.Morir("configuración", err)
	}
	idx, _ := plataforma.NuevoIndice(llaveIndice)
	s := acceso.Nuevo(b.BD, cif, idx, b.Firmante, acceso.Config{
		CookieSegura: plataforma.Env("COOKIE_INSEGURA", "0") != "1",
		Demo:         b.Demo,
	})
	if b.Demo {
		if err := s.SembrarDemo(context.Background()); err != nil {
			plataforma.Morir("demo", err)
		}
	}
	if plataforma.Env("COOKIE_INSEGURA", "0") == "1" {
		slog.Warn("COOKIE_INSEGURA=1: la cookie de sesión NO lleva Secure. Úsalo solo en desarrollo por http://localhost")
	}
	plataforma.Arrancar("acceso", plataforma.Env("ADDR", ":8101"), s.Rutas())
}
