package reels

import (
	"bytes"
	"encoding/binary"
	"math/rand"
	"testing"
)

func nuevaCaja(tipo string, contenido ...[]byte) []byte {
	c := bytes.Join(contenido, nil)
	b := make([]byte, 8, 8+len(c))
	binary.BigEndian.PutUint32(b, uint32(8+len(c)))
	copy(b[4:], tipo)
	return append(b, c...)
}

// mp4 arma un MP4 mínimo: ftyp + moov(mvhd + trak(tkhd + mdia/minf/stbl/stsd)) + mdat.
func mp4(segundos int, codec string, ancho, alto int) []byte {
	mvhd := make([]byte, 100)
	binary.BigEndian.PutUint32(mvhd[12:], 1000)                  // escala
	binary.BigEndian.PutUint32(mvhd[16:], uint32(segundos*1000)) // duración
	tkhd := make([]byte, 84)
	binary.BigEndian.PutUint32(tkhd[76:], uint32(ancho)<<16)
	binary.BigEndian.PutUint32(tkhd[80:], uint32(alto)<<16)
	stsd := append(make([]byte, 8), nuevaCaja(codec, make([]byte, 70))...)
	return bytes.Join([][]byte{
		nuevaCaja("ftyp", []byte("isom"), make([]byte, 4), []byte("isomavc1")),
		nuevaCaja("moov", nuevaCaja("mvhd", mvhd), nuevaCaja("trak", nuevaCaja("tkhd", tkhd), nuevaCaja("mdia", nuevaCaja("minf", nuevaCaja("stbl", nuevaCaja("stsd", stsd)))))),
		nuevaCaja("mdat", make([]byte, 64)),
	}, nil)
}

func analizar(b []byte) (infoMP4, error) { return analizarMP4(bytes.NewReader(b), int64(len(b))) }

func TestMP4Valido(t *testing.T) {
	i, err := analizar(mp4(8, "avc1", 540, 960))
	if err != nil || i.DuracionMs != 8000 || i.Ancho != 540 || i.Alto != 960 {
		t.Fatalf("%+v %v", i, err)
	}
}

func TestVideosDeDemo(t *testing.T) {
	for _, d := range demos {
		b, err := demoFS.ReadFile("demo/" + d.archivo)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := analizar(b); err != nil {
			t.Errorf("%s: %v", d.archivo, err)
		}
	}
}

func TestMP4Rechazados(t *testing.T) {
	html := []byte("<!doctype html><script>alert(1)</script>")
	zip := append([]byte("PK\x03\x04"), make([]byte, 100)...)
	exe := append([]byte("MZ"), make([]byte, 200)...)
	casos := map[string][]byte{
		"html renombrado":     html,
		"zip":                 zip,
		"exe":                 exe,
		"vacío":               {},
		"muy largo (90 s)":    mp4(90, "avc1", 540, 960),
		"cero segundos":       mp4(0, "avc1", 540, 960),
		"códec no soportado":  mp4(8, "mp4v", 540, 960),
		"sin dimensiones":     mp4(8, "avc1", 0, 0),
		"dimensiones enormes": mp4(8, "avc1", 20000, 20000),
		"ftyp con html":       append(mp4(8, "avc1", 540, 960)[:8], html...),
		"recortado":           mp4(8, "avc1", 540, 960)[:60],
	}
	sinMoov := bytes.Join([][]byte{nuevaCaja("ftyp", []byte("isom"), make([]byte, 4)), nuevaCaja("mdat", make([]byte, 10))}, nil)
	casos["sin moov"] = sinMoov
	// Caja con tamaño mentiroso (más grande que el archivo).
	mentira := mp4(8, "avc1", 540, 960)
	binary.BigEndian.PutUint32(mentira[len(mentira)-72:], 1<<30)
	casos["tamaño mentiroso"] = mentira
	for n, b := range casos {
		if _, err := analizar(b); err == nil {
			t.Errorf("aceptó %s", n)
		}
	}
}

// Fuzz sencillo: bytes al azar (y MP4 válidos mutados) nunca deben provocar
// pánico ni cuelgues.
func TestMP4Aleatorio(t *testing.T) {
	r := rand.New(rand.NewSource(1))
	base := mp4(8, "avc1", 540, 960)
	for i := range 5000 {
		var b []byte
		if i%2 == 0 {
			b = make([]byte, r.Intn(400))
			r.Read(b)
		} else {
			b = append([]byte(nil), base...)
			for range 1 + r.Intn(6) {
				b[r.Intn(len(b))] = byte(r.Intn(256))
			}
		}
		_, _ = analizar(b)
	}
}
