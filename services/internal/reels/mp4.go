package reels

import (
	"encoding/binary"
	"errors"
	"io"
)

// Validación estructural de MP4: no confiamos en la extensión ni en el
// Content-Type. Se recorren las "cajas" del archivo y se exige:
//   - que empiece con ftyp y una marca conocida,
//   - que tenga moov (índice) y mdat (datos),
//   - una pista de video H.264/H.265 (lo que reproducen los navegadores),
//   - y una duración real (mvhd) de 1 a 60 segundos.
// Cualquier otra cosa (HTML, ZIP, ejecutables renombrados) se rechaza.

var (
	ErrNoMP4    = errors.New("El video debe ser MP4 (H.264)")
	ErrDuracion = errors.New("El video debe durar entre 1 y 60 segundos")
	ErrSinVideo = errors.New("El archivo no tiene una pista de video compatible (H.264)")
)

type infoMP4 struct {
	DuracionMs int
	Ancho      int
	Alto       int
	Codec      string
}

var marcas = map[string]bool{"isom": true, "iso2": true, "iso4": true, "iso5": true, "iso6": true, "mp41": true, "mp42": true,
	"avc1": true, "M4V ": true, "dash": true, "qt  ": true, "MSNV": true}

type caja struct {
	tipo        string
	ini, inicio int64 // ini: inicio de la caja; inicio: inicio del contenido
	fin         int64
}

// cajas lista las cajas hijas dentro de [desde, hasta).
func cajas(r io.ReaderAt, desde, hasta int64) ([]caja, error) {
	var out []caja
	pos := desde
	for pos+8 <= hasta {
		var cab [16]byte
		if _, err := r.ReadAt(cab[:8], pos); err != nil {
			return nil, ErrNoMP4
		}
		tam := int64(binary.BigEndian.Uint32(cab[:4]))
		tipo := string(cab[4:8])
		inicio := pos + 8
		switch tam {
		case 1:
			if _, err := r.ReadAt(cab[8:16], pos+8); err != nil {
				return nil, ErrNoMP4
			}
			t := binary.BigEndian.Uint64(cab[8:16])
			if t > 1<<40 {
				return nil, ErrNoMP4
			}
			tam = int64(t)
			inicio = pos + 16
		case 0:
			tam = hasta - pos
		}
		if tam < inicio-pos || pos+tam > hasta {
			return nil, ErrNoMP4
		}
		for _, c := range tipo {
			if c < 0x20 || c > 0x7e {
				return nil, ErrNoMP4
			}
		}
		out = append(out, caja{tipo: tipo, ini: pos, inicio: inicio, fin: pos + tam})
		if len(out) > 10_000 {
			return nil, ErrNoMP4
		}
		pos += tam
	}
	return out, nil
}

func hija(r io.ReaderAt, padre caja, tipo string) (caja, bool) {
	hs, err := cajas(r, padre.inicio, padre.fin)
	if err != nil {
		return caja{}, false
	}
	for _, h := range hs {
		if h.tipo == tipo {
			return h, true
		}
	}
	return caja{}, false
}

func leer(r io.ReaderAt, pos int64, n int) ([]byte, error) {
	b := make([]byte, n)
	if _, err := r.ReadAt(b, pos); err != nil {
		return nil, ErrNoMP4
	}
	return b, nil
}

func analizarMP4(r io.ReaderAt, tam int64) (infoMP4, error) {
	var info infoMP4
	top, err := cajas(r, 0, tam)
	if err != nil || len(top) == 0 || top[0].tipo != "ftyp" {
		return info, ErrNoMP4
	}
	marca, err := leer(r, top[0].inicio, 4)
	if err != nil || !marcas[string(marca)] {
		return info, ErrNoMP4
	}
	var moov caja
	var hayMoov, hayMdat bool
	for _, c := range top {
		switch c.tipo {
		case "moov":
			moov, hayMoov = c, true
		case "mdat":
			hayMdat = true
		}
	}
	if !hayMoov || !hayMdat {
		return info, ErrNoMP4
	}
	mvhd, ok := hija(r, moov, "mvhd")
	if !ok {
		return info, ErrNoMP4
	}
	v, err := leer(r, mvhd.inicio, 1)
	if err != nil {
		return info, err
	}
	var escala, dur uint64
	if v[0] == 1 {
		b, err := leer(r, mvhd.inicio+4+16, 12)
		if err != nil {
			return info, err
		}
		escala, dur = uint64(binary.BigEndian.Uint32(b[:4])), binary.BigEndian.Uint64(b[4:12])
	} else {
		b, err := leer(r, mvhd.inicio+4+8, 8)
		if err != nil {
			return info, err
		}
		escala, dur = uint64(binary.BigEndian.Uint32(b[:4])), uint64(binary.BigEndian.Uint32(b[4:8]))
	}
	if escala == 0 {
		return info, ErrNoMP4
	}
	ms := dur * 1000 / escala
	if ms < 1000 || ms > 61_000 {
		return info, ErrDuracion
	}
	info.DuracionMs = int(ms)

	traks, err := cajas(r, moov.inicio, moov.fin)
	if err != nil {
		return info, err
	}
	for _, t := range traks {
		if t.tipo != "trak" {
			continue
		}
		codec := codecDe(r, t)
		if codec != "avc1" && codec != "avc3" && codec != "hvc1" && codec != "hev1" {
			continue
		}
		info.Codec = codec
		if tkhd, ok := hija(r, t, "tkhd"); ok {
			v, _ := leer(r, tkhd.inicio, 1)
			// version/flags (4) + campos de tiempo/id + reservados + capa/volumen + matriz (36) → ancho, alto
			off := int64(4 + 72) // versión 0 (campos de 32 bits)
			if len(v) == 1 && v[0] == 1 {
				off = 4 + 84 // versión 1 (fechas y duración de 64 bits)
			}
			if b, err := leer(r, tkhd.inicio+off, 8); err == nil {
				info.Ancho = int(binary.BigEndian.Uint32(b[:4]) >> 16)
				info.Alto = int(binary.BigEndian.Uint32(b[4:]) >> 16)
			}
		}
		break
	}
	if info.Codec == "" {
		return info, ErrSinVideo
	}
	if info.Ancho <= 0 || info.Alto <= 0 || info.Ancho > 4096 || info.Alto > 4096 {
		return info, ErrSinVideo
	}
	return info, nil
}

// codecDe baja por trak/mdia/minf/stbl/stsd y lee el tipo de la primera entrada.
func codecDe(r io.ReaderAt, trak caja) string {
	c := trak
	for _, t := range []string{"mdia", "minf", "stbl", "stsd"} {
		var ok bool
		if c, ok = hija(r, c, t); !ok {
			return ""
		}
	}
	// stsd: version/flags(4) + entradas(4) + primera caja (tamaño 4 + tipo 4)
	b, err := leer(r, c.inicio+8+4, 4)
	if err != nil {
		return ""
	}
	return string(b)
}
