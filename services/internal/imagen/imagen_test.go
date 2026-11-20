package imagen

import (
	"bytes"
	"encoding/binary"
	"hash/crc32"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"testing"
)

func jpegDe(w, h int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for x := range w {
		img.Set(x, x%h, color.RGBA{200, 80, 40, 255})
	}
	var b bytes.Buffer
	_ = jpeg.Encode(&b, img, nil)
	return b.Bytes()
}

// conExif mete un segmento APP1 (Exif) con un texto de "GPS" justo después del SOI.
func conExif(j []byte, marca string) []byte {
	payload := append([]byte("Exif\x00\x00"), []byte(marca)...)
	seg := []byte{0xFF, 0xE1, 0, 0}
	binary.BigEndian.PutUint16(seg[2:], uint16(len(payload)+2))
	out := append([]byte{}, j[:2]...)
	out = append(out, seg...)
	out = append(out, payload...)
	return append(out, j[2:]...)
}

func TestQuitaExifYPoliglota(t *testing.T) {
	const gps = "GPSLatitude=24.0277;GPSLongitude=-104.6532"
	original := conExif(jpegDe(800, 600), gps)
	// Además, HTML pegado al final (archivo "políglota").
	original = append(original, []byte("<html><script>alert('xss')</script></html>")...)
	r, err := Procesar(bytes.NewReader(original), 8<<20)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(r.JPEG, []byte("GPSLatitude")) || bytes.Contains(r.JPEG, []byte("<script>")) || bytes.Contains(r.JPEG, []byte("Exif")) {
		t.Fatal("la foto procesada conserva metadatos o contenido escondido")
	}
	if _, err := jpeg.Decode(bytes.NewReader(r.JPEG)); err != nil {
		t.Fatal("el resultado no es un JPEG válido")
	}
}

func TestReduceTamano(t *testing.T) {
	r, err := Procesar(bytes.NewReader(jpegDe(3200, 2000)), 8<<20)
	if err != nil || r.Ancho != 1600 || r.Alto != 1000 {
		t.Fatalf("%dx%d %v", r.Ancho, r.Alto, err)
	}
}

// pngConDimensiones fabrica un PNG cuyo encabezado declara w×h (bomba de descompresión).
func pngConDimensiones(w, h uint32) []byte {
	var b bytes.Buffer
	_ = png.Encode(&b, image.NewRGBA(image.Rect(0, 0, 1, 1)))
	d := b.Bytes()
	binary.BigEndian.PutUint32(d[16:], w)
	binary.BigEndian.PutUint32(d[20:], h)
	binary.BigEndian.PutUint32(d[29:], crc32.ChecksumIEEE(d[12:29]))
	return d
}

func TestRechazos(t *testing.T) {
	casos := map[string][]byte{
		"html con extensión jpg": []byte("<html><body>hola</body></html>"),
		"svg":                    []byte(`<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>`),
		"gif":                    []byte("GIF89a" + string(make([]byte, 100))),
		"bomba png 50000x50000":  pngConDimensiones(50000, 50000),
		"demasiados píxeles":     pngConDimensiones(7999, 7999),
		"muy pequeña":            jpegDe(50, 50),
		"jpeg truncado":          jpegDe(800, 600)[:300],
	}
	for n, b := range casos {
		if _, err := Procesar(bytes.NewReader(b), 8<<20); err == nil {
			t.Errorf("aceptó %s", n)
		}
	}
	grande := append(jpegDe(800, 600), make([]byte, 9<<20)...)
	if _, err := Procesar(bytes.NewReader(grande), 8<<20); err == nil {
		t.Error("aceptó más de 8 MB")
	}
}
