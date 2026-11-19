package pagos

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Estados normalizados de un cobro, iguales para todas las marcas.
const (
	EstadoEnviado   = "enviado"
	EstadoAprobado  = "aprobado"
	EstadoRechazado = "rechazado"
	EstadoCancelado = "cancelado"
)

// Solicitud de cobro que se manda a una terminal física.
type Solicitud struct {
	CobroID     string // también es la llave de idempotencia
	Dispositivo string
	MontoC      int64
	Descripcion string
}

// Consulta es lo que la marca dice de una transacción (fuente: su API, nunca
// el cuerpo del webhook).
type Consulta struct {
	Estado      string // enviado | aprobado | rechazado | cancelado
	Detalle     string
	MontoC      int64
	Dispositivo string // terminal física donde ocurrió
	Referencia  string // external_reference: nuestro id de cobro, o "" si se cobró directo en la terminal
	PagadoEn    time.Time
}

// Terminal es lo que debe saber hacer cada marca. Agregar otra (Getnet,
// Square, BBVA…) es escribir un archivo que cumpla esta interfaz.
type Terminal interface {
	Proveedor() string
	Cobrar(ctx context.Context, s Solicitud) (ref string, err error)
	Consultar(ctx context.Context, ref string) (Consulta, error)
	Cancelar(ctx context.Context, ref, dispositivo string) error
	// Webhook valida la firma del aviso y regresa la referencia a consultar.
	// Nunca se confía en el estado que trae el aviso: siempre se consulta.
	Webhook(r *http.Request, cuerpo []byte) (ref, eventoID string, err error)
}

var ErrFirma = errors.New("firma de webhook inválida")

func pesos(c int64) string { return fmt.Sprintf("%d.%02d", c/100, c%100) }

func llamarJSON(ctx context.Context, cli *http.Client, metodo, url string, cab map[string]string, entrada, salida any) error {
	var cuerpo io.Reader
	if entrada != nil {
		b, err := json.Marshal(entrada)
		if err != nil {
			return err
		}
		cuerpo = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, metodo, url, cuerpo)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range cab {
		req.Header.Set(k, v)
	}
	res, err := cli.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	datos, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if res.StatusCode >= 300 {
		// No se registra el cuerpo completo: puede traer datos del cliente.
		var e struct {
			Error   string `json:"error"`
			Message string `json:"message"`
		}
		_ = json.Unmarshal(datos, &e)
		return fmt.Errorf("%s respondió %d %s", url[:strings.Index(url+"?", "?")], res.StatusCode, strings.TrimSpace(e.Error+" "+e.Message))
	}
	if salida != nil {
		return json.Unmarshal(datos, salida)
	}
	return nil
}

// ---- Mercado Pago Point (Orders API) ----
// Referencia: https://www.mercadopago.com.mx/developers/es/reference/in-person-payments/point/orders/create-order/post
// La terminal debe estar en modo PDV y ligada a la cuenta del token.

type MercadoPago struct {
	Token, SecretoWebhook, Base string
	HTTP                        *http.Client
}

func (m *MercadoPago) Proveedor() string { return "mercadopago" }

func (m *MercadoPago) cab(idem string) map[string]string {
	c := map[string]string{"Authorization": "Bearer " + m.Token}
	if idem != "" {
		c["X-Idempotency-Key"] = idem
	}
	return c
}

func (m *MercadoPago) Cobrar(ctx context.Context, s Solicitud) (string, error) {
	var out struct {
		ID string `json:"id"`
	}
	err := llamarJSON(ctx, m.HTTP, http.MethodPost, m.Base+"/v1/orders", m.cab(s.CobroID), map[string]any{
		"type":               "point",
		"external_reference": s.CobroID,
		"description":        s.Descripcion,
		"expiration_time":    "PT10M",
		"transactions":       map[string]any{"payments": []map[string]any{{"amount": pesos(s.MontoC)}}},
		"config":             map[string]any{"point": map[string]any{"terminal_id": s.Dispositivo, "print_on_terminal": "no_ticket"}},
	}, &out)
	if err == nil && out.ID == "" {
		err = errors.New("Mercado Pago no regresó el id de la orden")
	}
	return out.ID, err
}

func (m *MercadoPago) Consultar(ctx context.Context, ref string) (Consulta, error) {
	var out struct {
		Status            string `json:"status"`
		StatusDetail      string `json:"status_detail"`
		ExternalReference string `json:"external_reference"`
		LastUpdated       string `json:"last_updated_date"`
		Config            struct {
			Point struct {
				TerminalID string `json:"terminal_id"`
			} `json:"point"`
		} `json:"config"`
		Transactions struct {
			Payments []struct {
				Amount string `json:"amount"`
			} `json:"payments"`
		} `json:"transactions"`
	}
	if err := llamarJSON(ctx, m.HTTP, http.MethodGet, m.Base+"/v1/orders/"+urlSegura(ref), m.cab(""), nil, &out); err != nil {
		return Consulta{}, err
	}
	c := Consulta{Detalle: out.StatusDetail, Dispositivo: out.Config.Point.TerminalID, Referencia: out.ExternalReference, PagadoEn: time.Now()}
	if t, err := time.Parse(time.RFC3339, out.LastUpdated); err == nil {
		c.PagadoEn = t
	}
	if len(out.Transactions.Payments) > 0 {
		c.MontoC, _ = centavos(out.Transactions.Payments[0].Amount)
	}
	switch out.Status {
	case "processed":
		c.Estado = EstadoAprobado
	case "canceled", "expired":
		c.Estado = EstadoCancelado
	case "failed", "refunded", "charged_back":
		c.Estado = EstadoRechazado
	default:
		c.Estado = EstadoEnviado
	}
	return c, nil
}

func (m *MercadoPago) Cancelar(ctx context.Context, ref, _ string) error {
	return llamarJSON(ctx, m.HTTP, http.MethodPost, m.Base+"/v1/orders/"+urlSegura(ref)+"/cancel", m.cab("cancel-"+ref), nil, nil)
}

// Webhook verifica x-signature (ver verificarFirma).
func (m *MercadoPago) Webhook(r *http.Request, cuerpo []byte) (string, string, error) {
	return verificarFirma(m.SecretoWebhook, r, cuerpo)
}

// verificarFirma implementa el esquema de Mercado Pago (lo reutiliza la
// terminal virtual): cabecera x-signature "ts=<t>,v1=<hmac>" con
// hmac = HMAC-SHA256(secreto, "id:<data.id>;request-id:<x-request-id>;ts:<t>;").
// Se rechazan avisos de más de 10 minutos (repetición).
func verificarFirma(secreto string, r *http.Request, cuerpo []byte) (string, string, error) {
	if secreto == "" {
		return "", "", ErrFirma
	}
	var ts, v1 string
	for _, p := range strings.Split(r.Header.Get("x-signature"), ",") {
		k, v, _ := strings.Cut(strings.TrimSpace(p), "=")
		switch k {
		case "ts":
			ts = v
		case "v1":
			v1 = v
		}
	}
	reqID := r.Header.Get("x-request-id")
	dataID := r.URL.Query().Get("data.id")
	if dataID == "" {
		var b struct {
			Data struct {
				ID string `json:"id"`
			} `json:"data"`
		}
		_ = json.Unmarshal(cuerpo, &b)
		dataID = b.Data.ID
	}
	if ts == "" || v1 == "" || dataID == "" || reqID == "" || len(dataID) > 64 || len(reqID) > 128 {
		return "", "", ErrFirma
	}
	t, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return "", "", ErrFirma
	}
	if t > 1e12 {
		t /= 1000
	}
	if d := time.Since(time.Unix(t, 0)); d > 10*time.Minute || d < -5*time.Minute {
		return "", "", ErrFirma
	}
	if !hmac.Equal([]byte(firmaHMAC(secreto, dataID, reqID, ts)), []byte(strings.ToLower(v1))) {
		return "", "", ErrFirma
	}
	return dataID, reqID, nil
}

func firmaHMAC(secreto, dataID, reqID, ts string) string {
	mac := hmac.New(sha256.New, []byte(secreto))
	fmt.Fprintf(mac, "id:%s;request-id:%s;ts:%s;", strings.ToLower(dataID), reqID, ts)
	return hex.EncodeToString(mac.Sum(nil))
}

// centavos convierte "123.45" a 12345 sin pasar por flotantes.
func centavos(s string) (int64, error) {
	ent, dec, _ := strings.Cut(strings.TrimSpace(s), ".")
	if len(dec) > 2 || ent == "" {
		return 0, errors.New("monto inválido")
	}
	for len(dec) < 2 {
		dec += "0"
	}
	e, err1 := strconv.ParseInt(ent, 10, 64)
	d, err2 := strconv.ParseInt(dec, 10, 64)
	if err1 != nil || err2 != nil || e < 0 || e > 1e10 {
		return 0, errors.New("monto inválido")
	}
	return e*100 + d, nil
}

// ---- Clip PinPad API ----
// IMPORTANTE: los nombres de campos siguen la especificación pública de
// terceros de /f2f/pinpad/v1; deben confirmarse en developer.clip.mx con las
// credenciales del comercio antes de producción. Clip no tiene aquí webhook
// firmado: el estado se obtiene consultando (el conciliador lo hace solo).

type Clip struct {
	Llave, Secreto, Base string
	HTTP                 *http.Client
}

func (c *Clip) Proveedor() string { return "clip" }

func (c *Clip) cab() map[string]string {
	return map[string]string{"Authorization": "Basic " + base64.StdEncoding.EncodeToString([]byte(c.Llave+":"+c.Secreto))}
}

func (c *Clip) Cobrar(ctx context.Context, s Solicitud) (string, error) {
	var out struct {
		PinpadRequestID string `json:"pinpad_request_id"`
	}
	monto, _ := strconv.ParseFloat(pesos(s.MontoC), 64)
	err := llamarJSON(ctx, c.HTTP, http.MethodPost, c.Base+"/f2f/pinpad/v1/payment", c.cab(), map[string]any{
		"amount": monto, "reference": s.CobroID, "serial_number_pos": s.Dispositivo,
	}, &out)
	if err == nil && out.PinpadRequestID == "" {
		err = errors.New("Clip no regresó el id del cobro")
	}
	return out.PinpadRequestID, err
}

func (c *Clip) Consultar(ctx context.Context, ref string) (Consulta, error) {
	var out struct {
		Status    string  `json:"status"`
		Amount    float64 `json:"amount"`
		Reference string  `json:"reference"`
		Serial    string  `json:"serial_number_pos"`
	}
	if err := llamarJSON(ctx, c.HTTP, http.MethodGet, c.Base+"/f2f/pinpad/v1/payment?pinpad_request_id="+urlSegura(ref), c.cab(), nil, &out); err != nil {
		return Consulta{}, err
	}
	monto, _ := centavos(strconv.FormatFloat(out.Amount, 'f', 2, 64))
	k := Consulta{Detalle: out.Status, MontoC: monto, Referencia: out.Reference, Dispositivo: out.Serial, PagadoEn: time.Now()}
	switch strings.ToUpper(out.Status) {
	case "APPROVED", "COMPLETED", "PAID":
		k.Estado = EstadoAprobado
	case "CANCELED", "CANCELLED", "EXPIRED":
		k.Estado = EstadoCancelado
	case "DECLINED", "REJECTED", "FAILED", "ERROR":
		k.Estado = EstadoRechazado
	default:
		k.Estado = EstadoEnviado
	}
	return k, nil
}

func (c *Clip) Cancelar(ctx context.Context, ref, _ string) error {
	return llamarJSON(ctx, c.HTTP, http.MethodDelete, c.Base+"/f2f/pinpad/v1/payment?pinpad_request_id="+urlSegura(ref), c.cab(), nil, nil)
}

func (c *Clip) Webhook(*http.Request, []byte) (string, string, error) { return "", "", ErrFirma }

// urlSegura deja solo caracteres de id (evita inyectar rutas en la URL externa).
func urlSegura(s string) string {
	var b strings.Builder
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			b.WriteRune(r)
		}
	}
	return b.String()
}
