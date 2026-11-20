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
	// 2: red de comercio local (vitrina, publicidad, solicitudes, tratos),
	// kárdex de inventario al estilo MatObra y el asesor del negocio.
	`
ALTER TABLE tiendas ADD COLUMN giro TEXT NOT NULL DEFAULT 'abarrotes';
ALTER TABLE tiendas ADD COLUMN descripcion TEXT NOT NULL DEFAULT '';
ALTER TABLE productos ADD COLUMN publicado INTEGER NOT NULL DEFAULT 0;   -- visible en la red
ALTER TABLE productos ADD COLUMN precio_mayoreo_c INTEGER NOT NULL DEFAULT 0;
ALTER TABLE productos ADD COLUMN minimo_mayoreo INTEGER NOT NULL DEFAULT 0;
ALTER TABLE productos ADD COLUMN unidad TEXT NOT NULL DEFAULT 'pza';
ALTER TABLE productos ADD COLUMN descripcion TEXT NOT NULL DEFAULT '';
-- Kárdex: cada cambio de existencia deja un renglón. cantidad lleva signo.
CREATE TABLE movimientos (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_id   INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
	tipo        TEXT NOT NULL,   -- entrada | salida | venta | ajuste | trato_entrada | trato_salida
	cantidad    INTEGER NOT NULL,
	saldo       INTEGER NOT NULL,
	nota        TEXT NOT NULL DEFAULT '',
	trato_id    INTEGER,
	creado      INTEGER NOT NULL
);
CREATE INDEX idx_mov_producto ON movimientos(producto_id, creado);
CREATE INDEX idx_mov_tienda ON movimientos(tienda_id, creado);
-- Publicidad: lo que una tienda, proveedor o productor ofrece (incluye servicios).
CREATE TABLE anuncios (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_id   INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	tipo        TEXT NOT NULL DEFAULT 'producto', -- producto | servicio | promocion
	titulo      TEXT NOT NULL,
	texto       TEXT NOT NULL DEFAULT '',
	precio      TEXT NOT NULL DEFAULT '',          -- libre: "$45 la pieza desde 50"
	activo      INTEGER NOT NULL DEFAULT 1,
	creado      INTEGER NOT NULL
);
-- "Busco 1000 gorras": lo que una tienda necesita conseguir.
CREATE TABLE solicitudes (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_id   INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	que         TEXT NOT NULL,
	cantidad    INTEGER NOT NULL DEFAULT 0,
	detalle     TEXT NOT NULL DEFAULT '',
	estado      TEXT NOT NULL DEFAULT 'abierta',  -- abierta | cerrada
	por_asesor  INTEGER NOT NULL DEFAULT 0,
	creado      INTEGER NOT NULL
);
-- Negociación entre dos tiendas.
CREATE TABLE tratos (
	id            INTEGER PRIMARY KEY AUTOINCREMENT,
	comprador_id  INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	vendedor_id   INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	producto_id   INTEGER REFERENCES productos(id) ON DELETE SET NULL,
	anuncio_id    INTEGER REFERENCES anuncios(id) ON DELETE SET NULL,
	solicitud_id  INTEGER REFERENCES solicitudes(id) ON DELETE SET NULL,
	titulo        TEXT NOT NULL,
	cantidad      INTEGER NOT NULL,
	precio_c      INTEGER NOT NULL DEFAULT 0,   -- por unidad
	propuso_id    INTEGER NOT NULL,             -- quién hizo la última propuesta
	estado        TEXT NOT NULL DEFAULT 'negociando', -- negociando | aceptado | entregado | cancelado
	creado        INTEGER NOT NULL,
	actualizado   INTEGER NOT NULL
);
CREATE INDEX idx_tratos_comprador ON tratos(comprador_id, actualizado);
CREATE INDEX idx_tratos_vendedor ON tratos(vendedor_id, actualizado);
CREATE TABLE trato_mensajes (
	id         INTEGER PRIMARY KEY AUTOINCREMENT,
	trato_id   INTEGER NOT NULL REFERENCES tratos(id) ON DELETE CASCADE,
	tienda_id  INTEGER,                 -- NULL = mensaje del sistema
	texto      TEXT NOT NULL,
	cantidad   INTEGER,                 -- con cantidad/precio es una contraoferta
	precio_c   INTEGER,
	creado     INTEGER NOT NULL
);
CREATE TABLE avisos (
	id         INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_id  INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	clave      TEXT,                    -- evita avisar dos veces lo mismo
	tipo       TEXT NOT NULL,           -- encontrado | interesado | trato | sistema
	texto      TEXT NOT NULL,
	enlace     TEXT NOT NULL DEFAULT '',
	leido      INTEGER NOT NULL DEFAULT 0,
	creado     INTEGER NOT NULL,
	UNIQUE (tienda_id, clave)
);
CREATE TABLE asesor_mensajes (
	id         INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_id  INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	rol        TEXT NOT NULL,           -- user | assistant
	texto      TEXT NOT NULL,
	extra      TEXT NOT NULL DEFAULT '{}', -- tarjetas y pasos (JSON)
	creado     INTEGER NOT NULL
);
`,
	// 3: bodega 3D. El plano (paredes, puerta, muebles) es un JSON por tienda y
	// cada producto guarda en qué mueble, lado, entrepaño y posición está.
	`
CREATE TABLE planos (
	tienda_id   INTEGER PRIMARY KEY REFERENCES tiendas(id) ON DELETE CASCADE,
	datos       TEXT NOT NULL,
	actualizado INTEGER NOT NULL
);
ALTER TABLE productos ADD COLUMN lugar_mueble TEXT NOT NULL DEFAULT '';
ALTER TABLE productos ADD COLUMN lugar_lado INTEGER NOT NULL DEFAULT 0;
ALTER TABLE productos ADD COLUMN lugar_nivel INTEGER NOT NULL DEFAULT 0;
ALTER TABLE productos ADD COLUMN lugar_pos REAL NOT NULL DEFAULT 0;
`,
	// 4: chat entre tiendas. Cada par de tiendas tiene una conversación; los
	// mensajes pueden ser texto, un mini catálogo o un pedido (tratos).
	`
CREATE TABLE chats (
	id          INTEGER PRIMARY KEY AUTOINCREMENT,
	tienda_a    INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	tienda_b    INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	actualizado INTEGER NOT NULL,
	leido_a     INTEGER NOT NULL DEFAULT 0, -- último mensaje que leyó cada tienda
	leido_b     INTEGER NOT NULL DEFAULT 0,
	UNIQUE (tienda_a, tienda_b)
);
CREATE TABLE chat_mensajes (
	id         INTEGER PRIMARY KEY AUTOINCREMENT,
	chat_id    INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
	tienda_id  INTEGER,                         -- NULL = mensaje del sistema
	tipo       TEXT NOT NULL DEFAULT 'texto',   -- texto | catalogo | pedido | sistema
	texto      TEXT NOT NULL DEFAULT '',
	datos      TEXT NOT NULL DEFAULT '',
	creado     INTEGER NOT NULL
);
CREATE INDEX idx_chat_mensajes ON chat_mensajes(chat_id, id);
`,
	// 6: perfiles completos de tienda (portada, horario, reparto, crédito…),
	// foto de producto y lectura de notificaciones del teléfono.
	`
ALTER TABLE productos ADD COLUMN foto TEXT NOT NULL DEFAULT '';
ALTER TABLE tiendas ADD COLUMN portada TEXT NOT NULL DEFAULT '';
ALTER TABLE tiendas ADD COLUMN horario TEXT NOT NULL DEFAULT '';
ALTER TABLE tiendas ADD COLUMN entrega TEXT NOT NULL DEFAULT '';
ALTER TABLE tiendas ADD COLUMN pedido_minimo_c INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tiendas ADD COLUMN credito TEXT NOT NULL DEFAULT '';
ALTER TABLE tiendas ADD COLUMN pagos TEXT NOT NULL DEFAULT '';
ALTER TABLE tiendas ADD COLUMN zona TEXT NOT NULL DEFAULT '';
ALTER TABLE tiendas ADD COLUMN desde INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tiendas ADD COLUMN clientes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tiendas ADD COLUMN verificada INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tiendas ADD COLUMN calificacion REAL NOT NULL DEFAULT 0;
ALTER TABLE tiendas ADD COLUMN resenas INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tiendas ADD COLUMN etiquetas TEXT NOT NULL DEFAULT '';
`,
	`
-- Mayoreo con audiencia: 'todos' (toda la red) o 'elegidos' (solo las tiendas de producto_audiencia).
ALTER TABLE productos ADD COLUMN audiencia TEXT NOT NULL DEFAULT 'todos';
CREATE TABLE producto_audiencia (
	producto_id INTEGER NOT NULL REFERENCES productos(id) ON DELETE CASCADE,
	tienda_id   INTEGER NOT NULL REFERENCES tiendas(id) ON DELETE CASCADE,
	PRIMARY KEY (producto_id, tienda_id)
);
CREATE INDEX producto_audiencia_tienda ON producto_audiencia(tienda_id);
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
