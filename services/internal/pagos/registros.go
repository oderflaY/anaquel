package pagos

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
)

// procesarNotificacion es el corazón de la captura automática. Llega una
// notificación (webhook verificado, o el conciliador) con la referencia de la
// marca; se CONSULTA el estado real a la marca y:
//  1. si corresponde a un cobro iniciado desde la app → se resuelve el cobro
//     (y la orden pasa a "pagada");
//  2. si no (alguien cobró directo en el aparato) → se registra el ingreso a
//     nombre del dueño de esa terminal, como "venta directa en terminal".
func (s *Servicio) procesarNotificacion(ctx context.Context, prov, ref string) error {
	t, ok := s.terminales[prov]
	if !ok {
		return fmt.Errorf("proveedor desconocido %q", prov)
	}
	ctxT, cancelar := context.WithTimeout(ctx, 10*time.Second)
	defer cancelar()
	k, err := t.Consultar(ctxT, ref)
	if err != nil {
		return fmt.Errorf("consultar %s: %w", prov, err)
	}

	// 1) ¿Es un cobro nuestro? Por la referencia de la marca o por nuestro id.
	c, err := escanearCobro(s.bd.QueryRow(ctx, "SELECT "+columnasCobro+
		" FROM cobros c JOIN terminales t ON t.id = c.terminal_id WHERE c.proveedor = $1 AND (c.ref_externa = $2 OR c.id::text = $3)",
		prov, ref, k.Referencia))
	if err == nil {
		if c.Estado == EstadoEnviado && k.Estado != EstadoEnviado {
			s.resolver(ctx, c.ID, k)
		}
		return nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}

	// 2) Cobro directo en la terminal.
	if k.Estado != EstadoAprobado {
		return nil
	}
	if k.MontoC <= 0 || k.Dispositivo == "" {
		return errors.New("la marca no informó monto o terminal")
	}
	var terminal, cuenta int64
	err = s.bd.QueryRow(ctx, "SELECT id, cuenta_id FROM terminales WHERE proveedor = $1 AND dispositivo = $2 AND activa", prov, k.Dispositivo).
		Scan(&terminal, &cuenta)
	if errors.Is(err, pgx.ErrNoRows) {
		slog.Warn("pagos: pago en una terminal que nadie vinculó; no se registra", "proveedor", prov)
		return nil
	}
	if err != nil {
		return err
	}
	pagado := k.PagadoEn
	if pagado.IsZero() {
		pagado = time.Now()
	}
	detalle := k.Detalle
	if detalle == "" {
		detalle = "Cobro directo en la terminal"
	}
	tag, err := s.bd.Exec(ctx, `INSERT INTO registros (cuenta_id, terminal_id, proveedor, ref_externa, monto_c, origen, detalle, pagado_en)
		VALUES ($1, $2, $3, $4, $5, 'terminal', $6, $7) ON CONFLICT (proveedor, ref_externa) DO NOTHING`,
		cuenta, terminal, prov, ref, k.MontoC, detalle, pagado)
	if err == nil && tag.RowsAffected() > 0 {
		slog.Info("pagos: venta directa en terminal registrada", "proveedor", prov, "monto_c", k.MontoC)
	}
	return err
}

type Registro struct {
	ID        int64     `json:"id"`
	MontoC    int64     `json:"monto_c"`
	Proveedor string    `json:"proveedor"`
	Marca     string    `json:"marca"`
	Terminal  string    `json:"terminal"`
	Origen    string    `json:"origen"` // app | terminal
	OrdenID   *int64    `json:"orden_id"`
	Detalle   string    `json:"detalle"`
	PagadoEn  time.Time `json:"pagado_en"`
}

// listarRegistros: ingresos de la cuenta. Con ?desde=<id> regresa solo los
// nuevos (la web lo usa para avisar "Pago recibido" en cuanto llega).
func (s *Servicio) listarRegistros(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	cuenta := plataforma.IdentidadDe(r).Cuenta
	desde, _ := strconv.ParseInt(r.URL.Query().Get("desde"), 10, 64)
	filas, err := s.bd.Query(ctx, `SELECT g.id, g.monto_c, g.proveedor, COALESCE(t.nombre, ''), g.origen, g.orden_id, g.detalle, g.pagado_en
		FROM registros g LEFT JOIN terminales t ON t.id = g.terminal_id
		WHERE g.cuenta_id = $1 AND g.id > $2 ORDER BY g.id DESC LIMIT 100`, cuenta, max(desde, 0))
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (Registro, error) {
		var x Registro
		err := f.Scan(&x.ID, &x.MontoC, &x.Proveedor, &x.Terminal, &x.Origen, &x.OrdenID, &x.Detalle, &x.PagadoEn)
		x.Marca = nombres[x.Proveedor]
		return x, err
	})
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	// Totales del día en hora de Durango.
	var hoy int64
	var cuantos int
	err = s.bd.QueryRow(ctx, `SELECT COALESCE(sum(monto_c), 0), COUNT(*) FROM registros WHERE cuenta_id = $1
		AND (pagado_en AT TIME ZONE 'America/Monterrey')::date = (now() AT TIME ZONE 'America/Monterrey')::date`, cuenta).Scan(&hoy, &cuantos)
	plataforma.Manejar(w, r, map[string]any{"items": lista, "hoy_c": hoy, "hoy_cobros": cuantos}, err)
}

// vincularRegistro: una venta directa en terminal se liga a una orden
// pendiente del mismo vendedor y del mismo monto; la orden queda pagada.
func (s *Servicio) vincularRegistro(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	cuenta := plataforma.IdentidadDe(r).Cuenta
	rid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var d struct {
		OrdenID int64 `json:"orden_id"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var monto int64
	var origen string
	var orden *int64
	if err := s.bd.QueryRow(ctx, "SELECT monto_c, origen, orden_id FROM registros WHERE id = $1 AND cuenta_id = $2", rid, cuenta).
		Scan(&monto, &origen, &orden); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if origen != "terminal" || orden != nil {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(409, "vinculado", "Este pago ya está ligado a una orden"))
		return
	}
	var o ordenInterna
	if err := s.mercado.Llamar(ctx, http.MethodGet, "/interno/ordenes/"+itoa(d.OrdenID), nil, &o); err != nil || o.VendedorID != cuenta {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "No encontramos esa orden entre tus ventas"))
		return
	}
	if o.Estado != "pendiente" {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(409, "estado", "Esa orden ya no está pendiente de cobro"))
		return
	}
	if o.TotalC != monto {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(409, "monto", "El monto del pago no coincide con el total de la orden"))
		return
	}
	if err := s.mercado.Llamar(ctx, http.MethodPost, "/interno/ordenes/"+itoa(o.ID)+"/cobro",
		map[string]string{"estado": "aprobado", "cobro_id": "reg-" + itoa(rid), "nota": "Pagado directo en la terminal"}, nil); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	_, err = s.bd.Exec(ctx, "UPDATE registros SET orden_id = $1 WHERE id = $2 AND orden_id IS NULL", o.ID, rid)
	plataforma.Manejar(w, r, nil, err)
}
