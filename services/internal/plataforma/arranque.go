package plataforma

import (
	"context"
	"io/fs"
	"log/slog"
	"os"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Base es lo que todo servicio necesita al arrancar.
type Base struct {
	BD       *pgxpool.Pool
	Firmante *Firmante
	Demo     bool
}

// Morir registra el error y termina (solo durante el arranque).
func Morir(msg string, err error) {
	slog.Error(msg, "err", err)
	os.Exit(1)
}

// ArrancarBase lee CLAVE_INTERNA y BD_DSN, conecta y migra el esquema.
func ArrancarBase(esquema string, migraciones fs.FS) Base {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	clave, err := Llave("CLAVE_INTERNA", 32)
	if err != nil {
		Morir("configuración", err)
	}
	f, _ := NuevoFirmante(clave)
	dsn, err := Secreto("BD_DSN")
	if err != nil {
		Morir("configuración", err)
	}
	ctx := context.Background()
	bd, err := ConectarBD(ctx, dsn)
	if err != nil {
		Morir("base de datos", err)
	}
	if migraciones != nil {
		if err := Migrar(ctx, bd, esquema, migraciones); err != nil {
			Morir("migraciones", err)
		}
	}
	return Base{BD: bd, Firmante: f, Demo: Env("DEMO", "0") == "1"}
}
