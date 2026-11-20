package mercado

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
)

// Catálogo de demostración: lo que venderían de verdad los negocios demo de
// Durango (mismo correo que en el panel de Anaquel).
type pubDemo struct {
	email, titulo, desc, categoria, unidad string
	precioC                                int64
	existencias                            *int
	minimo                                 int
}

func n(x int) *int { return &x }

var catalogoDemo = []pubDemo{
	{"mayoreo@anaquel.mx", "Gorra bordada con tu logo", "Gorra de gabardina con bordado de hasta 8 colores. Ideal para eventos, escuelas y negocios.", "mayoreo", "pieza", 6500, n(1200), 50},
	{"mayoreo@anaquel.mx", "Gorra trucker lisa", "Frente de espuma y malla trasera. Varios colores.", "mayoreo", "pieza", 4800, n(600), 20},
	{"mayoreo@anaquel.mx", "Playera polo para uniforme", "Tela piqué, tallas CH a XXL. Se puede bordar.", "ropa", "pieza", 13500, n(300), 12},
	{"textiles@anaquel.mx", "Chamarra acolchada de invierno", "Forro térmico, capucha desmontable. Tallas surtidas.", "ropa", "pieza", 42000, n(80), 6},
	{"textiles@anaquel.mx", "Sudadera con capucha", "Algodón perchado. Colores lisos.", "ropa", "pieza", 26000, n(150), 6},
	{"textiles@anaquel.mx", "Bufanda tejida", "Tejido grueso, colores de temporada.", "ropa", "pieza", 8500, n(4), 12},
	{"abarrotes.mayoreo@anaquel.mx", "Frijol pinto (bulto de 25 kg)", "Frijol pinto saltillo de temporada, limpio.", "abarrotes", "bulto", 78000, n(40), 1},
	{"abarrotes.mayoreo@anaquel.mx", "Aceite vegetal (caja de 12 litros)", "Caja con 12 botellas de 1 litro.", "abarrotes", "caja", 52000, n(60), 1},
	{"abarrotes.mayoreo@anaquel.mx", "Azúcar estándar (bulto de 50 kg)", "Precio de mayoreo para tienditas.", "abarrotes", "bulto", 115000, n(25), 1},
	{"abarrotes.mayoreo@anaquel.mx", "Refresco de 600 ml (paquete de 24)", "Sabores surtidos.", "abarrotes", "paquete", 31000, n(0), 1},
	{"demo@anaquel.mx", "Queso menonita", "Queso de Nuevo Ideal, por kilo.", "hecho-en-durango", "kilo", 16500, n(25), 1},
	{"demo@anaquel.mx", "Tortilla de maíz de nixtamal", "Hechas el mismo día por la Tortillería El Nixtamal.", "hecho-en-durango", "kilo", 2400, nil, 1},
	{"demo@anaquel.mx", "Huevo de rancho", "De la Granja Los Álamos, Valle del Sur.", "hecho-en-durango", "docena", 4800, n(30), 1},
	{"demo@anaquel.mx", "Miel del Mezquital (500 g)", "Miel cruda de mezquite, frasco de vidrio.", "hecho-en-durango", "pieza", 12000, n(18), 1},
	{"ropa@anaquel.mx", "Vestido casual de temporada", "Tallas CH, M y G.", "ropa", "pieza", 39000, n(15), 1},
	{"ropa@anaquel.mx", "Chamarra de mezclilla", "Corte clásico, forro de borrega.", "ropa", "pieza", 52000, n(8), 1},
	{"papeleria@anaquel.mx", "Paquete escolar básico", "Cuadernos, lápices, colores y regla.", "papeleria", "paquete", 14500, n(60), 1},
	{"papeleria@anaquel.mx", "Impresión a color", "Por hoja tamaño carta.", "papeleria", "pieza", 500, nil, 1},
	{"fletes@anaquel.mx", "Flete local en camioneta de 3.5 t", "Dentro de la ciudad de Durango. Incluye chofer.", "servicios", "servicio", 65000, nil, 1},
	{"fletes@anaquel.mx", "Mudanza pequeña con 2 ayudantes", "Hasta 4 horas.", "servicios", "servicio", 150000, nil, 1},
	{"imprenta@anaquel.mx", "Bordado de logo en gorra", "Precio por pieza, desde 50 piezas.", "servicios", "pieza", 3500, nil, 50},
	{"imprenta@anaquel.mx", "Tarjetas de presentación (millar)", "Papel couché, a color por ambos lados.", "papeleria", "paquete", 45000, nil, 1},
}

// SembrarDemo espera a que "acceso" tenga las cuentas demo, copia sus perfiles
// y, si el catálogo está vacío, lo llena. Corre en segundo plano.
func (s *Servicio) SembrarDemo(ctx context.Context) {
	var perfiles []perfilAcceso
	for intento := 0; ; intento++ {
		err := s.acceso.Llamar(ctx, http.MethodGet, "/interno/demo", nil, &perfiles)
		if err == nil && len(perfiles) > 0 {
			break
		}
		if intento > 60 {
			slog.Error("demo: acceso no respondió", "err", err)
			return
		}
		time.Sleep(2 * time.Second)
	}
	ids := map[string]int64{}
	for _, p := range perfiles {
		if err := s.guardarPerfil(ctx, p); err != nil {
			slog.Error("demo: perfil", "err", err)
			return
		}
		ids[p.Email] = p.ID
	}
	var hay bool
	_ = s.bd.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM publicaciones)").Scan(&hay)
	if hay {
		return
	}
	err := s.enTx(ctx, 0, "sistema", func(tx pgx.Tx) error {
		pubs := map[string]int64{}
		for _, p := range catalogoDemo {
			var id int64
			if err := tx.QueryRow(ctx, `INSERT INTO publicaciones (vendedor_id, titulo, descripcion, categoria, precio_c, unidad, existencias, minimo)
				VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING id`, ids[p.email], p.titulo, p.desc, p.categoria, p.precioC,
				p.unidad, p.existencias, p.minimo).Scan(&id); err != nil {
				return err
			}
			pubs[p.titulo] = id
		}
		// Órdenes de ejemplo en distintos estados.
		ejemplos := []struct {
			comprador, titulo string
			cantidad          int
			estados           []string
		}{
			{"papeleria@anaquel.mx", "Gorra bordada con tu logo", 100, []string{"pendiente"}},
			{"ropa@anaquel.mx", "Chamarra acolchada de invierno", 12, []string{"pendiente", "por_cobrar", "pagada"}},
			{"demo@anaquel.mx", "Aceite vegetal (caja de 12 litros)", 2, []string{"pendiente", "por_cobrar", "pagada", "entregada"}},
		}
		for _, e := range ejemplos {
			var oid int64
			var precio, vendedor int64
			var unidad string
			if err := tx.QueryRow(ctx, "SELECT precio_c, vendedor_id, unidad FROM publicaciones WHERE id = $1", pubs[e.titulo]).
				Scan(&precio, &vendedor, &unidad); err != nil {
				return err
			}
			final := e.estados[len(e.estados)-1]
			if err := tx.QueryRow(ctx, `INSERT INTO ordenes (comprador_id, vendedor_id, publicacion_id, titulo, unidad, cantidad, precio_c, total_c, estado, cobro_id)
				VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, CASE WHEN $9 IN ('pagada', 'entregada') THEN 'demo' END) RETURNING id`,
				ids[e.comprador], vendedor, pubs[e.titulo], e.titulo, unidad, e.cantidad, precio, int64(e.cantidad)*precio, final).Scan(&oid); err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, "UPDATE publicaciones SET existencias = existencias - $1 WHERE id = $2 AND existencias IS NOT NULL",
				e.cantidad, pubs[e.titulo]); err != nil {
				return err
			}
			for _, st := range e.estados {
				por := map[string]string{"pendiente": "comprador", "por_cobrar": "pagos", "pagada": "pagos", "entregada": "vendedor"}[st]
				if err := evento(ctx, tx, oid, st, por, ""); err != nil {
					return err
				}
			}
		}
		return nil
	})
	if err != nil {
		slog.Error("demo: catálogo", "err", err)
		return
	}
	slog.Info("demo: catálogo del mercado creado", "publicaciones", len(catalogoDemo))
}
