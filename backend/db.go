package main

import (
	"database/sql"
	"fmt"

	_ "modernc.org/sqlite"
)

// Esquema completo. Cada entrada de migraciones se aplica una sola vez
// (PRAGMA user_version guarda cuántas van).
var migraciones = []string{
	`
CREATE TABLE tiendas (
	id         INTEGER PRIMARY KEY AUTOINCREMENT,
	nombre     TEXT NOT NULL,
	slug       TEXT NOT NULL UNIQUE,
	email      TEXT NOT NULL UNIQUE COLLATE NOCASE,
	pass_hash  TEXT NOT NULL,
	telefono   TEXT NOT NULL DEFAULT '',
	direccion  TEXT NOT NULL DEFAULT '',
	lat        REAL,
	lng        REAL,
	creado     INTEGER NOT NULL
);
CREATE TABLE sesiones (
	token_hash TEXT PRIMARY KEY,
	tienda_id  INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	creado     INTEGER NOT NULL
);
-- Productores locales: directorio público (no pertenecen a una tienda).
CREATE TABLE productores (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	nombre      TEXT NOT NULL,
	telefono    TEXT NOT NULL,
	productos   TEXT NOT NULL,          -- "huevo, queso, nopal" (lo que produce)
	descripcion TEXT NOT NULL DEFAULT '',
	lugar       TEXT NOT NULL DEFAULT '', -- colonia / rancho / municipio
	lat         REAL NOT NULL,
	lng         REAL NOT NULL,
	token_hash  TEXT NOT NULL,          -- para que el productor edite su ficha
	creado      INTEGER NOT NULL
);
-- Proveedores de cada tienda (el de refrescos, el de abarrotes…).
CREATE TABLE proveedores (
	id         INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_id  INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	nombre     TEXT NOT NULL,
	telefono   TEXT NOT NULL DEFAULT '',
	surte      TEXT NOT NULL DEFAULT '', -- palabras de lo que surte
	creado     INTEGER NOT NULL
);
CREATE TABLE productos (
	id           INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_id    INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	codigo       TEXT NOT NULL DEFAULT '',
	nombre       TEXT NOT NULL,
	categoria    TEXT NOT NULL DEFAULT '',
	precio_c     INTEGER NOT NULL DEFAULT 0,
	stock        INTEGER,                 -- NULL = no se lleva conteo
	stock_min    INTEGER NOT NULL DEFAULT 0,
	proveedor_id INTEGER REFERENCES proveedores(id) ON DELETE SET NULL,
	productor_id INTEGER REFERENCES productores(id) ON DELETE SET NULL,
	actualizado  INTEGER NOT NULL
);
CREATE INDEX idx_productos_tienda ON productos(tienda_id, nombre);
CREATE TABLE anaqueles (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_id   INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	nombre      TEXT NOT NULL,
	foto_ref    TEXT NOT NULL,            -- archivo de la foto del anaquel LLENO
	ultima_foto TEXT,
	ultima_en   INTEGER,
	creado      INTEGER NOT NULL
);
-- Rectángulo (0..1) dentro de la foto de referencia donde va un producto.
CREATE TABLE zonas (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	anaquel_id  INTEGER NOT NULL REFERENCES anaqueles(id) ON DELETE CASCADE,
	producto_id INTEGER REFERENCES productos(id) ON DELETE SET NULL,
	x REAL NOT NULL, y REAL NOT NULL, w REAL NOT NULL, h REAL NOT NULL,
	nivel       TEXT NOT NULL DEFAULT 'lleno', -- lleno | poco | vacio
	score       REAL NOT NULL DEFAULT 1,
	actualizado INTEGER
);
CREATE INDEX idx_zonas_anaquel ON zonas(anaquel_id);
CREATE TABLE fotos (
	id         INTEGER PRIMARY KEY AUTOINCREMENT,
	anaquel_id INTEGER NOT NULL REFERENCES anaqueles(id) ON DELETE CASCADE,
	archivo    TEXT NOT NULL,
	metodo     TEXT NOT NULL,             -- ia | comparacion
	faltantes  INTEGER NOT NULL,
	creado     INTEGER NOT NULL
);
CREATE TABLE pedidos (
	id           INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_id    INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	proveedor_id INTEGER REFERENCES proveedores(id) ON DELETE SET NULL,
	productor_id INTEGER REFERENCES productores(id) ON DELETE SET NULL,
	destino      TEXT NOT NULL,           -- nombre copiado al crear
	telefono     TEXT NOT NULL DEFAULT '',
	estado       TEXT NOT NULL,           -- enviado | confirmado | llego | cancelado
	nota         TEXT NOT NULL DEFAULT '',
	creado       INTEGER NOT NULL,
	actualizado  INTEGER NOT NULL,
	llego_auto   INTEGER NOT NULL DEFAULT 0 -- 1 = lo confirmó una foto
);
CREATE INDEX idx_pedidos_tienda ON pedidos(tienda_id, estado);
CREATE TABLE pedido_items (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	pedido_id   INTEGER NOT NULL REFERENCES pedidos(id) ON DELETE CASCADE,
	producto_id INTEGER REFERENCES productos(id) ON DELETE SET NULL,
	nombre      TEXT NOT NULL,
	cantidad    INTEGER NOT NULL CHECK (cantidad > 0),
	recibido    INTEGER NOT NULL DEFAULT 0
);
`,
}

func abrirDB(ruta string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", "file:"+ruta+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)")
	if err != nil {
		return nil, err
	}
	// SQLite: un solo escritor; con una conexión no hay "database is locked" internos.
	db.SetMaxOpenConns(1)
	var version int
	if err := db.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		return nil, err
	}
	for i := version; i < len(migraciones); i++ {
		tx, err := db.Begin()
		if err != nil {
			return nil, err
		}
		if _, err := tx.Exec(migraciones[i]); err != nil {
			tx.Rollback()
			return nil, fmt.Errorf("migración %d: %w", i+1, err)
		}
		if _, err := tx.Exec(fmt.Sprintf("PRAGMA user_version = %d", i+1)); err != nil {
			tx.Rollback()
			return nil, err
		}
		if err := tx.Commit(); err != nil {
			return nil, err
		}
	}
	return db, nil
}
