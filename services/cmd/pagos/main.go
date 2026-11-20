// Servicio de pagos: terminales (Mercado Pago Point, Clip, simulador) y cobros.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"anaquel/servicios/internal/pagos"
	"anaquel/servicios/internal/plataforma"
)

func main() {
	b := plataforma.ArrancarBase("pagos", pagos.Migraciones())
	cli := &http.Client{Timeout: 15 * time.Second}
	var terminales []pagos.Terminal
	if tok, err := plataforma.Secreto("MP_ACCESS_TOKEN"); err == nil {
		secreto, _ := plataforma.Secreto("MP_WEBHOOK_SECRET")
		if secreto == "" {
			slog.Warn("MP_WEBHOOK_SECRET vacío: los webhooks de Mercado Pago se rechazarán (el conciliador consultará el estado)")
		}
		terminales = append(terminales, &pagos.MercadoPago{Token: tok, SecretoWebhook: secreto,
			Base: plataforma.Env("MP_URL", "https://api.mercadopago.com"), HTTP: cli})
	}
	if llave, err := plataforma.Secreto("CLIP_API_KEY"); err == nil {
		secreto, _ := plataforma.Secreto("CLIP_API_SECRET")
		terminales = append(terminales, &pagos.Clip{Llave: llave, Secreto: secreto,
			Base: plataforma.Env("CLIP_URL", "https://api.payclip.com"), HTTP: cli})
	}
	if plataforma.Env("SIMULADOR", map[bool]string{true: "1", false: "0"}[b.Demo]) == "1" {
		secreto, err := plataforma.Secreto("SIMULADOR_SECRETO")
		if err != nil || len(secreto) < 32 {
			plataforma.Morir("configuración", errors.New("SIMULADOR_SECRETO debe tener al menos 32 caracteres"))
		}
		terminales = append(terminales, &pagos.Simulador{BD: b.BD, Secreto: secreto, HTTP: cli,
			URLWebhook: plataforma.Env("SIMULADOR_WEBHOOK", "http://127.0.0.1"+plataforma.Env("ADDR", ":8104")+"/webhooks/simulador")})
	}
	s := pagos.Nuevo(b.BD, b.Firmante, plataforma.Env("URL_MERCADO", "http://127.0.0.1:8102"), terminales...)
	go s.Conciliar(context.Background())
	plataforma.Arrancar("pagos", plataforma.Env("ADDR", ":8104"), s.Rutas())
}
