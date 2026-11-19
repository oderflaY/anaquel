// Package pagos cobra órdenes del mercado con la terminal del vendedor
// (Mercado Pago Point, Clip o la terminal virtual de demostración).
//
// Reglas de dinero: montos en centavos enteros; el monto sale siempre de la
// orden (mercado), nunca de lo que mande el navegador; un cobro en curso y un
// cobro aprobado por orden como máximo (índices únicos en Postgres); el id del
// cobro es la llave de idempotencia con la marca; los webhooks se verifican y
// después se consulta el estado real.
package pagos

import (
	"context"
	"embed"
	"errors"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migraciones/*.sql
var migracionesFS embed.FS

func Migraciones() fs.FS { sub, _ := fs.Sub(migracionesFS, "migraciones"); return sub }

type Servicio struct {
	bd         *pgxpool.Pool
	firma      *plataforma.Firmante
	mercado    *plataforma.ClienteInterno
	terminales map[string]Terminal
}

var nombres = map[string]string{"mercadopago": "Mercado Pago Point", "clip": "Clip", "simulador": "Terminal virtual (demo)"}

func Nuevo(bd *pgxpool.Pool, firma *plataforma.Firmante, urlMercado string, terminales ...Terminal) *Servicio {
	s := &Servicio{bd: bd, firma: firma, mercado: plataforma.NuevoClienteInterno(urlMercado, "pagos", firma), terminales: map[string]Terminal{}}
	for _, t := range terminales {
		s.terminales[t.Proveedor()] = t
	}
	return s
}

func (s *Servicio) Rutas() http.Handler {
	m := http.NewServeMux()
	vendedor := func(h http.HandlerFunc) http.HandlerFunc { return s.firma.Exigir(plataforma.Vendedor, h) }
	m.HandleFunc("GET /proveedores", vendedor(s.proveedores))
	m.HandleFunc("GET /terminales", vendedor(s.listarTerminales))
	m.HandleFunc("POST /terminales", vendedor(s.crearTerminal))
	m.HandleFunc("DELETE /terminales/{id}", vendedor(s.quitarTerminal))
	m.HandleFunc("POST /cobros", vendedor(s.crearCobro))
	m.HandleFunc("GET /cobros/{id}", vendedor(s.verCobro))
	m.HandleFunc("POST /cobros/{id}/cancelar", vendedor(s.cancelarCobro))
	m.HandleFunc("GET /registros", vendedor(s.listarRegistros))
	m.HandleFunc("POST /registros/{id}/vincular", vendedor(s.vincularRegistro))
	m.HandleFunc("GET /simulador/pendientes", vendedor(s.simPendientes))
	m.HandleFunc("POST /simulador/transacciones/{id}/{accion}", vendedor(s.simResolver))
	m.HandleFunc("POST /simulador/terminales/{id}/cobro-directo", vendedor(s.simDirecto))
	m.HandleFunc("POST /webhooks/{proveedor}", s.webhook)
	m.HandleFunc("GET /salud", func(w http.ResponseWriter, r *http.Request) {
		plataforma.Manejar(w, r, nil, s.bd.Ping(r.Context()))
	})
	return m
}

func (s *Servicio) proveedores(w http.ResponseWriter, r *http.Request) {
	type p struct {
		Clave  string `json:"clave"`
		Nombre string `json:"nombre"`
	}
	out := []p{}
	for _, k := range []string{"mercadopago", "clip", "simulador"} {
		if _, ok := s.terminales[k]; ok {
			out = append(out, p{k, nombres[k]})
		}
	}
	plataforma.Manejar(w, r, out, nil)
}

// ---- terminales ----

type TerminalVinculada struct {
	ID          int64     `json:"id"`
	Proveedor   string    `json:"proveedor"`
	Marca       string    `json:"marca"`
	Nombre      string    `json:"nombre"`
	Dispositivo string    `json:"dispositivo"`
	Creada      time.Time `json:"creada"`
}

func (s *Servicio) listarTerminales(w http.ResponseWriter, r *http.Request) {
	filas, err := s.bd.Query(r.Context(), "SELECT id, proveedor, nombre, dispositivo, creada FROM terminales WHERE cuenta_id = $1 AND activa ORDER BY id",
		plataforma.IdentidadDe(r).Cuenta)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (TerminalVinculada, error) {
		var t TerminalVinculada
		err := f.Scan(&t.ID, &t.Proveedor, &t.Nombre, &t.Dispositivo, &t.Creada)
		t.Marca = nombres[t.Proveedor]
		return t, err
	})
	plataforma.Manejar(w, r, lista, err)
}

func (s *Servicio) crearTerminal(w http.ResponseWriter, r *http.Request) {
	id := plataforma.IdentidadDe(r)
	var d struct {
		Proveedor   string `json:"proveedor"`
		Nombre      string `json:"nombre"`
		Dispositivo string `json:"dispositivo"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if _, ok := s.terminales[d.Proveedor]; !ok {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Esa marca de terminal no está disponible"))
		return
	}
	d.Nombre = strings.TrimSpace(d.Nombre)
	if n := utf8.RuneCountInString(d.Nombre); n < 2 || n > 40 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Ponle un nombre a la terminal (2 a 40 letras), por ejemplo «Mostrador»"))
		return
	}
	if d.Proveedor == "simulador" {
		d.Dispositivo = "SIM-" + strings.ToUpper(plataforma.Aleatorio(5))
	}
	d.Dispositivo = strings.TrimSpace(d.Dispositivo)
	if !dispositivoValido(d.Dispositivo) {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "El número de la terminal solo lleva letras, números, guiones y dos puntos (3 a 64)"))
		return
	}
	var n int
	_ = s.bd.QueryRow(r.Context(), "SELECT COUNT(*) FROM terminales WHERE cuenta_id = $1 AND activa", id.Cuenta).Scan(&n)
	if n >= 10 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(409, "Puedes vincular hasta 10 terminales"))
		return
	}
	var t TerminalVinculada
	err := s.bd.QueryRow(r.Context(), `INSERT INTO terminales (cuenta_id, proveedor, nombre, dispositivo) VALUES ($1, $2, $3, $4)
		RETURNING id, proveedor, nombre, dispositivo, creada`, id.Cuenta, d.Proveedor, d.Nombre, d.Dispositivo).
		Scan(&t.ID, &t.Proveedor, &t.Nombre, &t.Dispositivo, &t.Creada)
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23505" {
		err = plataforma.Fallo(409, "Esa terminal ya está vinculada a otra cuenta")
	}
	t.Marca = nombres[t.Proveedor]
	plataforma.Manejar(w, r, t, err)
}

func dispositivoValido(d string) bool {
	if len(d) < 3 || len(d) > 64 {
		return false
	}
	for _, c := range d {
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == ':') {
			return false
		}
	}
	return true
}

func (s *Servicio) quitarTerminal(w http.ResponseWriter, r *http.Request) {
	tid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	tag, err := s.bd.Exec(r.Context(), "UPDATE terminales SET activa = false WHERE id = $1 AND cuenta_id = $2 AND activa", tid, plataforma.IdentidadDe(r).Cuenta)
	if err == nil && tag.RowsAffected() == 0 {
		err = pgx.ErrNoRows
	}
	plataforma.Manejar(w, r, nil, err)
}

// ---- cobros ----

type Cobro struct {
	ID          string    `json:"id"`
	OrdenID     int64     `json:"orden_id"`
	Proveedor   string    `json:"proveedor"`
	Marca       string    `json:"marca"`
	Terminal    string    `json:"terminal"`
	MontoC      int64     `json:"monto_c"`
	Descripcion string    `json:"descripcion"`
	Estado      string    `json:"estado"`
	Detalle     string    `json:"detalle"`
	Creado      time.Time `json:"creado"`
	Actualizado time.Time `json:"actualizado"`
	dispositivo string
	ref         *string
	consultado  *time.Time
}

const columnasCobro = `c.id::text, c.orden_id, c.proveedor, t.nombre, c.monto_c, c.descripcion, c.estado, c.detalle, c.creado, c.actualizado,
	t.dispositivo, c.ref_externa, c.consultado`

func escanearCobro(f pgx.Row) (Cobro, error) {
	var c Cobro
	err := f.Scan(&c.ID, &c.OrdenID, &c.Proveedor, &c.Terminal, &c.MontoC, &c.Descripcion, &c.Estado, &c.Detalle, &c.Creado,
		&c.Actualizado, &c.dispositivo, &c.ref, &c.consultado)
	c.Marca = nombres[c.Proveedor]
	return c, err
}

func (s *Servicio) cobroDe(ctx context.Context, id string, cuenta int64) (Cobro, error) {
	if !esUUID(id) {
		return Cobro{}, pgx.ErrNoRows
	}
	return escanearCobro(s.bd.QueryRow(ctx, "SELECT "+columnasCobro+" FROM cobros c JOIN terminales t ON t.id = c.terminal_id WHERE c.id = $1 AND c.cuenta_id = $2", id, cuenta))
}

func esUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, c := range s {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
		} else if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return false
		}
	}
	return true
}

type ordenInterna struct {
	ID         int64  `json:"id"`
	VendedorID int64  `json:"vendedor_id"`
	Titulo     string `json:"titulo"`
	TotalC     int64  `json:"total_c"`
	Estado     string `json:"estado"`
}

// crearCobro manda a la terminal el total de la orden.
func (s *Servicio) crearCobro(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	var d struct {
		OrdenID    int64 `json:"orden_id"`
		TerminalID int64 `json:"terminal_id"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var proveedor, dispositivo string
	err := s.bd.QueryRow(ctx, "SELECT proveedor, dispositivo FROM terminales WHERE id = $1 AND cuenta_id = $2 AND activa", d.TerminalID, id.Cuenta).
		Scan(&proveedor, &dispositivo)
	if errors.Is(err, pgx.ErrNoRows) {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Elige una de tus terminales"))
		return
	}
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	term, ok := s.terminales[proveedor]
	if !ok {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(409, "Esa marca de terminal no está disponible ahora"))
		return
	}
	var o ordenInterna
	if err := s.mercado.Llamar(ctx, http.MethodGet, "/interno/ordenes/"+itoa(d.OrdenID), nil, &o); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if o.VendedorID != id.Cuenta { // la orden existe pero no es suya: igual que si no existiera
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "No lo encontramos"))
		return
	}
	if o.Estado != "pendiente" {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(409, "estado", "Esta orden ya no está pendiente de cobro"))
		return
	}
	desc := o.Titulo
	if utf8.RuneCountInString(desc) > 60 {
		desc = string([]rune(desc)[:60])
	}
	var cobroID string
	err = s.bd.QueryRow(ctx, `INSERT INTO cobros (orden_id, cuenta_id, terminal_id, proveedor, monto_c, descripcion) VALUES ($1, $2, $3, $4, $5, $6)
		RETURNING id::text`, o.ID, id.Cuenta, d.TerminalID, proveedor, o.TotalC, desc).Scan(&cobroID)
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23505" {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(409, "cobro_en_curso", "Ya hay un cobro en curso para esta orden"))
		return
	}
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	ctxT, cancelar := context.WithTimeout(ctx, 15*time.Second)
	defer cancelar()
	ref, err := term.Cobrar(ctxT, Solicitud{CobroID: cobroID, Dispositivo: dispositivo, MontoC: o.TotalC, Descripcion: desc})
	if err != nil {
		slog.Error("pagos: la terminal rechazó el cobro", "proveedor", proveedor, "err", err)
		_, _ = s.bd.Exec(ctx, "UPDATE cobros SET estado = 'error', detalle = 'La terminal no aceptó el cobro', notificado = true, actualizado = now() WHERE id = $1", cobroID)
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(502, "terminal", "La terminal no respondió. Revisa que esté encendida, con internet y en modo de cobro."))
		return
	}
	if _, err := s.bd.Exec(ctx, "UPDATE cobros SET estado = 'enviado', ref_externa = $2, actualizado = now() WHERE id = $1", cobroID, ref); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if err := s.mercado.Llamar(ctx, http.MethodPost, "/interno/ordenes/"+itoa(o.ID)+"/cobro", map[string]string{"estado": "enviado", "cobro_id": cobroID}, nil); err != nil {
		// La orden cambió mientras tanto (p. ej. la cancelaron): se cancela el cobro.
		_ = term.Cancelar(ctx, ref, dispositivo)
		_, _ = s.bd.Exec(ctx, "UPDATE cobros SET estado = 'cancelado', detalle = 'La orden cambió', notificado = true, actualizado = now() WHERE id = $1", cobroID)
		plataforma.Manejar(w, r, nil, err)
		return
	}
	c, err := s.cobroDe(ctx, cobroID, id.Cuenta)
	plataforma.Manejar(w, r, c, err)
}

// verCobro: la web consulta cada 2 s mientras espera. Si el cobro sigue en la
// terminal, se le pregunta a la marca (máximo cada 3 s por cobro).
func (s *Servicio) verCobro(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	c, err := s.cobroDe(ctx, r.PathValue("id"), id.Cuenta)
	if err == nil && c.Estado == EstadoEnviado && (c.consultado == nil || time.Since(*c.consultado) > 3*time.Second) {
		s.consultar(ctx, c)
		c, err = s.cobroDe(ctx, c.ID, id.Cuenta)
	}
	plataforma.Manejar(w, r, c, err)
}

func (s *Servicio) cancelarCobro(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	c, err := s.cobroDe(ctx, r.PathValue("id"), id.Cuenta)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if c.Estado != EstadoEnviado {
		plataforma.Manejar(w, r, nil, plataforma.FalloClave(409, "estado", "Este cobro ya terminó"))
		return
	}
	if t, ok := s.terminales[c.Proveedor]; ok && c.ref != nil {
		if err := t.Cancelar(ctx, *c.ref, c.dispositivo); err != nil {
			slog.Warn("pagos: no se pudo cancelar en la terminal", "err", err)
			plataforma.Manejar(w, r, nil, plataforma.FalloClave(502, "terminal", "No se pudo cancelar en la terminal. Cancélalo desde el aparato."))
			return
		}
	}
	s.resolver(ctx, c.ID, Consulta{Estado: EstadoCancelado, Detalle: "Cancelado por quien vende"})
	c, err = s.cobroDe(ctx, c.ID, id.Cuenta)
	plataforma.Manejar(w, r, c, err)
}

func (s *Servicio) consultar(ctx context.Context, c Cobro) {
	_, _ = s.bd.Exec(ctx, "UPDATE cobros SET consultado = now() WHERE id = $1", c.ID)
	t, ok := s.terminales[c.Proveedor]
	if !ok || c.ref == nil {
		return
	}
	ctxT, cancelar := context.WithTimeout(ctx, 8*time.Second)
	defer cancelar()
	k, err := t.Consultar(ctxT, *c.ref)
	if err != nil {
		slog.Warn("pagos: consulta a la terminal", "proveedor", c.Proveedor, "err", err)
		return
	}
	if k.Estado != EstadoEnviado {
		s.resolver(ctx, c.ID, k)
	}
}

// resolver deja el cobro en su estado final (una sola vez), lo anota en el
// registro de cobros si se aprobó y avisa a mercado.
func (s *Servicio) resolver(ctx context.Context, cobroID string, k Consulta) {
	detalle := k.Detalle
	if len(detalle) > 200 {
		detalle = detalle[:200]
	}
	var c Cobro
	err := pgx.BeginFunc(ctx, s.bd, func(tx pgx.Tx) error {
		var e error
		c, e = escanearCobro(tx.QueryRow(ctx, `UPDATE cobros c SET estado = $2, detalle = $3, actualizado = now(), notificado = false
			FROM terminales t WHERE c.id = $1 AND c.estado = 'enviado' AND t.id = c.terminal_id RETURNING `+columnasCobro, cobroID, k.Estado, detalle))
		if e != nil {
			return e
		}
		if k.Estado != EstadoAprobado {
			return nil
		}
		if k.MontoC != 0 && k.MontoC != c.MontoC {
			// La marca dice que se cobró otro monto: no se marca pagada la orden.
			slog.Error("pagos: monto aprobado distinto al de la orden", "cobro", cobroID, "esperado", c.MontoC, "cobrado", k.MontoC)
			_, e = tx.Exec(ctx, "UPDATE cobros SET estado = 'error', detalle = 'El monto cobrado no coincide', notificado = true WHERE id = $1", cobroID)
			return e
		}
		pagado := k.PagadoEn
		if pagado.IsZero() {
			pagado = time.Now()
		}
		_, e = tx.Exec(ctx, `INSERT INTO registros (cuenta_id, terminal_id, proveedor, ref_externa, monto_c, origen, cobro_id, orden_id, detalle, pagado_en)
			SELECT cuenta_id, terminal_id, proveedor, ref_externa, monto_c, 'app', id, orden_id, $2, $3 FROM cobros WHERE id = $1
			ON CONFLICT (proveedor, ref_externa) DO NOTHING`, cobroID, detalle, pagado)
		return e
	})
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			slog.Error("pagos: resolver", "err", err)
		}
		return
	}
	s.notificar(ctx, cobroID)
}

func (s *Servicio) notificar(ctx context.Context, cobroID string) {
	var orden int64
	var estado, detalle string
	if err := s.bd.QueryRow(ctx, "SELECT orden_id, estado, detalle FROM cobros WHERE id = $1", cobroID).Scan(&orden, &estado, &detalle); err != nil {
		return
	}
	err := s.mercado.Llamar(ctx, http.MethodPost, "/interno/ordenes/"+itoa(orden)+"/cobro",
		map[string]string{"estado": estado, "cobro_id": cobroID, "nota": detalle}, nil)
	var e plataforma.ErrHTTP
	if err != nil && !(errors.As(err, &e) && e.Codigo == http.StatusConflict) {
		slog.Warn("pagos: no se pudo avisar a mercado; se reintentará", "err", err)
		return
	}
	_, _ = s.bd.Exec(ctx, "UPDATE cobros SET notificado = true WHERE id = $1", cobroID)
}

// Conciliar corre en segundo plano: consulta cobros que siguen en la terminal
// (por si un webhook no llegó), vence los de más de 15 min y reintenta avisos.
// Un candado de Postgres evita que dos réplicas lo hagan a la vez.
func (s *Servicio) Conciliar(ctx context.Context) {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		_ = pgx.BeginFunc(ctx, s.bd, func(tx pgx.Tx) error {
			var mio bool
			if err := tx.QueryRow(ctx, "SELECT pg_try_advisory_xact_lock(7313001)").Scan(&mio); err != nil || !mio {
				return err
			}
			filas, err := tx.Query(ctx, "SELECT "+columnasCobro+` FROM cobros c JOIN terminales t ON t.id = c.terminal_id
				WHERE c.estado = 'enviado' AND (c.consultado IS NULL OR c.consultado < now() - interval '4 seconds') LIMIT 50`)
			if err != nil {
				return err
			}
			cobros, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (Cobro, error) { return escanearCobro(f) })
			if err != nil {
				return err
			}
			for _, c := range cobros {
				if time.Since(c.Creado) > 15*time.Minute {
					if t, ok := s.terminales[c.Proveedor]; ok && c.ref != nil {
						_ = t.Cancelar(ctx, *c.ref, c.dispositivo)
					}
					s.resolver(ctx, c.ID, Consulta{Estado: EstadoCancelado, Detalle: "Venció: nadie pagó en 15 minutos"})
					continue
				}
				s.consultar(ctx, c)
			}
			var pendientes []string
			filas, err = tx.Query(ctx, "SELECT id::text FROM cobros WHERE NOT notificado AND estado IN ('aprobado', 'rechazado', 'cancelado') LIMIT 50")
			if err != nil {
				return err
			}
			if pendientes, err = pgx.CollectRows(filas, pgx.RowTo[string]); err != nil {
				return err
			}
			for _, id := range pendientes {
				s.notificar(ctx, id)
			}
			// Cobros directos de la terminal virtual cuyo aviso no llegó.
			if _, ok := s.terminales["simulador"]; ok {
				filas, err = tx.Query(ctx, `SELECT x.id FROM sim_transacciones x WHERE x.estado = 'aprobada' AND x.referencia = ''
					AND x.actualizada < now() - interval '10 seconds'
					AND NOT EXISTS (SELECT 1 FROM registros g WHERE g.proveedor = 'simulador' AND g.ref_externa = x.id) LIMIT 50`)
				if err != nil {
					return err
				}
				sueltos, err := pgx.CollectRows(filas, pgx.RowTo[string])
				if err != nil {
					return err
				}
				for _, ref := range sueltos {
					if err := s.procesarNotificacion(ctx, "simulador", ref); err != nil {
						slog.Warn("pagos: conciliar venta directa", "err", err)
					}
				}
			}
			return nil
		})
	}
}

// ---- webhooks ----

func (s *Servicio) webhook(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	prov := r.PathValue("proveedor")
	t, ok := s.terminales[prov]
	if !ok {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "No existe"))
		return
	}
	cuerpo, err := io.ReadAll(io.LimitReader(r.Body, 64<<10))
	if err != nil {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Cuerpo inválido"))
		return
	}
	ref, evento, err := t.Webhook(r, cuerpo)
	if err != nil {
		slog.Warn("pagos: webhook rechazado", "proveedor", prov)
		plataforma.Manejar(w, r, nil, plataforma.Fallo(401, "Firma inválida"))
		return
	}
	tag, err := s.bd.Exec(ctx, "INSERT INTO webhooks (proveedor, evento_id) VALUES ($1, $2) ON CONFLICT DO NOTHING", prov, evento)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if tag.RowsAffected() == 0 {
		plataforma.Manejar(w, r, nil, nil) // repetido: ya se procesó
		return
	}
	if err := s.procesarNotificacion(ctx, prov, ref); err != nil {
		// Se olvida el aviso para que la marca lo reintente (responde 500).
		slog.Error("pagos: no se pudo procesar la notificación", "proveedor", prov, "err", err)
		_, _ = s.bd.Exec(context.WithoutCancel(ctx), "DELETE FROM webhooks WHERE proveedor = $1 AND evento_id = $2", prov, evento)
		plataforma.Manejar(w, r, nil, errors.New("procesar notificación"))
		return
	}
	plataforma.Manejar(w, r, nil, nil)
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }
