package acceso

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"strings"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/bcrypt"
)

// Argon2id con los parámetros mínimos que recomienda OWASP (19 MiB, t=2, p=1).
// Un semáforo limita cuántos hashes corren a la vez: así un ataque de muchos
// logins simultáneos no agota la memoria del servidor.
const (
	argonMem    = 19 * 1024
	argonTiempo = 2
	argonHilos  = 1
	argonLargo  = 32
	saltLargo   = 16
)

var semaforoHash = make(chan struct{}, 4)

func hashArgon(pass string) (string, error) {
	salt := make([]byte, saltLargo)
	if _, err := io.ReadFull(rand.Reader, salt); err != nil {
		return "", err
	}
	semaforoHash <- struct{}{}
	h := argon2.IDKey([]byte(pass), salt, argonTiempo, argonMem, argonHilos, argonLargo)
	<-semaforoHash
	e := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s", argon2.Version, argonMem, argonTiempo, argonHilos,
		e.EncodeToString(salt), e.EncodeToString(h)), nil
}

// verificarPass compara en tiempo constante. Acepta también hashes bcrypt de
// cuentas importadas del panel de Anaquel; en ese caso pide re-cifrar.
func verificarPass(guardado, pass string) (ok, recifrar bool) {
	if strings.HasPrefix(guardado, "$2a$") || strings.HasPrefix(guardado, "$2b$") || strings.HasPrefix(guardado, "$2y$") {
		semaforoHash <- struct{}{}
		err := bcrypt.CompareHashAndPassword([]byte(guardado), []byte(pass))
		<-semaforoHash
		return err == nil, err == nil
	}
	partes := strings.Split(guardado, "$")
	if len(partes) != 6 || partes[1] != "argon2id" {
		return false, false
	}
	var m uint32
	var t uint32
	var p uint8
	if _, err := fmt.Sscanf(partes[3], "m=%d,t=%d,p=%d", &m, &t, &p); err != nil || m > 256*1024 || t > 10 || p > 8 || p == 0 {
		return false, false
	}
	e := base64.RawStdEncoding
	salt, err1 := e.DecodeString(partes[4])
	esperado, err2 := e.DecodeString(partes[5])
	if err1 != nil || err2 != nil || len(esperado) == 0 {
		return false, false
	}
	semaforoHash <- struct{}{}
	h := argon2.IDKey([]byte(pass), salt, t, m, p, uint32(len(esperado)))
	<-semaforoHash
	ok = subtle.ConstantTimeCompare(h, esperado) == 1
	return ok, ok && (m != argonMem || t != argonTiempo || p != argonHilos)
}

// hashSenuelo se calcula al arrancar. Cuando el correo no existe se verifica
// contra él, para que la respuesta tarde lo mismo y no delate qué correos
// tienen cuenta.
var hashSenuelo, _ = hashArgon("senuelo-" + base64.RawStdEncoding.EncodeToString([]byte("anaquel")))

// Contraseñas muy comunes (y sus variantes en español). Se rechazan.
var comunes = map[string]bool{}

func init() {
	for _, p := range strings.Fields(`123456789 1234567890 12345678910 qwertyuiop qwerty1234 password1 password123
		contraseña contrasena contraseña1 contrasena1 contraseña123 contrasena123 1q2w3e4r5t 1qaz2wsx3edc
		abcdefghij abc1234567 iloveyou12 tequiero123 teamo12345 mexico1234 mexico2024 mexico2025 mexico2026
		durango123 durango2024 durango2025 durango2026 anaquel123 anaquel1234 mercado123 tienda1234 negocio123
		0000000000 1111111111 1234512345 9876543210 aaaaaaaaaa 1234567890a a123456789 passw0rd12 administrador
		admin12345 admin123456 bienvenido1 bienvenido123 holahola12 hola123456 america123 chivas1234 superman12
		princesa12 futbol1234 dragon1234 monkey1234 letmein123 welcome123 sunshine12 football12 baseball12`) {
		comunes[p] = true
	}
}

// validarPass aplica la política: 10 a 128 caracteres, no común, sin el correo.
func validarPass(pass, email string) error {
	n := utf8.RuneCountInString(pass)
	switch {
	case n < 10:
		return errors.New("La contraseña debe tener al menos 10 caracteres")
	case len(pass) > 128:
		return errors.New("La contraseña es demasiado larga (máximo 128)")
	case comunes[strings.ToLower(pass)]:
		return errors.New("Esa contraseña es muy común. Elige otra")
	}
	if usuario, _, _ := strings.Cut(email, "@"); len(usuario) >= 4 && strings.Contains(strings.ToLower(pass), usuario) {
		return errors.New("La contraseña no debe contener tu correo")
	}
	distintos := map[rune]bool{}
	for _, c := range pass {
		distintos[c] = true
	}
	if len(distintos) < 5 {
		return errors.New("La contraseña repite demasiado los mismos caracteres")
	}
	return nil
}
