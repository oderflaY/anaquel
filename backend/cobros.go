package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// Cobros: quien compra paga en línea lo que se acordó en el chat (una oferta
// aceptada o un pedido). Con STRIPE_SECRET_KEY se cobra con Stripe Checkout
// (tarjeta, OXXO… lo que tenga activo la cuenta); sin llave hay una página de
// pago de prueba para la demo, que no mueve dinero. En los dos casos, al
// confirmarse el pago los tratos quedan pagados, se avisa a quien vende y el
// chat lo muestra.
//
// Las rutas viven en /api/cobros (/api/pagos es del servicio de terminales).

type Cobro struct {
	ID     int64  `json:"id"`
	URL    string `json:"url"`
	Modo   string `json:"modo"` // stripe | prueba
	MontoC int64  `json:"monto_c"`
	Estado string `json:"estado"` // pendiente | pagado | cancelado
}

type lineaCobro struct {
	trato    int64
	titulo   string
	cantidad int64
	precio   int64
}

// ---------------- Stripe (API REST, sin SDK) ----------------

type stripeCli struct {
	clave, firma string
	http         *http.Client
}

func leerSecreto(nombre string) string {
	v := strings.TrimSpace(os.Getenv(nombre))
	if f := os.Getenv(nombre + "_FILE"); v == "" && f != "" {
		if b, err := os.ReadFile(f); err == nil {
			v = strings.TrimSpace(string(b))
		}
	}
	return v
}

func stripeDesdeEnv() *stripeCli {
	k := leerSecreto("STRIPE_SECRET_KEY")
	if k == "" {
		return nil
	}
	return &stripeCli{clave: k, firma: leerSecreto("STRIPE_WEBHOOK_SECRET"), http: &http.Client{Timeout: 20 * time.Second}}
}

type sesionStripe struct {
	ID            string            `json:"id"`
	URL           string            `json:"url"`
	PaymentStatus string            `json:"payment_status"` // paid | unpaid | no_payment_required
	Status        string            `json:"status"`         // open | complete | expired
	Metadata      map[string]string `json:"metadata"`
	Error         *struct {
		Message string `json:"message"`
	} `json:"error"`
}

func (s *stripeCli) llamar(ctx context.Context, metodo, ruta string, form url.Values, idem string) (sesionStripe, error) {
	var out sesionStripe
	var cuerpo io.Reader
	if form != nil {
		cuerpo = strings.NewReader(form.Encode())
	}
	req, err := http.NewRequestWithContext(ctx, metodo, "https://api.stripe.com/v1"+ruta, cuerpo)
	if err != nil {
		return out, err
	}
	req.SetBasicAuth(s.clave, "")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if idem != "" {
		req.Header.Set("Idempotency-Key", idem)
	}
	r, err := s.http.Do(req)
	if err != nil {
		return out, fmt.Errorf("no se pudo hablar con Stripe: %w", err)
	}
	defer r.Body.Close()
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&out); err != nil {
		return out, fmt.Errorf("respuesta rara de Stripe (%d)", r.StatusCode)
	}
	if r.StatusCode >= 300 {
		msg := "Stripe rechazó el cobro"
		if out.Error != nil && out.Error.Message != "" {
			msg += ": " + out.Error.Message
		}
		return out, fallo(502, msg)
	}
	return out, nil
}

func (s *stripeCli) crearSesion(ctx context.Context, cobro int64, lineas []lineaCobro, exito, cancelar string) (sesionStripe, error) {
	f := url.Values{}
	f.Set("mode", "payment")
	f.Set("locale", "es-419")
	f.Set("success_url", exito)
	f.Set("cancel_url", cancelar)
	f.Set("client_reference_id", strconv.FormatInt(cobro, 10))
	f.Set("metadata[cobro]", strconv.FormatInt(cobro, 10))
	f.Set("payment_intent_data[metadata][cobro]", strconv.FormatInt(cobro, 10))
	for i, l := range lineas {
		p := fmt.Sprintf("line_items[%d]", i)
		f.Set(p+"[quantity]", strconv.FormatInt(l.cantidad, 10))
		f.Set(p+"[price_data][currency]", "mxn")
		f.Set(p+"[price_data][unit_amount]", strconv.FormatInt(l.precio, 10))
		f.Set(p+"[price_data][product_data][name]", recortarTexto(l.titulo, 200))
	}
	return s.llamar(ctx, "POST", "/checkout/sessions", f, fmt.Sprintf("anaquel-cobro-%d", cobro))
}

func (s *stripeCli) verSesion(ctx context.Context, id string) (sesionStripe, error) {
	return s.llamar(ctx, "GET", "/checkout/sessions/"+url.PathEscape(id), nil, "")
}

// firmaValida revisa la cabecera Stripe-Signature (t=…,v1=…) de un webhook.
func firmaValida(secreto, cabecera string, cuerpo []byte, ahora time.Time) bool {
	var ts string
	var firmas []string
	for _, p := range strings.Split(cabecera, ",") {
		k, v, _ := strings.Cut(strings.TrimSpace(p), "=")
		switch k {
		case "t":
			ts = v
		case "v1":
			firmas = append(firmas, v)
		}
	}
	t, err := strconv.ParseInt(ts, 10, 64)
	if err != nil || ahora.Sub(time.Unix(t, 0)).Abs() > 5*time.Minute {
		return false
	}
	m := hmac.New(sha256.New, []byte(secreto))
	m.Write([]byte(ts + "."))
	m.Write(cuerpo)
	esperada := hex.EncodeToString(m.Sum(nil))
	for _, f := range firmas {
		if subtle.ConstantTimeCompare([]byte(f), []byte(esperada)) == 1 {
			return true
		}
	}
	return false
}

// ---------------- Iniciar y confirmar ----------------

// urlPublica: dirección con la que el navegador de quien paga regresa aquí.
func urlPublica(r *http.Request) string {
	if u := strings.TrimRight(os.Getenv("ANAQUEL_URL_PUBLICA"), "/"); u != "" {
		return u
	}
	esquema := "https"
	if r.TLS == nil && r.Header.Get("X-Forwarded-Proto") == "http" {
		esquema = "http"
	}
	host := r.Header.Get("X-Forwarded-Host")
	if host == "" {
		host = r.Host
	}
	return esquema + "://" + host + prefijo
}

func azar() string {
	b := make([]byte, 18)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// porPagar: los tratos de la lista que yo (comprador) puedo pagar ahora: ya
// aceptados, o con una propuesta de la otra tienda (pagar = aceptarla).
func (a *App) porPagar(yo int64, tratos []int64) (lineas []lineaCobro, vendedor int64, err error) {
	esperando := 0
	for _, id := range tratos {
		var l lineaCobro
		var comprador, vend, propuso int64
		var estado string
		var cobro sql.NullInt64
		if a.db.QueryRow("SELECT id, titulo, cantidad, precio_c, comprador_id, vendedor_id, propuso_id, estado, cobro_id FROM tratos WHERE id = ?", id).
			Scan(&l.trato, &l.titulo, &l.cantidad, &l.precio, &comprador, &vend, &propuso, &estado, &cobro) != nil || comprador != yo {
			continue
		}
		if cobro.Valid || l.cantidad <= 0 || l.precio <= 0 {
			continue
		}
		switch {
		case estado == "aceptado", estado == "negociando" && propuso != yo:
			lineas = append(lineas, l)
			vendedor = vend
		case estado == "negociando":
			esperando++
		}
	}
	if len(lineas) == 0 {
		if esperando > 0 {
			return nil, 0, fallo(409, "Primero tiene que aceptar tu propuesta; en cuanto lo haga te sale el botón de pagar.")
		}
		return nil, 0, fallo(409, "No hay nada por pagar aquí")
	}
	return lineas, vendedor, nil
}

// iniciarCobro acepta lo que estaba en negociación (así el precio ya no cambia)
// y prepara el pago: una sesión de Stripe Checkout o la página de prueba.
func (a *App) iniciarCobro(r *http.Request, yo, chat int64, tratos []int64) (*Cobro, error) {
	lineas, vendedor, err := a.porPagar(yo, tratos)
	if err != nil {
		return nil, err
	}
	var total int64
	ids := make([]int64, len(lineas))
	for i, l := range lineas {
		total += l.cantidad * l.precio
		ids[i] = l.trato
	}
	if total < 1000 {
		return nil, fallo(400, "El pago en línea es desde $10. Para menos, págale al recibir.")
	}
	idsJSON, _ := json.Marshal(ids)
	// Si ya había un cobro pendiente por lo mismo (se cerró la ventana), se reusa.
	var c Cobro
	var proveedor, secreto string
	err = a.db.QueryRow(`SELECT id, url, proveedor, monto_c, secreto FROM cobros WHERE comprador_id = ? AND tratos = ? AND estado = 'pendiente'
		AND creado > ? ORDER BY id DESC LIMIT 1`, yo, string(idsJSON), ahora()-50*60*1000).Scan(&c.ID, &c.URL, &proveedor, &c.MontoC, &secreto)
	if err == nil && c.MontoC == total && c.URL != "" {
		c.Modo, c.Estado = proveedor, "pendiente"
		return &c, nil
	}
	for _, l := range lineas { // pagar una propuesta es aceptarla
		var estado string
		a.db.QueryRow("SELECT estado FROM tratos WHERE id = ?", l.trato).Scan(&estado)
		if estado == "negociando" {
			if err := a.hacerAccionTrato(l.trato, yo, "aceptar", true); err != nil {
				return nil, err
			}
		}
	}
	modo := "prueba"
	if a.stripe != nil {
		modo = "stripe"
	}
	secreto = azar()
	res, err := a.db.Exec(`INSERT INTO cobros (comprador_id, vendedor_id, chat_id, tratos, monto_c, proveedor, secreto, creado)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`, yo, vendedor, chat, string(idsJSON), total, modo, secreto, ahora())
	if err != nil {
		return nil, err
	}
	c = Cobro{Modo: modo, MontoC: total, Estado: "pendiente"}
	c.ID, _ = res.LastInsertId()
	base := fmt.Sprintf("%s/api/cobros/%d", urlPublica(r), c.ID)
	sesion := ""
	if a.stripe != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 25*time.Second)
		defer cancel()
		s, err := a.stripe.crearSesion(ctx, c.ID, lineas, base+"/vuelta?s="+secreto, base+"/vuelta?cancelado=1&s="+secreto)
		if err != nil {
			a.db.Exec("UPDATE cobros SET estado = 'cancelado' WHERE id = ?", c.ID)
			return nil, err
		}
		c.URL, sesion = s.URL, s.ID
	} else {
		c.URL = base + "/prueba?s=" + secreto
	}
	a.db.Exec("UPDATE cobros SET url = ?, sesion = ? WHERE id = ?", c.URL, sesion, c.ID)
	return &c, nil
}

// cobrado marca el cobro como pagado (una sola vez) y lo cuenta en el chat.
func (a *App) cobrado(id int64, como string) {
	res, err := a.db.Exec("UPDATE cobros SET estado = 'pagado', pagado = ? WHERE id = ? AND estado = 'pendiente'", ahora(), id)
	if err != nil {
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return
	}
	var comprador, vendedor, monto int64
	var chat sql.NullInt64
	var tratosJSON string
	a.db.QueryRow("SELECT comprador_id, vendedor_id, chat_id, tratos, monto_c FROM cobros WHERE id = ?", id).
		Scan(&comprador, &vendedor, &chat, &tratosJSON, &monto)
	var ids []int64
	json.Unmarshal([]byte(tratosJSON), &ids)
	quien := a.nombreTienda(comprador)
	for _, t := range ids {
		a.db.Exec("UPDATE tratos SET cobro_id = ? WHERE id = ? AND cobro_id IS NULL", id, t)
		a.sistemaTrato(t, fmt.Sprintf("%s pagó %s en línea (%s).", quien, pesosTxt(monto), como))
	}
	texto := fmt.Sprintf("💳 %s pagó %s en línea. Ya puedes preparar la entrega.", quien, pesosTxt(monto))
	enlace := "/panel/tratos"
	if chat.Valid {
		a.mensajeChat(chat.Int64, nil, "sistema", texto, nil)
		enlace = fmt.Sprintf("/panel/chats/%d", chat.Int64)
	}
	a.avisar(vendedor, fmt.Sprintf("cobro:%d:pagado", id), "trato", texto, enlace)
	log.Printf("cobro %d pagado (%s): %s", id, como, pesosTxt(monto))
}

// revisarCobro pregunta a Stripe si ya se pagó (por si el webhook no llegó).
func (a *App) revisarCobro(ctx context.Context, id int64) string {
	var estado, proveedor, sesion string
	if a.db.QueryRow("SELECT estado, proveedor, sesion FROM cobros WHERE id = ?", id).Scan(&estado, &proveedor, &sesion) != nil {
		return ""
	}
	if estado == "pendiente" && proveedor == "stripe" && sesion != "" && a.stripe != nil {
		if s, err := a.stripe.verSesion(ctx, sesion); err == nil {
			switch {
			case s.PaymentStatus == "paid":
				a.cobrado(id, "Stripe")
				estado = "pagado"
			case s.Status == "expired":
				a.db.Exec("UPDATE cobros SET estado = 'cancelado' WHERE id = ? AND estado = 'pendiente'", id)
				estado = "cancelado"
			}
		}
	}
	return estado
}

// ---------------- HTTP ----------------

// verCobro: GET /api/cobros/{id} → estado (la app lo pide al cerrar la hoja de pago).
func (a *App) verCobro(w http.ResponseWriter, r *http.Request) {
	id, err := idRuta(r)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	var c Cobro
	var comprador, vendedor int64
	if a.db.QueryRow("SELECT id, url, proveedor, monto_c, comprador_id, vendedor_id FROM cobros WHERE id = ?", id).
		Scan(&c.ID, &c.URL, &c.Modo, &c.MontoC, &comprador, &vendedor) != nil || (tiendaDe(r) != comprador && tiendaDe(r) != vendedor) {
		manejar(w, nil, fallo(404, "Ese cobro no existe"))
		return
	}
	c.Estado = a.revisarCobro(r.Context(), id)
	manejar(w, c, nil)
}

// cobroConSecreto: para las páginas públicas (prueba y vuelta), que no llevan token.
func (a *App) cobroConSecreto(r *http.Request) (id int64, monto int64, estado, proveedor, tratos string, err error) {
	id, err = idRuta(r)
	if err != nil {
		return
	}
	var secreto string
	err = a.db.QueryRow("SELECT monto_c, estado, proveedor, tratos, secreto FROM cobros WHERE id = ?", id).Scan(&monto, &estado, &proveedor, &tratos, &secreto)
	if err != nil || subtle.ConstantTimeCompare([]byte(secreto), []byte(r.URL.Query().Get("s"))) != 1 {
		err = fallo(404, "Ese cobro no existe")
	}
	return
}

var plantillaCobro = template.Must(template.New("cobro").Parse(`<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{.Titulo}} · Anaquel</title>
<style>
:root{--verde:#0E7C5A;--fondo:#F2F5F3;--texto:#14231C;--gris:#5E6B65}
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--fondo);color:var(--texto);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px}
.caja{background:#fff;border-radius:24px;box-shadow:0 10px 40px rgb(0 0 0/.08);padding:24px;max-width:420px;width:100%;animation:entra .45s cubic-bezier(.2,.9,.3,1.2)}
@keyframes entra{from{opacity:0;transform:translateY(24px) scale(.97)}}
h1{font-size:22px;margin:.2em 0}.monto{font-size:40px;font-weight:900;margin:.1em 0 .4em}
.gris{color:var(--gris);font-size:14px}.linea{display:flex;justify-content:space-between;font-size:14px;padding:6px 0;border-bottom:1px solid #EEF1EF}
label{display:block;font-size:12px;font-weight:700;color:var(--gris);margin:14px 0 4px}
input{width:100%;font-size:17px;padding:12px 14px;border:1.5px solid #DDE3E0;border-radius:12px;background:#FAFBFA}
.fila{display:flex;gap:10px}.fila>div{flex:1}
button,.boton{display:block;width:100%;margin-top:20px;padding:15px;border:0;border-radius:16px;background:var(--verde);color:#fff;font-size:17px;font-weight:800;text-align:center;text-decoration:none;cursor:pointer}
button:active{transform:scale(.98)}.prueba{background:#FFF4E0;color:#8A5300;border-radius:12px;padding:10px 12px;font-size:13px;margin-bottom:12px}
.ok{width:84px;height:84px;border-radius:50%;background:var(--verde);display:flex;align-items:center;justify-content:center;margin:4px auto 12px;animation:pop .6s cubic-bezier(.2,1.6,.4,1)}
@keyframes pop{from{transform:scale(0)}}.ok svg{width:46px;height:46px}.centro{text-align:center}
.marca{font-weight:900;color:var(--verde);letter-spacing:.5px;font-size:13px}
</style></head><body><div class="caja">
{{if eq .Vista "prueba"}}
  <div class="marca">ANAQUEL · PAGO</div>
  <div class="prueba">Modo de prueba: no se cobra dinero real. Cuando se configure la llave de Stripe, aquí aparece el pago de Stripe.</div>
  <div class="gris">Total a pagar</div><div class="monto">{{.Monto}}</div>
  {{range .Lineas}}<div class="linea"><span>{{.}}</span></div>{{end}}
  <form method="post">
    <label>Número de tarjeta</label><input inputmode="numeric" value="4242 4242 4242 4242" autocomplete="off">
    <div class="fila"><div><label>Vence</label><input value="12/34"></div><div><label>CVC</label><input value="123"></div></div>
    <button type="submit">Pagar {{.Monto}}</button>
  </form>
{{else if eq .Vista "pagado"}}
  <div class="centro"><div class="ok"><svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg></div>
  <h1>¡Pago recibido!</h1><div class="monto" style="font-size:30px">{{.Monto}}</div>
  <p class="gris">Ya le avisamos a la tienda. Regresa a Anaquel para ver el pedido en el chat.</p>
  <a class="boton" href="anaquel://pago/{{.ID}}">Volver a Anaquel</a></div>
{{else if eq .Vista "cancelado"}}
  <div class="centro"><h1>No se hizo el cargo</h1><p class="gris">Puedes intentarlo otra vez desde el chat cuando quieras.</p>
  <a class="boton" href="anaquel://pago/{{.ID}}">Volver a Anaquel</a></div>
{{else}}
  <div class="centro"><h1>Revisando tu pago…</h1><p class="gris">Stripe aún no lo confirma. En unos segundos se verá en el chat.</p>
  <a class="boton" href="anaquel://pago/{{.ID}}">Volver a Anaquel</a></div>
{{end}}
</div></body></html>`))

func (a *App) paginaCobro(w http.ResponseWriter, vista string, id, monto int64, lineas []string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	titulo := map[string]string{"prueba": "Pagar", "pagado": "Pago recibido", "cancelado": "Pago cancelado"}[vista]
	if titulo == "" {
		titulo = "Revisando pago"
	}
	plantillaCobro.Execute(w, map[string]any{"Vista": vista, "ID": id, "Monto": pesosTxt(monto), "Lineas": lineas, "Titulo": titulo})
}

// pruebaCobro: GET muestra el formulario de pago de prueba; POST «paga».
func (a *App) pruebaCobro(w http.ResponseWriter, r *http.Request) {
	id, monto, estado, proveedor, tratosJSON, err := a.cobroConSecreto(r)
	if err == nil && proveedor != "prueba" {
		err = fallo(404, "Ese cobro no existe")
	}
	if err != nil {
		manejar(w, nil, err)
		return
	}
	vuelta := fmt.Sprintf("%s/api/cobros/%d/vuelta?s=%s", urlPublica(r), id, url.QueryEscape(r.URL.Query().Get("s")))
	if r.Method == http.MethodPost {
		if estado == "pendiente" {
			a.cobrado(id, "pago de prueba")
		}
		http.Redirect(w, r, vuelta, http.StatusSeeOther)
		return
	}
	if estado != "pendiente" {
		http.Redirect(w, r, vuelta, http.StatusSeeOther)
		return
	}
	var ids []int64
	json.Unmarshal([]byte(tratosJSON), &ids)
	var lineas []string
	for _, t := range ids {
		var titulo string
		var cant, precio int64
		if a.db.QueryRow("SELECT titulo, cantidad, precio_c FROM tratos WHERE id = ?", t).Scan(&titulo, &cant, &precio) == nil {
			lineas = append(lineas, fmt.Sprintf("%s × %s · %s", cantidadTxt(cant), titulo, pesosTxt(cant*precio)))
		}
	}
	a.paginaCobro(w, "prueba", id, monto, lineas)
}

// vueltaCobro: a donde regresa el navegador después de pagar (o cancelar).
func (a *App) vueltaCobro(w http.ResponseWriter, r *http.Request) {
	id, monto, _, _, _, err := a.cobroConSecreto(r)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	estado := a.revisarCobro(r.Context(), id)
	vista := "revisando"
	switch {
	case estado == "pagado":
		vista = "pagado"
	case r.URL.Query().Get("cancelado") == "1" || estado == "cancelado":
		vista = "cancelado"
	}
	a.paginaCobro(w, vista, id, monto, nil)
}

// webhookStripe: POST /api/cobros/stripe/webhook. Aunque la firma sea válida,
// el estado se vuelve a pedir a Stripe con la llave secreta antes de marcar nada.
func (a *App) webhookStripe(w http.ResponseWriter, r *http.Request) {
	if a.stripe == nil {
		http.Error(w, "sin Stripe", http.StatusNotFound)
		return
	}
	cuerpo, err := io.ReadAll(io.LimitReader(r.Body, 512<<10))
	if err != nil {
		http.Error(w, "cuerpo", http.StatusBadRequest)
		return
	}
	if a.stripe.firma != "" && !firmaValida(a.stripe.firma, r.Header.Get("Stripe-Signature"), cuerpo, time.Now()) {
		http.Error(w, "firma", http.StatusBadRequest)
		return
	}
	var ev struct {
		Type string `json:"type"`
		Data struct {
			Object struct {
				ID       string            `json:"id"`
				Object   string            `json:"object"`
				Metadata map[string]string `json:"metadata"`
			} `json:"object"`
		} `json:"data"`
	}
	if json.Unmarshal(cuerpo, &ev) != nil {
		http.Error(w, "json", http.StatusBadRequest)
		return
	}
	o := ev.Data.Object
	if o.Object == "checkout.session" {
		var id int64
		if a.db.QueryRow("SELECT id FROM cobros WHERE sesion = ?", o.ID).Scan(&id) == nil {
			a.revisarCobro(r.Context(), id)
		}
	}
	w.WriteHeader(http.StatusOK)
}

// pagarDesdeChat: el botón «Pagar» de una tarjeta de oferta o pedido.
func (a *App) pagarDesdeChat(r *http.Request, yo int64, c Chat, tratos []int64) (*Cobro, error) {
	cobro, err := a.iniciarCobro(r, yo, c.ID, tratos)
	if err != nil {
		var e errHTTP
		if errors.As(err, &e) {
			return nil, err
		}
		return nil, fallo(502, err.Error())
	}
	return cobro, nil
}
