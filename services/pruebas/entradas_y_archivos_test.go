package pruebas

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"image"
	"image/color"
	stdjpeg "image/jpeg"
	"image/png"
	"net/http"
	"os"
	"strings"
	"testing"
)

func jpeg(w, h int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for x := range w {
		img.Set(x, x%h, color.RGBA{14, 124, 90, 255})
	}
	var b bytes.Buffer
	_ = stdjpeg.Encode(&b, img, nil)
	return b.Bytes()
}

func TestValidacionDeEntradas(t *testing.T) {
	v := nuevo(t).entrarOk("abarrotes.mayoreo@anaquel.mx")
	c := nuevo(t).entrarOk("papeleria@anaquel.mx")
	pub := nuevaPublicacion(v, "Producto para validar", ptr(100), 10000)

	t.Run("precios, cantidades y existencias fuera de rango", func(t *testing.T) {
		base := map[string]any{"titulo": "Válido", "categoria": "otros", "precio_c": 1000, "unidad": "pieza", "minimo": 1}
		malos := []map[string]any{{"precio_c": -100}, {"precio_c": 0}, {"precio_c": 1e15}, {"existencias": -1}, {"minimo": -1}, {"minimo": 100001},
			{"unidad": "tonelada"}, {"categoria": "no-existe"}, {"titulo": "ab"}, {"titulo": strings.Repeat("x", 81)}, {"descripcion": strings.Repeat("x", 1001)}}
		for _, m := range malos {
			d := map[string]any{}
			for k, x := range base {
				d[k] = x
			}
			for k, x := range m {
				d[k] = x
			}
			if r := v.pedir("POST", "/mercado/publicaciones", d); r.codigo != 400 {
				t.Errorf("%v → %s", m, r)
			}
		}
		for _, n := range []any{0, -5, 100001, 1e12, "10", 1.5} {
			if r := c.pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": pub, "cantidad": n}); r.codigo != 400 {
				t.Errorf("cantidad %v → %s", n, r)
			}
		}
		esperar(t, c.pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": pub, "cantidad": 101}), 409)
		esperar(t, v.pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": pub, "cantidad": 1}), 400) // comprarse a sí mismo
	})

	t.Run("JSON malformado, campos desconocidos y cuerpos enormes", func(t *testing.T) {
		esperar(t, c.pedir("POST", "/mercado/ordenes", "{no es json"), 400)
		esperar(t, c.pedir("POST", "/mercado/ordenes", `{"publicacion_id":1,"cantidad":1}{"x":1}`), 400)
		esperar(t, c.pedir("POST", "/mercado/ordenes", map[string]any{"publicacion_id": pub, "cantidad": 1, "total_c": 1}), 400)
		grande := `{"publicacion_id":1,"cantidad":1,"nota":"` + strings.Repeat("a", 300<<10) + `"}`
		esperar(t, c.pedir("POST", "/mercado/ordenes", grande), 413, 400)
		esperar(t, c.pedir("GET", "/mercado/ordenes/abc", nil), 400, 404)
		esperar(t, c.pedir("GET", "/mercado/ordenes/-1", nil), 400, 404)
		esperar(t, c.pedir("GET", "/mercado/ordenes/99999999999999999999", nil), 400, 404)
	})

	t.Run("inyección SQL en búsqueda y filtros", func(t *testing.T) {
		for _, q := range []string{"' OR 1=1 --", "%' UNION SELECT pass_hash FROM acceso.cuentas --", "%", "_", `\`, "gorra'; DROP TABLE publicaciones; --"} {
			r := c.pedir("GET", "/mercado/publicaciones?q="+urlEsc(q), nil)
			if r.codigo != 200 || strings.Contains(string(r.cuerpo), "argon2") {
				t.Errorf("q=%q → %s", q, r)
			}
		}
		if n := len(c.pedir("GET", "/mercado/publicaciones?q=%25", nil).json()["items"].([]any)); n != 0 {
			t.Errorf("'%%' funcionó como comodín y regresó %d productos", n)
		}
		r := c.pedir("GET", "/mercado/publicaciones?categoria="+urlEsc("x' OR '1'='1"), nil)
		if items, _ := r.json()["items"].([]any); r.codigo != 200 || len(items) != 0 {
			t.Errorf("filtro inyectado: %s", r)
		}
		esperar(t, c.pedir("GET", "/mercado/publicaciones", nil), 200) // la tabla sigue ahí
	})

	t.Run("XSS guardado: el texto se guarda y se devuelve como dato, nunca como HTML", func(t *testing.T) {
		xss := `<img src=x onerror=alert(document.cookie)><script>alert(1)</script>`
		id := nuevaPublicacion(v, xss, ptr(1), 1000)
		r := c.pedir("GET", fmt.Sprintf("/mercado/publicaciones/%d", id), nil)
		if !strings.HasPrefix(r.cab.Get("Content-Type"), "application/json") || r.cab.Get("X-Content-Type-Options") != "nosniff" {
			t.Errorf("respuesta sin tipo JSON estricto: %v", r.cab)
		}
		if !strings.Contains(r.cab.Get("Content-Security-Policy"), "script-src 'self'") {
			t.Error("sin CSP que impida scripts en línea")
		}
		// Caracteres de control e invisibles (bidi) se eliminan.
		id2 := nuevaPublicacion(v, "Gorra‮​\x00 roja", ptr(1), 1000)
		if r := c.pedir("GET", fmt.Sprintf("/mercado/publicaciones/%d", id2), nil); !strings.Contains(string(r.cuerpo), `"titulo":"Gorra roja"`) {
			t.Errorf("no limpió caracteres invisibles: %s", r)
		}
	})

	t.Run("recorrido de rutas en archivos", func(t *testing.T) {
		for _, p := range []string{"/mercado/fotos/../../../../etc/passwd", "/mercado/fotos/..%2f..%2fetc%2fpasswd", "/mercado/fotos/%2e%2e%2fsecret.jpg",
			"/reels/media/..%5c..%5cwindows%5cwin.ini", "/mercado/fotos/0123456789abcdef0123456789abcdef.jpg"} {
			if r := c.pedir("GET", p, nil); r.codigo != 404 && r.codigo != 400 {
				t.Errorf("%s → %s", p, r)
			}
		}
	})
}

func TestArchivos(t *testing.T) {
	v := nuevo(t).entrarOk("demo@anaquel.mx")
	c := nuevo(t).entrarOk("papeleria@anaquel.mx")
	pub := nuevaPublicacion(v, "Producto con fotos", ptr(10), 5000)
	ruta := fmt.Sprintf("/mercado/publicaciones/%d/fotos", pub)

	t.Run("HTML, SVG y ejecutables disfrazados de foto se rechazan", func(t *testing.T) {
		for nombre, datos := range map[string][]byte{
			"foto.jpg": []byte("<html><script>alert(1)</script></html>"),
			"foto.svg": []byte(`<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>`),
			"foto.png": append([]byte("MZ"), make([]byte, 500)...),
		} {
			esperar(t, v.subir(ruta, map[string]string{"alt": "x"}, "foto", nombre, datos), 400)
		}
	})

	t.Run("bomba de descompresión (PNG de 50,000 × 50,000)", func(t *testing.T) {
		var b bytes.Buffer
		_ = png.Encode(&b, image.NewRGBA(image.Rect(0, 0, 1, 1)))
		d := b.Bytes()
		binary.BigEndian.PutUint32(d[16:], 50000)
		binary.BigEndian.PutUint32(d[20:], 50000)
		binary.BigEndian.PutUint32(d[29:], crc32.ChecksumIEEE(d[12:29]))
		esperar(t, v.subir(ruta, nil, "foto", "bomba.png", d), 400)
	})

	t.Run("foto políglota con EXIF/GPS: se limpia y se sirve segura", func(t *testing.T) {
		j := jpeg(600, 600)
		payload := append([]byte("Exif\x00\x00"), []byte("GPSLatitude=24.0277")...)
		seg := []byte{0xFF, 0xE1, 0, 0}
		binary.BigEndian.PutUint16(seg[2:], uint16(len(payload)+2))
		sucia := append(append(append(append([]byte{}, j[:2]...), seg...), payload...), j[2:]...)
		sucia = append(sucia, []byte("<script>alert(1)</script>")...)
		r := v.subir(ruta, map[string]string{"alt": "Gorra verde de frente"}, "foto", "x.jpg", sucia)
		esperar(t, r, 200)
		url, _ := r.json()["url"].(string)
		f := c.pedir("GET", strings.TrimPrefix(url, "/api"), nil)
		esperar(t, f, 200)
		if bytes.Contains(f.cuerpo, []byte("GPSLatitude")) || bytes.Contains(f.cuerpo, []byte("<script")) {
			t.Error("la foto servida conserva el GPS o el script")
		}
		if f.cab.Get("Content-Type") != "image/jpeg" || f.cab.Get("X-Content-Type-Options") != "nosniff" || !strings.Contains(strings.Join(f.cab.Values("Content-Security-Policy"), ";"), "sandbox") {
			t.Errorf("cabeceras de la foto: %v", f.cab)
		}
		// Sin sesión la foto no se ve.
		esperar(t, nuevo(t).pedir("GET", strings.TrimPrefix(url, "/api"), nil), 401)
	})

	t.Run("límite de 6 fotos y de tamaño", func(t *testing.T) {
		j := jpeg(300, 300)
		for range 6 {
			v.subir(ruta, nil, "foto", "a.jpg", j)
		}
		esperar(t, v.subir(ruta, nil, "foto", "a.jpg", j), 409)
		otra := fmt.Sprintf("/mercado/publicaciones/%d/fotos", nuevaPublicacion(v, "Otra con fotos", ptr(1), 1000))
		esperar(t, v.subir(otra, nil, "foto", "grande.jpg", append(jpeg(300, 300), make([]byte, 10<<20)...)), 400, 413)
	})

	t.Run("videos: texto renombrado a .mp4 se rechaza; MP4 real se acepta y admite Range", func(t *testing.T) {
		esperar(t, v.subir("/reels/subir", map[string]string{"descripcion": "Video falso de prueba"}, "video", "x.mp4", []byte("<html>no soy video</html>")), 400)
		esperar(t, v.subir("/reels/subir", map[string]string{"descripcion": "corto"}, "video", "x.mp4", []byte("x")), 400)
		datos, err := os.ReadFile("../internal/reels/demo/queso.mp4")
		if err != nil {
			t.Fatal(err)
		}
		// Ligar el video a un producto ajeno no se permite.
		ajena := publicacionDe(c, "gorras-el-tunal")
		esperar(t, v.subir("/reels/subir", map[string]string{"descripcion": "Queso de mi tienda", "publicacion_id": fmt.Sprint(ajena)}, "video", "q.mp4", datos), 400)
		r := v.subir("/reels/subir", map[string]string{"descripcion": "Queso menonita de prueba", "publicacion_id": fmt.Sprint(pub)}, "video", "q.mp4", datos)
		esperar(t, r, 200)
		feed := c.pedir("GET", "/reels/", nil)
		items, _ := feed.json()["items"].([]any)
		url := items[0].(map[string]any)["url"].(string)
		rg := c.pedir("GET", strings.TrimPrefix(url, "/api"), nil, map[string]string{"Range": "bytes=0-99"})
		if rg.codigo != http.StatusPartialContent || len(rg.cuerpo) != 100 || rg.cab.Get("Content-Type") != "video/mp4" {
			t.Errorf("Range: %d %d %s", rg.codigo, len(rg.cuerpo), rg.cab.Get("Content-Type"))
		}
	})

	t.Run("subidas de más de 64 MB se cortan en la pasarela", func(t *testing.T) {
		r := v.subir("/reels/subir", map[string]string{"descripcion": "Video demasiado grande"}, "video", "g.mp4", make([]byte, 70<<20))
		esperar(t, r, 413, 400)
	})
}

func urlEsc(s string) string {
	var b strings.Builder
	for _, c := range []byte(s) {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}
