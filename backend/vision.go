package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"strings"
	"time"

	"golang.org/x/image/draw"
)

// Zona a revisar: rectángulo normalizado (0..1) dentro de la foto de referencia.
type ZonaVision struct {
	ID     int64
	Nombre string
	X, Y   float64
	W, H   float64
}

// Lectura de una zona en la foto del día.
type Lectura struct {
	ZonaID int64   `json:"zona_id"`
	Nivel  string  `json:"nivel"` // lleno | poco | vacio
	Score  float64 `json:"score"` // 0 = vacío … 1 = como la referencia
}

type Vision interface {
	Nombre() string
	Analizar(ref, hoy []byte, zonas []ZonaVision) ([]Lectura, error)
}

// nuevaVision usa Claude si hay API key; si no, la comparación de imágenes local.
// La comparación local siempre queda como respaldo si la IA falla.
func nuevaVision() Vision {
	local := Comparador{}
	key := os.Getenv("ANTHROPIC_API_KEY")
	if f := os.Getenv("ANTHROPIC_API_KEY_FILE"); key == "" && f != "" {
		if b, err := os.ReadFile(f); err == nil {
			key = strings.TrimSpace(string(b))
		}
	}
	if key == "" {
		return local
	}
	return ConIA{key: key, modelo: env("MODELO_VISION", "claude-sonnet-5-5"), respaldo: local}
}

func nivelDe(score float64) string {
	switch {
	case score >= 0.62:
		return "lleno"
	case score >= 0.35:
		return "poco"
	default:
		return "vacio"
	}
}

// ---------------------------------------------------------------------------
// Comparación local (sin internet): compara cada zona contra la foto del
// anaquel lleno. Un hueco se ve "liso" (el fondo del estante) y distinto a la
// referencia; un producto tiene bordes, letras y colores.
// ---------------------------------------------------------------------------

type Comparador struct{}

func (Comparador) Nombre() string { return "comparacion" }

const anchoTrabajo = 320

// gris: imagen en escala de grises como matriz de float.
type gris struct {
	w, h int
	p    []float64
	rgb  [][3]float64
}

func decodificar(b []byte, w, h int) (*gris, error) {
	img, _, err := image.Decode(bytes.NewReader(b))
	if err != nil {
		return nil, fmt.Errorf("la foto no es una imagen válida")
	}
	if h == 0 {
		bw, bh := img.Bounds().Dx(), img.Bounds().Dy()
		w = anchoTrabajo
		h = int(math.Round(float64(bh) * float64(w) / float64(bw)))
		if h < 8 {
			h = 8
		}
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.ApproxBiLinear.Scale(dst, dst.Bounds(), img, img.Bounds(), draw.Src, nil)
	g := &gris{w: w, h: h, p: make([]float64, w*h), rgb: make([][3]float64, w*h)}
	for i := 0; i < w*h; i++ {
		r, gg, bb := float64(dst.Pix[i*4]), float64(dst.Pix[i*4+1]), float64(dst.Pix[i*4+2])
		g.p[i] = 0.299*r + 0.587*gg + 0.114*bb
		g.rgb[i] = [3]float64{r, gg, bb}
	}
	return g, nil
}

// gradiente: magnitud del borde en cada píxel (Sobel simplificado).
func (g *gris) gradiente() []float64 {
	out := make([]float64, g.w*g.h)
	for y := 1; y < g.h-1; y++ {
		for x := 1; x < g.w-1; x++ {
			i := y*g.w + x
			gx := g.p[i+1] - g.p[i-1]
			gy := g.p[i+g.w] - g.p[i-g.w]
			out[i] = math.Sqrt(gx*gx + gy*gy)
		}
	}
	return out
}

// alinear busca el desplazamiento (dx, dy) que mejor encaja la foto de hoy con
// la referencia: nadie toma la foto exactamente desde el mismo punto.
func alinear(ref, hoy []float64, w, h int) (int, int) {
	maxD := w / 12
	mejor, bx, by := math.Inf(-1), 0, 0
	for dy := -maxD; dy <= maxD; dy += 2 {
		for dx := -maxD; dx <= maxD; dx += 2 {
			var s, n float64
			for y := maxD; y < h-maxD; y += 3 {
				for x := maxD; x < w-maxD; x += 3 {
					s += ref[y*w+x] * hoy[(y+dy)*w+x+dx]
					n++
				}
			}
			if n > 0 && s/n > mejor {
				mejor, bx, by = s/n, dx, dy
			}
		}
	}
	return bx, by
}

func (Comparador) Analizar(refB, hoyB []byte, zonas []ZonaVision) ([]Lectura, error) {
	ref, err := decodificar(refB, 0, 0)
	if err != nil {
		return nil, err
	}
	hoy, err := decodificar(hoyB, ref.w, ref.h)
	if err != nil {
		return nil, err
	}
	// Compensa la luz: de noche o con el foco apagado toda la foto sale más oscura.
	var mr, mh float64
	for i := range ref.p {
		mr += ref.p[i]
		mh += hoy.p[i]
	}
	if mh > 0 {
		f := mr / mh
		for i := range hoy.p {
			hoy.p[i] *= f
			for c := 0; c < 3; c++ {
				hoy.rgb[i][c] *= f
			}
		}
	}
	gr, gh := ref.gradiente(), hoy.gradiente()
	dx, dy := alinear(gr, gh, ref.w, ref.h)

	out := make([]Lectura, 0, len(zonas))
	for _, z := range zonas {
		x0, y0 := int(z.X*float64(ref.w)), int(z.Y*float64(ref.h))
		x1, y1 := int((z.X+z.W)*float64(ref.w)), int((z.Y+z.H)*float64(ref.h))
		var bordeRef, bordeHoy, difColor, n float64
		var a, b []float64
		for y := max(y0, 1); y < min(y1, ref.h-1); y++ {
			yy := y + dy
			if yy < 1 || yy >= ref.h-1 {
				continue
			}
			for x := max(x0, 1); x < min(x1, ref.w-1); x++ {
				xx := x + dx
				if xx < 1 || xx >= ref.w-1 {
					continue
				}
				i, j := y*ref.w+x, yy*ref.w+xx
				bordeRef += gr[i]
				bordeHoy += gh[j]
				for c := 0; c < 3; c++ {
					difColor += math.Abs(ref.rgb[i][c] - hoy.rgb[j][c])
				}
				a = append(a, ref.p[i])
				b = append(b, hoy.p[j])
				n++
			}
		}
		if n < 20 {
			out = append(out, Lectura{ZonaID: z.ID, Nivel: "lleno", Score: 1})
			continue
		}
		// 1) ¿Conserva la "textura" de productos? (un hueco es liso)
		textura := 1.0
		if bordeRef > 0 {
			textura = math.Min(1, bordeHoy/bordeRef)
		}
		// 2) ¿Se parece a la referencia? (correlación de luminosidad)
		parecido := math.Max(0, correlacion(a, b))
		// 3) ¿Cambió mucho el color? (de 0 a 1)
		color := 1 - math.Min(1, difColor/n/3/90)
		score := 0.45*textura + 0.35*parecido + 0.20*color
		score = math.Round(score*100) / 100
		out = append(out, Lectura{ZonaID: z.ID, Nivel: nivelDe(score), Score: score})
	}
	return out, nil
}

func correlacion(a, b []float64) float64 {
	n := float64(len(a))
	var ma, mb float64
	for i := range a {
		ma += a[i]
		mb += b[i]
	}
	ma /= n
	mb /= n
	var num, da, db float64
	for i := range a {
		x, y := a[i]-ma, b[i]-mb
		num += x * y
		da += x * x
		db += y * y
	}
	if da == 0 || db == 0 {
		return 0
	}
	return num / math.Sqrt(da*db)
}

// ---------------------------------------------------------------------------
// Con IA (Claude): ve las dos fotos y las zonas numeradas, y dice el nivel de
// cada una. Si falla por lo que sea, se usa la comparación local.
// ---------------------------------------------------------------------------

type ConIA struct {
	key, modelo string
	respaldo    Vision
}

func (c ConIA) Nombre() string { return "ia" }

func (c ConIA) Analizar(ref, hoy []byte, zonas []ZonaVision) ([]Lectura, error) {
	l, err := c.preguntar(ref, hoy, zonas)
	if err != nil {
		log.Println("visión IA falló, uso comparación:", err)
		return c.respaldo.Analizar(ref, hoy, zonas)
	}
	return l, nil
}

func (c ConIA) preguntar(ref, hoy []byte, zonas []ZonaVision) ([]Lectura, error) {
	var lista strings.Builder
	for _, z := range zonas {
		fmt.Fprintf(&lista, "- zona %d (%s): x=%.2f y=%.2f ancho=%.2f alto=%.2f\n", z.ID, z.Nombre, z.X, z.Y, z.W, z.H)
	}
	prompt := "La primera foto es un anaquel de una tienda LLENO (referencia). La segunda es el mismo anaquel HOY.\n" +
		"Coordenadas normalizadas 0..1 sobre la primera foto (x,y = esquina superior izquierda):\n" + lista.String() +
		"Para cada zona di cuánto producto queda HOY comparado con la referencia: \"lleno\", \"poco\" o \"vacio\", " +
		"y un score de 0 (vacío) a 1 (igual que la referencia).\n" +
		`Responde SOLO JSON: [{"zona_id":1,"nivel":"lleno","score":0.9}]`
	img := func(b []byte) map[string]any {
		return map[string]any{"type": "image", "source": map[string]any{
			"type": "base64", "media_type": http.DetectContentType(b), "data": base64.StdEncoding.EncodeToString(b)}}
	}
	cuerpo, _ := json.Marshal(map[string]any{
		"model": c.modelo, "max_tokens": 1500,
		"messages": []any{map[string]any{"role": "user", "content": []any{
			img(ref), img(hoy), map[string]any{"type": "text", "text": prompt}}}},
	})
	req, _ := http.NewRequest("POST", "https://api.anthropic.com/v1/messages", bytes.NewReader(cuerpo))
	req.Header.Set("x-api-key", c.key)
	req.Header.Set("anthropic-version", "2023-06-01")
	req.Header.Set("content-type", "application/json")
	resp, err := (&http.Client{Timeout: 60 * time.Second}).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("API %d: %.200s", resp.StatusCode, raw)
	}
	var r struct {
		Content []struct {
			Text string `json:"text"`
		} `json:"content"`
	}
	if err := json.Unmarshal(raw, &r); err != nil || len(r.Content) == 0 {
		return nil, fmt.Errorf("respuesta rara")
	}
	txt := r.Content[0].Text
	i, j := strings.Index(txt, "["), strings.LastIndex(txt, "]")
	if i < 0 || j < i {
		return nil, fmt.Errorf("sin JSON")
	}
	var out []Lectura
	if err := json.Unmarshal([]byte(txt[i:j+1]), &out); err != nil {
		return nil, err
	}
	validas := map[int64]bool{}
	for _, z := range zonas {
		validas[z.ID] = true
	}
	res := out[:0]
	for _, l := range out {
		if validas[l.ZonaID] && (l.Nivel == "lleno" || l.Nivel == "poco" || l.Nivel == "vacio") {
			res = append(res, l)
		}
	}
	if len(res) != len(zonas) {
		return nil, fmt.Errorf("faltaron zonas en la respuesta")
	}
	return res, nil
}

// guardarJPEG recomprime la foto (máx. 1280 px) para no llenar el disco.
func normalizarFoto(b []byte) ([]byte, error) {
	img, _, err := image.Decode(bytes.NewReader(b))
	if err != nil {
		return nil, fallo(400, "La foto no es una imagen válida (usa JPG o PNG)")
	}
	w, h := img.Bounds().Dx(), img.Bounds().Dy()
	const maxLado = 1280
	if w > maxLado || h > maxLado {
		f := float64(maxLado) / float64(max(w, h))
		w, h = int(float64(w)*f), int(float64(h)*f)
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.CatmullRom.Scale(dst, dst.Bounds(), img, img.Bounds(), draw.Src, nil)
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, dst, &jpeg.Options{Quality: 82}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
