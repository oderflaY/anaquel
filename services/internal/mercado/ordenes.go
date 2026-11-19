package mercado

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"anaquel/servicios/internal/plataforma"

	"github.com/jackc/pgx/v5"
)

type Evento struct {
	Estado string    `json:"estado"`
	Por    string    `json:"por"`
	Nota   string    `json:"nota"`
	Creado time.Time `json:"creado"`
}

type Orden struct {
	ID            int64     `json:"id"`
	Rol           string    `json:"rol"` // compra | venta (desde el punto de vista de quien pregunta)
	Contraparte   string    `json:"contraparte"`
	ContraSlug    string    `json:"contraparte_slug"`
	PublicacionID *int64    `json:"publicacion_id"`
	Titulo        string    `json:"titulo"`
	Unidad        string    `json:"unidad"`
	Cantidad      int       `json:"cantidad"`
	PrecioC       int64     `json:"precio_c"`
	TotalC        int64     `json:"total_c"`
	Nota          string    `json:"nota"`
	Estado        string    `json:"estado"`
	CobroID       *string   `json:"cobro_id"`
	Foto          *Foto     `json:"foto"`
	Creada        time.Time `json:"creada"`
	Actualizada   time.Time `json:"actualizada"`
	Eventos       []Evento  `json:"eventos,omitempty"`
}

const columnasOrden = `o.id, o.comprador_id, o.vendedor_id, c.nombre, c.slug, v.nombre, v.slug, o.publicacion_id, o.titulo, o.unidad,
	o.cantidad, o.precio_c, o.total_c, o.nota, o.estado, o.cobro_id, o.creada, o.actualizada, f.archivo, f.alt`

const desdeOrden = ` FROM ordenes o JOIN perfiles c ON c.cuenta_id = o.comprador_id JOIN perfiles v ON v.cuenta_id = o.vendedor_id
	LEFT JOIN LATERAL (SELECT archivo, alt FROM fotos WHERE publicacion_id = o.publicacion_id ORDER BY orden, id LIMIT 1) f ON true`

func escanearOrden(f pgx.Row, yo int64) (Orden, error) {
	var o Orden
	var comprador, vendedor int64
	var cn, cs, vn, vs string
	var archivo, alt *string
	err := f.Scan(&o.ID, &comprador, &vendedor, &cn, &cs, &vn, &vs, &o.PublicacionID, &o.Titulo, &o.Unidad, &o.Cantidad,
		&o.PrecioC, &o.TotalC, &o.Nota, &o.Estado, &o.CobroID, &o.Creada, &o.Actualizada, &archivo, &alt)
	if err != nil {
		return o, err
	}
	if comprador == yo {
		o.Rol, o.Contraparte, o.ContraSlug = "compra", vn, vs
	} else {
		o.Rol, o.Contraparte, o.ContraSlug = "venta", cn, cs
	}
	if archivo != nil {
		o.Foto = &Foto{URL: urlFoto(*archivo), Alt: *alt}
	}
	return o, nil
}

func evento(ctx context.Context, tx pgx.Tx, orden int64, estado, por, nota string) error {
	_, err := tx.Exec(ctx, "INSERT INTO orden_eventos (orden_id, estado, por, nota) VALUES ($1, $2, $3, $4)", orden, estado, por, nota)
	return err
}

// crearOrden "aparta" el producto: descuenta existencias en la misma
// transacción (nadie puede apartar más de lo que hay, aunque compren al mismo
// tiempo) y deja la orden en "pendiente" hasta que el vendedor cobre.
func (s *Servicio) crearOrden(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	var d struct {
		PublicacionID int64  `json:"publicacion_id"`
		Cantidad      int    `json:"cantidad"`
		Nota          string `json:"nota"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	d.Nota = limpiar(d.Nota)
	if d.Cantidad < 1 || d.Cantidad > 100_000 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "La cantidad debe estar entre 1 y 100,000"))
		return
	}
	if utf8.RuneCountInString(d.Nota) > 300 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "La nota puede tener hasta 300 letras"))
		return
	}
	if _, _, err := s.asegurarPerfil(ctx, id); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var nueva int64
	err := s.enTx(ctx, id.Cuenta, "", func(tx pgx.Tx) error {
		var vendedor int64
		var titulo, unidad string
		var precio int64
		var minimo int
		var existencias *int
		err := tx.QueryRow(ctx, `SELECT vendedor_id, titulo, unidad, precio_c, minimo, existencias FROM publicaciones
			WHERE id = $1 AND estado = 'activa' FOR UPDATE`, d.PublicacionID).Scan(&vendedor, &titulo, &unidad, &precio, &minimo, &existencias)
		if errors.Is(err, pgx.ErrNoRows) {
			return plataforma.FalloClave(404, "no_disponible", "Este producto ya no está disponible")
		}
		if err != nil {
			return err
		}
		switch {
		case vendedor == id.Cuenta:
			return plataforma.Fallo(400, "No puedes apartar tus propios productos")
		case d.Cantidad < minimo:
			return plataforma.Fallo(400, "La compra mínima es de "+itoa(int64(minimo)))
		case existencias != nil && *existencias < d.Cantidad:
			if *existencias == 0 {
				return plataforma.FalloClave(409, "agotado", "Se agotó. Prueba con otro vendedor.")
			}
			return plataforma.FalloClave(409, "sin_existencias", "Solo quedan "+itoa(int64(*existencias))+" disponibles")
		}
		if existencias != nil {
			if _, err := tx.Exec(ctx, "UPDATE publicaciones SET existencias = existencias - $1 WHERE id = $2", d.Cantidad, d.PublicacionID); err != nil {
				return err
			}
		}
		if err := tx.QueryRow(ctx, `INSERT INTO ordenes (comprador_id, vendedor_id, publicacion_id, titulo, unidad, cantidad, precio_c, total_c, nota)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING id`,
			id.Cuenta, vendedor, d.PublicacionID, titulo, unidad, d.Cantidad, precio, int64(d.Cantidad)*precio, d.Nota).Scan(&nueva); err != nil {
			return err
		}
		return evento(ctx, tx, nueva, "pendiente", "comprador", "")
	})
	plataforma.Manejar(w, r, map[string]int64{"id": nueva}, err)
}

func (s *Servicio) listarOrdenes(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	col := "o.comprador_id"
	if r.URL.Query().Get("rol") == "ventas" {
		col = "o.vendedor_id"
	}
	var lista []Orden
	err := s.enTx(ctx, id.Cuenta, "", func(tx pgx.Tx) error {
		filas, err := tx.Query(ctx, "SELECT "+columnasOrden+desdeOrden+" WHERE "+col+" = $1 ORDER BY o.id DESC LIMIT 100", id.Cuenta)
		if err != nil {
			return err
		}
		lista, err = pgx.CollectRows(filas, func(f pgx.CollectableRow) (Orden, error) { return escanearOrden(f, id.Cuenta) })
		return err
	})
	if lista == nil {
		lista = []Orden{}
	}
	plataforma.Manejar(w, r, lista, err)
}

// ordenCompleta lee una orden con su historial. La RLS de Postgres garantiza
// que solo la vean sus partes: para cualquier otra cuenta "no existe".
func (s *Servicio) ordenCompleta(ctx context.Context, tx pgx.Tx, oid, yo int64) (Orden, error) {
	o, err := escanearOrden(tx.QueryRow(ctx, "SELECT "+columnasOrden+desdeOrden+" WHERE o.id = $1", oid), yo)
	if err != nil {
		return o, err
	}
	filas, err := tx.Query(ctx, "SELECT estado, por, nota, creado FROM orden_eventos WHERE orden_id = $1 ORDER BY id", oid)
	if err != nil {
		return o, err
	}
	o.Eventos, err = pgx.CollectRows(filas, func(f pgx.CollectableRow) (Evento, error) {
		var e Evento
		return e, f.Scan(&e.Estado, &e.Por, &e.Nota, &e.Creado)
	})
	return o, err
}

func (s *Servicio) verOrden(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	oid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var o Orden
	err = s.enTx(ctx, id.Cuenta, "", func(tx pgx.Tx) error {
		var e error
		o, e = s.ordenCompleta(ctx, tx, oid, id.Cuenta)
		return e
	})
	plataforma.Manejar(w, r, o, err)
}

// accionOrden: cancelar (comprador o vendedor, mientras esté pendiente) y
// entregar (vendedor, cuando ya está pagada).
func (s *Servicio) accionOrden(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := plataforma.IdentidadDe(r)
	oid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	accion := r.PathValue("accion")
	if accion != "cancelar" && accion != "entregar" {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(404, "Acción desconocida"))
		return
	}
	var o Orden
	err = s.enTx(ctx, id.Cuenta, "", func(tx pgx.Tx) error {
		var comprador, vendedor int64
		var estado string
		var pub *int64
		var cantidad int
		err := tx.QueryRow(ctx, "SELECT comprador_id, vendedor_id, estado, publicacion_id, cantidad FROM ordenes WHERE id = $1 FOR UPDATE", oid).
			Scan(&comprador, &vendedor, &estado, &pub, &cantidad)
		if err != nil {
			return err
		}
		por := "comprador"
		if vendedor == id.Cuenta {
			por = "vendedor"
		}
		switch accion {
		case "cancelar":
			if estado == "por_cobrar" {
				return plataforma.FalloClave(409, "cobro_en_curso", "Hay un cobro en la terminal. Cancélalo primero.")
			}
			if estado != "pendiente" {
				return plataforma.FalloClave(409, "estado", "Esta orden ya no se puede cancelar")
			}
			if pub != nil {
				if _, err := tx.Exec(ctx, "UPDATE publicaciones SET existencias = existencias + $1 WHERE id = $2 AND existencias IS NOT NULL", cantidad, *pub); err != nil {
					return err
				}
			}
			if _, err := tx.Exec(ctx, "UPDATE ordenes SET estado = 'cancelada', actualizada = now() WHERE id = $1", oid); err != nil {
				return err
			}
			if err := evento(ctx, tx, oid, "cancelada", por, ""); err != nil {
				return err
			}
		case "entregar":
			if por != "vendedor" {
				return plataforma.FalloClave(403, "prohibido", "Solo quien vende marca la entrega")
			}
			if estado != "pagada" {
				return plataforma.FalloClave(409, "estado", "Primero cobra la orden")
			}
			if _, err := tx.Exec(ctx, "UPDATE ordenes SET estado = 'entregada', actualizada = now() WHERE id = $1", oid); err != nil {
				return err
			}
			if err := evento(ctx, tx, oid, "entregada", por, ""); err != nil {
				return err
			}
		}
		o, err = s.ordenCompleta(ctx, tx, oid, id.Cuenta)
		return err
	})
	plataforma.Manejar(w, r, o, err)
}

// ---- interno: lo que necesita el servicio de pagos ----

type OrdenInterna struct {
	ID          int64  `json:"id"`
	CompradorID int64  `json:"comprador_id"`
	VendedorID  int64  `json:"vendedor_id"`
	Titulo      string `json:"titulo"`
	TotalC      int64  `json:"total_c"`
	Estado      string `json:"estado"`
}

func (s *Servicio) internoOrden(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	oid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var o OrdenInterna
	err = s.enTx(ctx, 0, "pagos", func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, "SELECT id, comprador_id, vendedor_id, titulo, total_c, estado FROM ordenes WHERE id = $1", oid).
			Scan(&o.ID, &o.CompradorID, &o.VendedorID, &o.Titulo, &o.TotalC, &o.Estado)
	})
	plataforma.Manejar(w, r, o, err)
}

// internoCobro: pagos avisa cómo va el cobro. Las transiciones válidas son
// pendiente → por_cobrar → pagada, o de regreso a pendiente si se rechaza.
func (s *Servicio) internoCobro(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	oid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var d struct {
		Estado  string `json:"estado"` // enviado | aprobado | rechazado | cancelado
		CobroID string `json:"cobro_id"`
		Nota    string `json:"nota"`
	}
	if err := plataforma.LeerJSON(w, r, &d); err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	if d.CobroID == "" || len(d.CobroID) > 64 {
		plataforma.Manejar(w, r, nil, plataforma.Fallo(400, "Cobro inválido"))
		return
	}
	err = s.enTx(ctx, 0, "pagos", func(tx pgx.Tx) error {
		var estado string
		var cobro *string
		if err := tx.QueryRow(ctx, "SELECT estado, cobro_id FROM ordenes WHERE id = $1 FOR UPDATE", oid).Scan(&estado, &cobro); err != nil {
			return err
		}
		mismo := cobro != nil && *cobro == d.CobroID
		nota := limpiar(d.Nota)
		if len(nota) > 200 {
			nota = nota[:200]
		}
		var nuevo, sql string
		switch {
		case d.Estado == "enviado" && estado == "pendiente":
			nuevo, sql = "por_cobrar", "UPDATE ordenes SET estado = 'por_cobrar', cobro_id = $2, actualizada = now() WHERE id = $1"
		case d.Estado == "aprobado" && (estado == "por_cobrar" || estado == "pendiente") && (mismo || cobro == nil):
			nuevo, sql = "pagada", "UPDATE ordenes SET estado = 'pagada', cobro_id = $2, actualizada = now() WHERE id = $1"
		case (d.Estado == "rechazado" || d.Estado == "cancelado") && estado == "por_cobrar" && mismo:
			nuevo, sql = "pendiente", "UPDATE ordenes SET estado = 'pendiente', cobro_id = NULL, actualizada = now() WHERE id = $1 AND $2::text IS NOT NULL"
		case d.Estado == "aprobado" && estado == "pagada" && mismo:
			return nil // aviso repetido (idempotente)
		default:
			return plataforma.FalloClave(409, "transicion", "La orden no admite ese cambio ("+estado+" → "+d.Estado+")")
		}
		if _, err := tx.Exec(ctx, sql, oid, d.CobroID); err != nil {
			return err
		}
		if nuevo == "pendiente" {
			if nota == "" {
				nota = "El cobro no se completó (" + d.Estado + ")"
			}
		}
		return evento(ctx, tx, oid, nuevo, "pagos", strings.TrimSpace(nota))
	})
	plataforma.Manejar(w, r, nil, err)
}

func (s *Servicio) internoPublicacion(w http.ResponseWriter, r *http.Request) {
	pid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	var out struct {
		ID         int64  `json:"id"`
		VendedorID int64  `json:"vendedor_id"`
		Titulo     string `json:"titulo"`
		PrecioC    int64  `json:"precio_c"`
		Unidad     string `json:"unidad"`
		Estado     string `json:"estado"`
	}
	err = s.bd.QueryRow(r.Context(), "SELECT id, vendedor_id, titulo, precio_c, unidad, estado FROM publicaciones WHERE id = $1", pid).
		Scan(&out.ID, &out.VendedorID, &out.Titulo, &out.PrecioC, &out.Unidad, &out.Estado)
	plataforma.Manejar(w, r, out, err)
}

func (s *Servicio) internoPublicacionesDe(w http.ResponseWriter, r *http.Request) {
	vid, err := plataforma.IDRuta(r, "id")
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	type pub struct {
		ID      int64  `json:"id"`
		Titulo  string `json:"titulo"`
		PrecioC int64  `json:"precio_c"`
		Unidad  string `json:"unidad"`
	}
	filas, err := s.bd.Query(r.Context(), "SELECT id, titulo, precio_c, unidad FROM publicaciones WHERE vendedor_id = $1 ORDER BY id LIMIT 500", vid)
	if err != nil {
		plataforma.Manejar(w, r, nil, err)
		return
	}
	lista, err := pgx.CollectRows(filas, func(f pgx.CollectableRow) (pub, error) {
		var p pub
		return p, f.Scan(&p.ID, &p.Titulo, &p.PrecioC, &p.Unidad)
	})
	plataforma.Manejar(w, r, lista, err)
}
