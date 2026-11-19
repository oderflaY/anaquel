package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/mail"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

type Tienda struct {
	ID          int64    `json:"id"`
	Nombre      string   `json:"nombre"`
	Slug        string   `json:"slug"`
	Email       string   `json:"email,omitempty"`
	Telefono    string   `json:"telefono"`
	Direccion   string   `json:"direccion"`
	Lat         *float64 `json:"lat"`
	Lng         *float64 `json:"lng"`
	Giro        string   `json:"giro"`
	Descripcion string   `json:"descripcion"`
}

type ctxTienda struct{}

func tiendaDe(r *http.Request) int64 { return r.Context().Value(ctxTienda{}).(int64) }

func hashToken(t string) string {
	s := sha256.Sum256([]byte(t))
	return hex.EncodeToString(s[:])
}

func tokenAleatorio() string {
	b := make([]byte, 24)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// conTienda exige "Authorization: Bearer <token>" y deja el id de la tienda en el contexto.
func (a *App) conTienda(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if tok == "" {
			responderError(w, 401, "Inicia sesión")
			return
		}
		var id int64
		err := a.db.QueryRow("SELECT tienda_id FROM sesiones WHERE token_hash = ?", hashToken(tok)).Scan(&id)
		if err != nil {
			responderError(w, 401, "Tu sesión expiró, vuelve a entrar")
			return
		}
		h(w, r.WithContext(context.WithValue(r.Context(), ctxTienda{}, id)))
	}
}

// normalizar: minúsculas y sin acentos, para comparar palabras ("Huevo" = "huevó").
func normalizar(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	return strings.NewReplacer("á", "a", "é", "e", "í", "i", "ó", "o", "ú", "u", "ü", "u", "ñ", "n").Replace(s)
}

func slugDe(nombre string) string {
	var b strings.Builder
	guion := false
	for _, c := range normalizar(nombre) {
		if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') {
			b.WriteRune(c)
			guion = false
		} else if !guion && b.Len() > 0 {
			b.WriteByte('-')
			guion = true
		}
	}
	s := strings.Trim(b.String(), "-")
	if len(s) > 40 {
		s = strings.Trim(s[:40], "-")
	}
	if s == "" {
		s = "tienda"
	}
	return s
}

func (a *App) slugLibre(base string) string {
	s := base
	for i := 2; ; i++ {
		var n int
		a.db.QueryRow("SELECT COUNT(*) FROM tiendas WHERE slug = ?", s).Scan(&n)
		if n == 0 {
			return s
		}
		s = fmt.Sprintf("%s-%d", base, i)
	}
}

type datosRegistro struct {
	Nombre      string   `json:"nombre"`
	Email       string   `json:"email"`
	Password    string   `json:"password"`
	Telefono    string   `json:"telefono"`
	Direccion   string   `json:"direccion"`
	Lat         *float64 `json:"lat"`
	Lng         *float64 `json:"lng"`
	Giro        string   `json:"giro"`
	Descripcion string   `json:"descripcion"`
}

func (a *App) crearSesion(tiendaID int64) (string, error) {
	tok := tokenAleatorio()
	_, err := a.db.Exec("INSERT INTO sesiones (token_hash, tienda_id, creado) VALUES (?, ?, ?)", hashToken(tok), tiendaID, ahora())
	return tok, err
}

func (a *App) registro(w http.ResponseWriter, r *http.Request) {
	var d datosRegistro
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	d.Nombre = strings.TrimSpace(d.Nombre)
	d.Email = strings.TrimSpace(strings.ToLower(d.Email))
	switch {
	case d.Nombre == "":
		manejar(w, nil, fallo(400, "Escribe el nombre de la tienda"))
		return
	case func() bool { _, err := mail.ParseAddress(d.Email); return err != nil }():
		manejar(w, nil, fallo(400, "Correo inválido"))
		return
	case len(d.Password) < 6:
		manejar(w, nil, fallo(400, "La contraseña debe tener al menos 6 caracteres"))
		return
	}
	var existe int
	a.db.QueryRow("SELECT COUNT(*) FROM tiendas WHERE email = ?", d.Email).Scan(&existe)
	if existe > 0 {
		manejar(w, nil, fallo(409, "Ese correo ya tiene una tienda. Inicia sesión."))
		return
	}
	hash, _ := bcrypt.GenerateFromPassword([]byte(d.Password), bcrypt.DefaultCost)
	res, err := a.db.Exec(`INSERT INTO tiendas (nombre, slug, email, pass_hash, telefono, direccion, lat, lng, giro, creado)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		d.Nombre, a.slugLibre(slugDe(d.Nombre)), d.Email, string(hash), strings.TrimSpace(d.Telefono),
		strings.TrimSpace(d.Direccion), d.Lat, d.Lng, giroValido(d.Giro), ahora())
	if err != nil {
		manejar(w, nil, err)
		return
	}
	id, _ := res.LastInsertId()
	a.responderSesion(w, id)
}

func (a *App) login(w http.ResponseWriter, r *http.Request) {
	var d struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	var id int64
	var hash string
	err := a.db.QueryRow("SELECT id, pass_hash FROM tiendas WHERE email = ?", strings.TrimSpace(d.Email)).Scan(&id, &hash)
	if err != nil || bcrypt.CompareHashAndPassword([]byte(hash), []byte(d.Password)) != nil {
		manejar(w, nil, fallo(401, "Correo o contraseña incorrectos"))
		return
	}
	a.responderSesion(w, id)
}

func (a *App) responderSesion(w http.ResponseWriter, id int64) {
	tok, err := a.crearSesion(id)
	if err != nil {
		manejar(w, nil, err)
		return
	}
	t, err := a.tienda(id)
	manejar(w, map[string]any{"token": tok, "tienda": t}, err)
}

func (a *App) tienda(id int64) (Tienda, error) {
	var t Tienda
	err := a.db.QueryRow("SELECT id, nombre, slug, email, telefono, direccion, lat, lng, giro, descripcion FROM tiendas WHERE id = ?", id).
		Scan(&t.ID, &t.Nombre, &t.Slug, &t.Email, &t.Telefono, &t.Direccion, &t.Lat, &t.Lng, &t.Giro, &t.Descripcion)
	return t, err
}

func (a *App) yo(w http.ResponseWriter, r *http.Request) {
	t, err := a.tienda(tiendaDe(r))
	manejar(w, t, err)
}

func (a *App) actualizarYo(w http.ResponseWriter, r *http.Request) {
	var d datosRegistro
	if err := leerJSON(r, &d); err != nil {
		manejar(w, nil, err)
		return
	}
	if strings.TrimSpace(d.Nombre) == "" {
		manejar(w, nil, fallo(400, "Escribe el nombre de la tienda"))
		return
	}
	_, err := a.db.Exec("UPDATE tiendas SET nombre = ?, telefono = ?, direccion = ?, lat = ?, lng = ?, giro = ?, descripcion = ? WHERE id = ?",
		strings.TrimSpace(d.Nombre), strings.TrimSpace(d.Telefono), strings.TrimSpace(d.Direccion), d.Lat, d.Lng,
		giroValido(d.Giro), recortarTexto(strings.TrimSpace(d.Descripcion), 600), tiendaDe(r))
	if err != nil {
		manejar(w, nil, err)
		return
	}
	if len(d.Password) > 0 {
		if len(d.Password) < 6 {
			manejar(w, nil, fallo(400, "La contraseña debe tener al menos 6 caracteres"))
			return
		}
		hash, _ := bcrypt.GenerateFromPassword([]byte(d.Password), bcrypt.DefaultCost)
		a.db.Exec("UPDATE tiendas SET pass_hash = ? WHERE id = ?", string(hash), tiendaDe(r))
	}
	t, err := a.tienda(tiendaDe(r))
	manejar(w, t, err)
}

// nulo convierte "" en NULL para columnas opcionales.
func nulo(s string) sql.NullString { return sql.NullString{String: s, Valid: s != ""} }
