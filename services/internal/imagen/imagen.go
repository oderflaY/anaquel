// Package imagen valida y normaliza fotos subidas por usuarios.
//
// Nunca se guarda el archivo original: se decodifica, se reduce y se vuelve a
// codificar como JPEG. Así se eliminan metadatos (EXIF con la ubicación GPS
// del celular), contenido escondido (archivos "políglotas" que también son
// HTML o ZIP) y formatos raros. Antes de decodificar se revisan las
// dimensiones para no caer en "bombas de descompresión".
package imagen

import (
	"bufio"
	"bytes"
	"errors"
	"image"
	"image/jpeg"
	_ "image/png"
	"io"

	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

var (
	ErrFormato   = errors.New("La foto debe ser JPG, PNG o WebP")
	ErrDimension = errors.New("La foto es demasiado grande (máximo 8000 × 8000 píxeles)")
	ErrPequena   = errors.New("La foto es demasiado pequeña (mínimo 200 × 200 píxeles)")
)

const (
	maxLado    = 8000
	maxPixeles = 40_000_000
	ladoFinal  = 1600
)

// Resultado de procesar una foto.
type Resultado struct {
	JPEG        []byte
	Ancho, Alto int
}

// firma revisa los primeros bytes (no confiamos en la extensión ni en el
// Content-Type que manda el navegador).
func firma(b []byte) bool {
	switch {
	case len(b) >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF:
		return true // JPEG
	case len(b) >= 8 && bytes.Equal(b[:8], []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}):
		return true // PNG
	case len(b) >= 12 && string(b[:4]) == "RIFF" && string(b[8:12]) == "WEBP":
		return true // WebP
	}
	return false
}

// Procesar lee hasta max bytes de r y regresa un JPEG limpio de máximo 1600 px.
func Procesar(r io.Reader, max int64) (Resultado, error) {
	datos, err := io.ReadAll(io.LimitReader(r, max+1))
	if err != nil {
		return Resultado{}, err
	}
	if int64(len(datos)) > max {
		return Resultado{}, errors.New("La foto pesa demasiado (máximo 8 MB)")
	}
	if !firma(datos) {
		return Resultado{}, ErrFormato
	}
	cfg, formato, err := image.DecodeConfig(bytes.NewReader(datos))
	if err != nil || (formato != "jpeg" && formato != "png" && formato != "webp") {
		return Resultado{}, ErrFormato
	}
	if cfg.Width > maxLado || cfg.Height > maxLado || cfg.Width*cfg.Height > maxPixeles {
		return Resultado{}, ErrDimension
	}
	if cfg.Width < 200 || cfg.Height < 200 {
		return Resultado{}, ErrPequena
	}
	img, _, err := image.Decode(bufio.NewReader(bytes.NewReader(datos)))
	if err != nil {
		return Resultado{}, ErrFormato
	}
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if w > ladoFinal || h > ladoFinal {
		if w >= h {
			h = h * ladoFinal / w
			w = ladoFinal
		} else {
			w = w * ladoFinal / h
			h = ladoFinal
		}
	}
	// Siempre se redibuja (aunque no cambie el tamaño): el resultado es un
	// lienzo RGBA nuevo, sin nada del archivo original.
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.Draw(dst, dst.Bounds(), image.White, image.Point{}, draw.Src) // fondo blanco para PNG transparentes
	draw.CatmullRom.Scale(dst, dst.Bounds(), img, b, draw.Over, nil)
	var out bytes.Buffer
	if err := jpeg.Encode(&out, dst, &jpeg.Options{Quality: 82}); err != nil {
		return Resultado{}, err
	}
	return Resultado{JPEG: out.Bytes(), Ancho: w, Alto: h}, nil
}
