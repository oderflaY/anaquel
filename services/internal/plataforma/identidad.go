package plataforma

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

// Identidad viaja de la pasarela a los servicios en la cabecera
// X-Anaquel-Identidad, firmada con HMAC-SHA256 y con vida de un minuto.
// La pasarela borra cualquier cabecera X-Anaquel-* que llegue de fuera, y los
// servicios rechazan las que no estén firmadas: aunque alguien alcance un
// servicio por la red interna, no puede hacerse pasar por otra cuenta.
type Identidad struct {
	Cuenta int64  `json:"c,omitempty"`
	Rol    string `json:"r"`           // cliente | admin | servicio
	Estado string `json:"e,omitempty"` // pendiente | verificada | suspendida
	Vende  bool   `json:"v,omitempty"`
	Nombre string `json:"n,omitempty"`
	Slug   string `json:"s,omitempty"`
	IP     string `json:"ip,omitempty"` // IP real del visitante, la decide la pasarela
	Expira int64  `json:"x"`
}

// Leer regresa la identidad firmada de r si existe y es válida (sin exigirla).
// Las rutas públicas la usan para conocer la IP real del visitante.
func (f *Firmante) Leer(r *http.Request) (Identidad, bool) {
	v := r.Header.Get(CabeceraIdentidad)
	if v == "" {
		return Identidad{}, false
	}
	id, err := f.Verificar(v)
	return id, err == nil
}

const CabeceraIdentidad = "X-Anaquel-Identidad"

const (
	RolCliente   = "cliente"
	RolAdmin     = "admin"
	RolServicio  = "servicio"
	RolVisitante = "visitante" // sin sesión; la pasarela la firma solo para pasar la IP

	EstadoPendiente  = "pendiente"
	EstadoVerificada = "verificada"
	EstadoSuspendida = "suspendida"
)

// Verificada: cuenta que ya puede usar el mercado.
func (i Identidad) Verificada() bool {
	return (i.Rol == RolCliente || i.Rol == RolAdmin) && i.Estado == EstadoVerificada
}

// Firmante firma y verifica identidades con la clave interna compartida.
type Firmante struct{ clave []byte }

func NuevoFirmante(clave []byte) (*Firmante, error) {
	if len(clave) < 32 {
		return nil, errors.New("CLAVE_INTERNA debe tener al menos 32 bytes")
	}
	return &Firmante{clave: clave}, nil
}

var b64 = base64.RawURLEncoding

func (f *Firmante) mac(datos string) string {
	m := hmac.New(sha256.New, f.clave)
	m.Write([]byte("anaquel-identidad-v1."))
	m.Write([]byte(datos))
	return b64.EncodeToString(m.Sum(nil))
}

// Firmar regresa el valor de la cabecera para id (válido 60 s).
func (f *Firmante) Firmar(id Identidad) string {
	id.Expira = time.Now().Add(60 * time.Second).Unix()
	j, _ := json.Marshal(id)
	d := b64.EncodeToString(j)
	return d + "." + f.mac(d)
}

// Verificar valida firma y vigencia.
func (f *Firmante) Verificar(valor string) (Identidad, error) {
	var id Identidad
	d, firma, ok := strings.Cut(valor, ".")
	if !ok || d == "" {
		return id, errors.New("identidad mal formada")
	}
	if !hmac.Equal([]byte(firma), []byte(f.mac(d))) {
		return id, errors.New("firma inválida")
	}
	j, err := b64.DecodeString(d)
	if err != nil {
		return id, err
	}
	if err := json.Unmarshal(j, &id); err != nil {
		return id, err
	}
	if time.Now().Unix() > id.Expira {
		return id, errors.New("identidad vencida")
	}
	return id, nil
}

type ctxIdentidad struct{}

// IdentidadDe regresa la identidad verificada de la petición.
func IdentidadDe(r *http.Request) Identidad {
	id, _ := r.Context().Value(ctxIdentidad{}).(Identidad)
	return id
}

// ConIdentidad pone id en el contexto (útil en pruebas).
func ConIdentidad(ctx context.Context, id Identidad) context.Context {
	return context.WithValue(ctx, ctxIdentidad{}, id)
}

// Regla decide si una identidad puede usar una ruta.
type Regla func(Identidad) error

// Cliente verificado (comprador; cualquier negocio verificado compra).
func ClienteVerificado(i Identidad) error {
	if i.Rol == RolServicio {
		return FalloClave(http.StatusForbidden, "prohibido", "No tienes permiso para esto")
	}
	if i.Cuenta <= 0 || (i.Rol != RolCliente && i.Rol != RolAdmin) {
		return FalloClave(http.StatusUnauthorized, "sin_sesion", "Inicia sesión para continuar")
	}
	if i.Estado == EstadoSuspendida {
		return FalloClave(http.StatusForbidden, "suspendida", "Tu cuenta está suspendida")
	}
	if !i.Verificada() {
		return FalloClave(http.StatusForbidden, "en_revision", "Tu cuenta está en revisión")
	}
	return nil
}

// Vendedor verificado (puede publicar y cobrar).
func Vendedor(i Identidad) error {
	if err := ClienteVerificado(i); err != nil {
		return err
	}
	if !i.Vende {
		return FalloClave(http.StatusForbidden, "no_vende", "Tu cuenta no tiene permiso para vender")
	}
	return nil
}

// Admin verificado.
func Admin(i Identidad) error {
	if i.Cuenta <= 0 || i.Rol != RolAdmin || i.Estado != EstadoVerificada {
		return FalloClave(http.StatusForbidden, "prohibido", "No tienes permiso para esto")
	}
	return nil
}

// Servicio interno con nombre permitido (Identidad.Nombre).
func Servicio(nombres ...string) Regla {
	return func(i Identidad) error {
		if i.Rol == RolServicio {
			for _, n := range nombres {
				if i.Nombre == n {
					return nil
				}
			}
		}
		return FalloClave(http.StatusForbidden, "prohibido", "No tienes permiso para esto")
	}
}

// Exigir envuelve h: solo pasa si la cabecera está firmada y la regla acepta.
func (f *Firmante) Exigir(regla Regla, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		v := r.Header.Get(CabeceraIdentidad)
		if v == "" {
			Responder(w, http.StatusUnauthorized, ErrHTTP{Msg: "Inicia sesión para continuar", Clave: "sin_sesion"})
			return
		}
		id, err := f.Verificar(v)
		if err != nil {
			Responder(w, http.StatusUnauthorized, ErrHTTP{Msg: "Inicia sesión para continuar", Clave: "sin_sesion"})
			return
		}
		if err := regla(id); err != nil {
			Manejar(w, r, nil, err)
			return
		}
		h(w, r.WithContext(ConIdentidad(r.Context(), id)))
	}
}
