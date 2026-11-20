// Package pruebas ataca el sistema completo en marcha (pasarela + servicios +
// Postgres), como lo haría alguien de fuera. Se corre con:
//
//	CONFIAR_PROXY=1 ./services/correr-local.sh      # entorno de prueba
//	set -a; source services/.env.local; set +a
//	ANAQUEL_URL=http://localhost:8080 go test ./pruebas/ -v
//
// CONFIAR_PROXY=1 permite que cada cliente de prueba simule una IP distinta
// (X-Forwarded-For); así los límites por IP no se mezclan entre pruebas.
package pruebas

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand/v2"
	"mime/multipart"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

var base string

func TestMain(m *testing.M) {
	base = os.Getenv("ANAQUEL_URL")
	if base == "" {
		fmt.Println("ANAQUEL_URL vacío: se omiten las pruebas contra el sistema en marcha")
		os.Exit(0)
	}
	os.Exit(m.Run())
}

type cliente struct {
	t   *testing.T
	ip  string
	jar *cookiejar.Jar
	c   *http.Client
}

func nuevo(t *testing.T) *cliente {
	t.Helper()
	jar, _ := cookiejar.New(nil)
	return &cliente{t: t, ip: fmt.Sprintf("10.%d.%d.%d", rand.IntN(250)+1, rand.IntN(250)+1, rand.IntN(250)+1), jar: jar,
		c: &http.Client{Jar: jar, Timeout: 60 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
}

type resp struct {
	codigo int
	cuerpo []byte
	cab    http.Header
}

func (r resp) json() map[string]any {
	var m map[string]any
	_ = json.Unmarshal(r.cuerpo, &m)
	return m
}
func (r resp) clave() string { s, _ := r.json()["codigo"].(string); return s }
func (r resp) String() string {
	b := string(r.cuerpo)
	if len(b) > 300 {
		b = b[:300]
	}
	return fmt.Sprintf("%d %s", r.codigo, b)
}

func (c *cliente) pedir(metodo, ruta string, cuerpo any, cab ...map[string]string) resp {
	c.t.Helper()
	var rd io.Reader
	tipo := ""
	switch v := cuerpo.(type) {
	case nil:
	case []byte:
		rd = bytes.NewReader(v)
	case *multipart.Writer:
		panic("usar subir()")
	case string:
		rd, tipo = strings.NewReader(v), "application/json"
	default:
		b, _ := json.Marshal(v)
		rd, tipo = bytes.NewReader(b), "application/json"
	}
	req, err := http.NewRequest(metodo, base+"/anaquel/api"+ruta, rd)
	if err != nil {
		c.t.Fatal(err)
	}
	if tipo != "" {
		req.Header.Set("Content-Type", tipo)
	}
	req.Header.Set("X-Forwarded-For", c.ip)
	for _, m := range cab {
		for k, v := range m {
			req.Header.Set(k, v)
		}
	}
	res, err := c.c.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return resp{res.StatusCode, b, res.Header}
}

// subir manda un multipart con campos y un archivo.
func (c *cliente) subir(ruta string, campos map[string]string, campoArchivo, nombre string, datos []byte) resp {
	c.t.Helper()
	var b bytes.Buffer
	w := multipart.NewWriter(&b)
	for k, v := range campos {
		_ = w.WriteField(k, v)
	}
	f, _ := w.CreateFormFile(campoArchivo, nombre)
	_, _ = f.Write(datos)
	_ = w.Close()
	return c.pedir("POST", ruta, b.Bytes(), map[string]string{"Content-Type": w.FormDataContentType()})
}

func (c *cliente) entrar(email, pass string) resp {
	c.t.Helper()
	return c.pedir("POST", "/acceso/entrar", map[string]string{"email": email, "password": pass})
}

func (c *cliente) entrarOk(email string) *cliente {
	c.t.Helper()
	if r := c.entrar(email, "demo1234"); r.codigo != 200 {
		c.t.Fatalf("no pudo entrar %s: %s", email, r)
	}
	return c
}

func (c *cliente) token() string {
	u, _ := url.Parse(base)
	for _, k := range c.jar.Cookies(u) {
		if strings.Contains(k.Name, "anaquel_mercado") {
			return k.Value
		}
	}
	return ""
}

func (c *cliente) ponerToken(v string) {
	u, _ := url.Parse(base)
	c.jar.SetCookies(u, []*http.Cookie{{Name: "anaquel_mercado", Value: v, Path: "/"}})
}

func esperar(t *testing.T, r resp, codigos ...int) {
	t.Helper()
	for _, c := range codigos {
		if r.codigo == c {
			return
		}
	}
	t.Errorf("esperaba %v, llegó %s", codigos, r)
}

func num(m map[string]any, k string) int64 { f, _ := m[k].(float64); return int64(f) }

// bd conecta como el rol de un servicio (para probar el aislamiento).
func bd(t *testing.T, rol string) *pgx.Conn {
	t.Helper()
	pass := os.Getenv("PG_" + strings.ToUpper(rol) + "_PASSWORD")
	if pass == "" {
		t.Skip("sin credenciales de BD en el entorno")
	}
	c, err := pgx.Connect(context.Background(), fmt.Sprintf("postgres://%s_app:%s@127.0.0.1:54330/anaquel?sslmode=disable", rol, pass))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close(context.Background()) })
	return c
}

// publicacionDe regresa el id de una publicación del vendedor con ese slug.
func publicacionDe(c *cliente, slug string) int64 {
	c.t.Helper()
	r := c.pedir("GET", "/mercado/publicaciones?vendedor="+slug, nil)
	items, _ := r.json()["items"].([]any)
	if len(items) == 0 {
		c.t.Fatalf("%s no tiene publicaciones: %s", slug, r)
	}
	return num(items[0].(map[string]any), "id")
}

// nuevaPublicacion crea una publicación propia con existencias dadas.
func nuevaPublicacion(c *cliente, titulo string, existencias *int, precioC int64) int64 {
	c.t.Helper()
	r := c.pedir("POST", "/mercado/publicaciones", map[string]any{"titulo": titulo, "categoria": "otros", "precio_c": precioC, "unidad": "pieza", "existencias": existencias, "minimo": 1})
	if r.codigo != 200 {
		c.t.Fatalf("no se creó la publicación: %s", r)
	}
	return num(r.json(), "id")
}

func ptr(n int) *int { return &n }

func jsonUnmarshal(b []byte, v any) error { return json.Unmarshal(b, v) }

// directo llama a un servicio interno saltándose la pasarela (solo GET).
func (c *cliente) directo(u string) (int, error) {
	res, err := http.Get(u)
	if err != nil {
		return 0, err
	}
	res.Body.Close()
	return res.StatusCode, nil
}
