package pagos

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Simulador es una "marca" de terminal completa para la demo y las pruebas.
// Funciona igual que una real:
//   - tiene su propio "servidor" (tabla sim_transacciones),
//   - cuando una transacción se aprueba o rechaza, NOTIFICA por HTTP al
//     webhook del sistema de pagos, firmado con HMAC (mismo formato que
//     Mercado Pago),
//   - y responde a Consultar con el estado real.
//
// Así se prueba de punta a punta la captura automática de notificaciones,
// incluyendo cobros hechos directo en la terminal (sin pasar por la app).
type Simulador struct {
	BD         *pgxpool.Pool
	Secreto    string // con el que firma sus webhooks
	URLWebhook string // a dónde notifica (el webhook del servicio de pagos)
	HTTP       *http.Client
}

func (s *Simulador) Proveedor() string { return "simulador" }

func (s *Simulador) Cobrar(ctx context.Context, sol Solicitud) (string, error) {
	id := "SIMTX-" + strings.ToUpper(plataforma.Aleatorio(8))
	_, err := s.BD.Exec(ctx, `INSERT INTO sim_transacciones (id, dispositivo, monto_c, referencia, concepto) VALUES ($1, $2, $3, $4, $5)`,
		id, sol.Dispositivo, sol.MontoC, sol.CobroID, sol.Descripcion)
	return id, err
}

func (s *Simulador) Consultar(ctx context.Context, ref string) (Consulta, error) {
	var c Consulta
	var estado string
	err := s.BD.QueryRow(ctx, "SELECT estado, monto_c, dispositivo, referencia, actualizada FROM sim_transacciones WHERE id = $1", ref).
		Scan(&estado, &c.MontoC, &c.Dispositivo, &c.Referencia, &c.PagadoEn)
	if err != nil {
		return c, err
	}
	c.Estado = map[string]string{"en_terminal": EstadoEnviado, "aprobada": EstadoAprobado, "rechazada": EstadoRechazado, "cancelada": EstadoCancelado}[estado]
	c.Detalle = map[string]string{"aprobada": "Pago aprobado", "rechazada": "Tarjeta rechazada", "cancelada": "Cancelado en la terminal"}[estado]
	return c, nil
}

func (s *Simulador) Cancelar(ctx context.Context, ref, _ string) error {
	_, err := s.BD.Exec(ctx, "UPDATE sim_transacciones SET estado = 'cancelada', actualizada = now() WHERE id = $1 AND estado = 'en_terminal'", ref)
	return err
}

func (s *Simulador) Webhook(r *http.Request, cuerpo []byte) (string, string, error) {
	return verificarFirma(s.Secreto, r, cuerpo)
}

// notificar manda el aviso firmado, como lo haría el servidor de la marca.
func (s *Simulador) notificar(ctx context.Context, txID string) error {
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	reqID := plataforma.Aleatorio(12)
	u := s.URLWebhook + "?type=order&data.id=" + url.QueryEscape(txID)
	cuerpo := []byte(`{"action":"order.updated","type":"order","data":{"id":"` + txID + `"}}`)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(cuerpo))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("x-request-id", reqID)
	req.Header.Set("x-signature", "ts="+ts+",v1="+firmaHMAC(s.Secreto, txID, reqID, ts))
	res, err := s.HTTP.Do(req)
	if err != nil {
		return err
	}
	res.Body.Close()
	if res.StatusCode >= 300 {
		return errors.New("el webhook respondió " + res.Status)
	}
	return nil
}

// ---- pantalla de la terminal virtual ----

type TransaccionSim struct {
	ID       string    `json:"id"`
	Terminal string    `json:"terminal"`
	MontoC   int64     `json:"monto_c"`
	Concepto string    `json:"concepto"`
	Creada   time.Time `json:"creada"`
}

func (sv *Servicio) simulador() (*Simulador, bool) {
	t, ok := sv.terminales["simulador"].(*Simulador)
	return t, ok
}

// simPendientes: lo que está esperando tarjeta en mis terminales virtuales.
func (sv *Servicio) simPendientes(w http.ResponseWriter, r *http.Request) {
	sim, ok := sv.simulador()
	if !ok {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "La terminal virtual no está activa"))
		return
	}
	filas, err := sim.BD.Query(r.Context(), `SELECT x.id, t.nombre, x.monto_c, x.concepto, x.creada FROM sim_transacciones x
		JOIN terminales t ON t.proveedor = 'simulador' AND t.dispositivo = x.dispositivo AND t.activa
		WHERE t.cuenta_id = $1 AND x.estado = 'en_terminal' ORDER BY x.creada`, plataforma.IdentidadDe(r).Cuenta)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (TransaccionSim, error) {
		var t TransaccionSim
		return t, f.Scan(&t.ID, &t.Terminal, &t.MontoC, &t.Concepto, &t.Creada)
	})
	plataforma.Manejar(w, r, lista, err)
}

// simResolver: "pasar la tarjeta" (aprobar) o simular un rechazo.
func (sv *Servicio) simResolver(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	sim, ok := sv.simulador()
	if !ok {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "La terminal virtual no está activa"))
		return
	}
	estado := map[string]string{"aprobar": "aprobada", "rechazar": "rechazada"}[r.PathValue("accion")]
	if estado == "" {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Acción desconocida"))
		return
	}
	tag, err := sim.BD.Exec(ctx, `UPDATE sim_transacciones x SET estado = $1, actualizada = now()
		FROM terminales t WHERE x.id = $2 AND x.estado = 'en_terminal'
			AND t.proveedor = 'simulador' AND t.dispositivo = x.dispositivo AND t.activa AND t.cuenta_id = $3`,
		estado, r.PathValue("id"), plataforma.IdentidadDe(r).Cuenta)
	if err == nil && tag.RowsAffected() == 0 {
		err = plataforma.FalloClave(409, "estado", "Esta transacción ya terminó")
	}
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	sv.simAvisar(ctx, sim, r.PathValue("id"))
	plataforma.Manejar(w, r, nil, nil)
}

// simDirecto: alguien cobra directo en la terminal, sin pasar por la app.
// El sistema de pagos se entera solo por la notificación y lo registra.
func (sv *Servicio) simDirecto(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	sim, ok := sv.simulador()
	if !ok {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "La terminal virtual no está activa"))
		return
	}
	tid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var d struct {
		MontoC   int64  `json:"monto_c"`
		Concepto string `json:"concepto"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if d.MontoC < 100 || d.MontoC > 10_000_000 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "El monto debe estar entre $1 y $100,000"))
		return
	}
	var disp string
	if err := sim.BD.QueryRow(ctx, "SELECT dispositivo FROM terminales WHERE id = $1 AND cuenta_id = $2 AND proveedor = 'simulador' AND activa",
		tid, plataforma.IdentidadDe(r).Cuenta).Scan(&disp); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	concepto := strings.TrimSpace(d.Concepto)
	if len(concepto) > 60 {
		concepto = concepto[:60]
	}
	id := "SIMTX-" + strings.ToUpper(plataforma.Aleatorio(8))
	if _, err := sim.BD.Exec(ctx, `INSERT INTO sim_transacciones (id, dispositivo, monto_c, concepto, estado) VALUES ($1, $2, $3, $4, 'aprobada')`,
		id, disp, d.MontoC, concepto); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	sv.simAvisar(ctx, sim, id)
	plataforma.Manejar(w, r, map[string]string{"id": id}, nil)
}

// simAvisar notifica; si el webhook falla, el conciliador lo encontrará igual.
func (sv *Servicio) simAvisar(ctx context.Context, sim *Simulador, id string) {
	ctx, cancelar := context.WithTimeout(context.WithoutCancel(ctx), 8*time.Second)
	defer cancelar()
	if err := sim.notificar(ctx, id); err != nil {
		slog.Warn("terminal virtual: no se pudo notificar; el conciliador lo recogerá", "err", err)
	}
}
