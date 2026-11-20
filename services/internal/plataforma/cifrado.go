package plataforma

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
)

// Cifrador protege datos personales en la base (teléfono, dirección, correo)
// con AES-256-GCM. La llave vive fuera de la base: un respaldo robado de
// Postgres no revela esos datos. Formato: versión(1) | nonce(12) | cifrado.
// El "contexto" (p. ej. "cuentas.telefono") se autentica: un valor cifrado no
// se puede copiar a otra columna sin que falle al descifrar.
type Cifrador struct {
	aead cipher.AEAD
}

const versionCifrado = 1

func NuevoCifrador(llave []byte) (*Cifrador, error) {
	if len(llave) != 32 {
		return nil, errors.New("la llave de cifrado debe medir 32 bytes")
	}
	b, err := aes.NewCipher(llave)
	if err != nil {
		return nil, err
	}
	g, err := cipher.NewGCM(b)
	if err != nil {
		return nil, err
	}
	return &Cifrador{aead: g}, nil
}

func (c *Cifrador) Cifrar(contexto, texto string) ([]byte, error) {
	nonce := make([]byte, c.aead.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	out := append([]byte{versionCifrado}, nonce...)
	return c.aead.Seal(out, nonce, []byte(texto), []byte(contexto)), nil
}

func (c *Cifrador) Descifrar(contexto string, datos []byte) (string, error) {
	n := c.aead.NonceSize()
	if len(datos) < 1+n || datos[0] != versionCifrado {
		return "", errors.New("dato cifrado inválido")
	}
	t, err := c.aead.Open(nil, datos[1:1+n], datos[1+n:], []byte(contexto))
	if err != nil {
		return "", errors.New("no se pudo descifrar")
	}
	return string(t), nil
}

// Indice calcula un "índice ciego": HMAC del valor normalizado. Permite buscar
// por correo (WHERE email_idx = …) sin guardar el correo en claro.
type Indice struct{ llave []byte }

func NuevoIndice(llave []byte) (*Indice, error) {
	if len(llave) < 32 {
		return nil, errors.New("la llave del índice debe medir al menos 32 bytes")
	}
	return &Indice{llave: llave}, nil
}

func (i *Indice) De(contexto, valor string) []byte {
	m := hmac.New(sha256.New, i.llave)
	m.Write([]byte(contexto))
	m.Write([]byte{0})
	m.Write([]byte(valor))
	return m.Sum(nil)
}

// Env lee una variable con valor por omisión.
func Env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// Secreto lee un secreto de K o del archivo en K_FILE (Docker secrets).
func Secreto(k string) (string, error) {
	if v := os.Getenv(k); v != "" {
		return v, nil
	}
	if f := os.Getenv(k + "_FILE"); f != "" {
		b, err := os.ReadFile(f)
		if err != nil {
			return "", fmt.Errorf("%s_FILE: %w", k, err)
		}
		return strings.TrimSpace(string(b)), nil
	}
	return "", fmt.Errorf("falta la variable %s (o %s_FILE)", k, k)
}

// Llave lee un secreto en base64 (estándar o URL) y exige un largo mínimo.
func Llave(k string, minimo int) ([]byte, error) {
	s, err := Secreto(k)
	if err != nil {
		return nil, err
	}
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		if b, err := enc.DecodeString(s); err == nil {
			if len(b) < minimo {
				return nil, fmt.Errorf("%s debe tener al menos %d bytes", k, minimo)
			}
			return b, nil
		}
	}
	return nil, fmt.Errorf("%s debe ir en base64", k)
}
