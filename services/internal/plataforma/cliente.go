package plataforma

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// ClienteInterno llama a otro servicio firmando como servicio (rol
// "servicio", nombre = quién llama). Solo para la red interna.
type ClienteInterno struct {
	Base     string
	Quien    string
	Firmante *Firmante
	HTTP     *http.Client
}

func NuevoClienteInterno(base, quien string, f *Firmante) *ClienteInterno {
	return &ClienteInterno{Base: strings.TrimRight(base, "/"), Quien: quien, Firmante: f, HTTP: &http.Client{Timeout: 8 * time.Second}}
}

// Llamar hace la petición y decodifica la respuesta JSON en salida (si no es nil).
func (c *ClienteInterno) Llamar(ctx context.Context, metodo, ruta string, entrada, salida any) error {
	var cuerpo io.Reader
	if entrada != nil {
		b, err := json.Marshal(entrada)
		if err != nil {
			return err
		}
		cuerpo = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, metodo, c.Base+ruta, cuerpo)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(CabeceraIdentidad, c.Firmante.Firmar(Identidad{Rol: RolServicio, Nombre: c.Quien}))
	res, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	datos, err := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if err != nil {
		return err
	}
	if res.StatusCode >= 300 {
		var e ErrHTTP
		if json.Unmarshal(datos, &e) == nil && e.Msg != "" {
			e.Codigo = res.StatusCode
			return e
		}
		return fmt.Errorf("%s %s: estado %d", metodo, ruta, res.StatusCode)
	}
	if salida != nil {
		return json.Unmarshal(datos, salida)
	}
	return nil
}
