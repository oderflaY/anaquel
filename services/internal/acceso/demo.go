package acceso

import (
	"context"
	"log/slog"
)

// Cuentas de demostración: las mismas tiendas del panel de Anaquel (mismo
// correo, nombre y slug), para que el mercado se conecte con su vista pública
// y su bodega 3D. Solo existen con DEMO=1; la contraseña demo1234 no cumple la
// política a propósito y por eso nunca se acepta en registros reales.
const PassDemo = "demo1234"

type cuentaDemo struct {
	Email, Nombre, Slug, Giro, Colonia, Desc, Tel, Que string
	Lat, Lng                                           float64
	Rol, Estado                                        string
	Vende                                              bool
}

var demos = []cuentaDemo{
	{Email: "demo@anaquel.mx", Nombre: "Abarrotes Doña Lupita", Slug: "dona-lupita", Giro: "abarrotes", Colonia: "Zona Centro",
		Desc: "Tiendita de la esquina desde 1998.", Tel: "6181234567", Lat: 24.0277, Lng: -104.6532, Vende: true,
		Que: "Tiendita: compra a mayoristas y vende lo suyo"},
	{Email: "ropa@anaquel.mx", Nombre: "Boutique Alondra", Slug: "boutique-alondra", Giro: "ropa", Colonia: "Zona Centro",
		Desc: "Ropa de dama y caballero.", Tel: "6182345678", Lat: 24.0236, Lng: -104.6612, Vende: true,
		Que: "Ropa: busca chamarras para el frío"},
	{Email: "mayoreo@anaquel.mx", Nombre: "Gorras El Tunal", Slug: "gorras-el-tunal", Giro: "mayoreo", Colonia: "El Tunal",
		Desc: "Gorras y uniformes por mayoreo. 1,200 gorras listas.", Tel: "6183456789", Lat: 24.0471, Lng: -104.6176, Vende: true,
		Que: "Mayoreo: 1,200 gorras a la venta"},
	{Email: "textiles@anaquel.mx", Nombre: "Textiles Guadiana", Slug: "textiles-guadiana", Giro: "mayoreo", Colonia: "Zona Centro",
		Desc: "Mayorista de ropa de invierno.", Tel: "6184567890", Lat: 24.0262, Lng: -104.6701, Vende: true,
		Que: "Mayorista de ropa de invierno"},
	{Email: "papeleria@anaquel.mx", Nombre: "Papelería El Estudiante", Slug: "papeleria-el-estudiante", Giro: "papeleria",
		Colonia: "Nueva Vizcaya", Desc: "Papelería y regalos.", Tel: "6185678901", Lat: 24.0333, Lng: -104.6488, Vende: true,
		Que: "Papelería: busca 1,000 gorras"},
	{Email: "abarrotes.mayoreo@anaquel.mx", Nombre: "Abarrotes Mayoreo Guadiana", Slug: "abarrotes-mayoreo-guadiana",
		Giro: "mayoreo", Colonia: "Carretera a Mazatlán", Desc: "Abarrotes por caja y bulto.", Tel: "6186789012",
		Lat: 24.0158, Lng: -104.6899, Vende: true, Que: "Abarrotes por caja y bulto"},
	{Email: "fletes@anaquel.mx", Nombre: "Fletes Durango Express", Slug: "fletes-durango-express", Giro: "servicios",
		Colonia: "Valle del Sur", Desc: "Fletes y mudanzas en la ciudad.", Tel: "6187890123", Lat: 24.0005, Lng: -104.6455,
		Vende: true, Que: "Servicio de fletes"},
	{Email: "imprenta@anaquel.mx", Nombre: "Imprenta Rápida Guadiana", Slug: "imprenta-rapida-guadiana", Giro: "servicios",
		Colonia: "Zona Centro", Desc: "Impresión y bordado de logos.", Tel: "6188901234", Lat: 24.0244, Lng: -104.6633,
		Vende: true, Que: "Imprenta y bordado"},
	{Email: "revision@anaquel.mx", Nombre: "Cremería La Nueva", Slug: "cremeria-la-nueva", Giro: "comida", Colonia: "Jardines de Durango",
		Desc: "Cuenta recién registrada.", Tel: "6189012345", Lat: 24.0398, Lng: -104.6801, Estado: "pendiente",
		Que: "Cuenta en revisión (para ver el protocolo)"},
	{Email: "admin@anaquel.mx", Nombre: "Administración Anaquel", Slug: "admin-anaquel", Giro: "otro", Colonia: "Zona Centro",
		Tel: "", Lat: 24.0277, Lng: -104.6532, Rol: "admin", Que: "Administra y verifica cuentas"},
}

type DemoPublica struct {
	Email  string `json:"email"`
	Nombre string `json:"nombre"`
	Que    string `json:"que"`
}

func cuentasDemoPublicas() []DemoPublica {
	out := make([]DemoPublica, len(demos))
	for i, d := range demos {
		out[i] = DemoPublica{Email: d.Email, Nombre: d.Nombre, Que: d.Que}
	}
	return out
}

// SembrarDemo crea las cuentas que falten (no toca las que ya existen).
func (s *Servicio) SembrarDemo(ctx context.Context) error {
	hash, err := hashArgon(PassDemo)
	if err != nil {
		return err
	}
	for _, d := range demos {
		emailCif, err := s.cif.Cifrar("cuentas.email", d.Email)
		if err != nil {
			return err
		}
		var telCif []byte
		if d.Tel != "" {
			if telCif, err = s.cif.Cifrar("cuentas.telefono", d.Tel); err != nil {
				return err
			}
		}
		rol, estado := d.Rol, d.Estado
		if rol == "" {
			rol = "cliente"
		}
		if estado == "" {
			estado = "verificada"
		}
		var tienda *string
		if rol == "cliente" && estado == "verificada" {
			t := d.Slug
			tienda = &t
		}
		tag, err := s.bd.Exec(ctx, `INSERT INTO cuentas (email_idx, email_cifrado, pass_hash, nombre, slug, giro, descripcion,
				telefono_cifrado, colonia, lat, lng, rol, estado, vende, demo, tienda_slug)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, true, $15)
			ON CONFLICT (email_idx) DO NOTHING`,
			s.idx.De("cuentas.email", d.Email), emailCif, hash, d.Nombre, d.Slug, d.Giro, d.Desc, telCif, d.Colonia,
			d.Lat, d.Lng, rol, estado, d.Vende, tienda)
		if err != nil {
			return err
		}
		if tag.RowsAffected() > 0 {
			slog.Info("cuenta demo creada", "nombre", d.Nombre)
		}
	}
	return nil
}
