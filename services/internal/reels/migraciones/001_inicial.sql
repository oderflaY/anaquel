-- Servicio de reels: videos cortos verticales ligados a un producto.
CREATE TABLE reels (
	id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	cuenta_id             BIGINT NOT NULL,
	vendedor_nombre       TEXT NOT NULL,
	vendedor_slug         TEXT NOT NULL,
	publicacion_id        BIGINT,
	publicacion_titulo    TEXT,
	publicacion_precio_c  BIGINT,
	publicacion_unidad    TEXT,
	archivo               TEXT NOT NULL UNIQUE CHECK (archivo ~ '^[a-f0-9]{32}\.mp4$'),
	bytes                 BIGINT NOT NULL CHECK (bytes > 0),
	duracion_ms           INTEGER NOT NULL CHECK (duracion_ms BETWEEN 1000 AND 61000),
	ancho                 INTEGER NOT NULL,
	alto                  INTEGER NOT NULL,
	descripcion           TEXT NOT NULL CHECK (char_length(descripcion) BETWEEN 10 AND 300),
	estado                TEXT NOT NULL DEFAULT 'activo' CHECK (estado IN ('activo', 'oculto')),
	creado                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reels_feed ON reels(estado, id DESC);
CREATE INDEX reels_cuenta ON reels(cuenta_id, id DESC);
