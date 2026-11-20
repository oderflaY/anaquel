-- Servicio de mercado: perfiles de negocios, publicaciones, fotos y órdenes.

-- Copia local (sin datos personales) del perfil que vive en "acceso".
CREATE TABLE perfiles (
	cuenta_id    BIGINT PRIMARY KEY,
	nombre       TEXT NOT NULL,
	slug         TEXT NOT NULL UNIQUE,
	giro         TEXT NOT NULL DEFAULT 'otro',
	descripcion  TEXT NOT NULL DEFAULT '',
	colonia      TEXT NOT NULL DEFAULT '',
	lat          DOUBLE PRECISION,
	lng          DOUBLE PRECISION,
	tienda_slug  TEXT,
	vende        BOOLEAN NOT NULL DEFAULT false,
	actualizado  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE categorias (
	clave   TEXT PRIMARY KEY,
	nombre  TEXT NOT NULL,
	icono   TEXT NOT NULL,
	orden   INTEGER NOT NULL
);
INSERT INTO categorias (clave, nombre, icono, orden) VALUES
	('abarrotes', 'Abarrotes', 'canasta', 1),
	('hecho-en-durango', 'Hecho en Durango', 'hoja', 2),
	('ropa', 'Ropa y calzado', 'camisa', 3),
	('mayoreo', 'Mayoreo', 'caja', 4),
	('papeleria', 'Papelería', 'lapiz', 5),
	('hogar', 'Hogar y ferretería', 'casa', 6),
	('servicios', 'Servicios', 'herramienta', 7),
	('otros', 'Otros', 'etiqueta', 8);

CREATE TABLE publicaciones (
	id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	vendedor_id  BIGINT NOT NULL REFERENCES perfiles(cuenta_id) ON DELETE CASCADE,
	titulo       TEXT NOT NULL CHECK (char_length(titulo) BETWEEN 3 AND 80),
	descripcion  TEXT NOT NULL DEFAULT '' CHECK (char_length(descripcion) <= 1000),
	categoria    TEXT NOT NULL REFERENCES categorias(clave),
	precio_c     BIGINT NOT NULL CHECK (precio_c BETWEEN 100 AND 100000000), -- de $1 a $1,000,000
	unidad       TEXT NOT NULL DEFAULT 'pieza'
		CHECK (unidad IN ('pieza', 'kilo', 'litro', 'caja', 'paquete', 'bulto', 'docena', 'metro', 'servicio')),
	existencias  INTEGER CHECK (existencias IS NULL OR existencias >= 0), -- NULL = sin conteo (servicios)
	minimo       INTEGER NOT NULL DEFAULT 1 CHECK (minimo BETWEEN 1 AND 100000),
	estado       TEXT NOT NULL DEFAULT 'activa' CHECK (estado IN ('activa', 'pausada')),
	creada       TIMESTAMPTZ NOT NULL DEFAULT now(),
	actualizada  TIMESTAMPTZ NOT NULL DEFAULT now(),
	busqueda     TSVECTOR GENERATED ALWAYS AS (to_tsvector('spanish', titulo || ' ' || descripcion)) STORED
);
CREATE INDEX publicaciones_recientes ON publicaciones(estado, id DESC);
CREATE INDEX publicaciones_vendedor ON publicaciones(vendedor_id, id DESC);
CREATE INDEX publicaciones_categoria ON publicaciones(categoria, estado, id DESC);
CREATE INDEX publicaciones_busqueda ON publicaciones USING GIN (busqueda);

CREATE TABLE fotos (
	id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	publicacion_id  BIGINT NOT NULL REFERENCES publicaciones(id) ON DELETE CASCADE,
	archivo         TEXT NOT NULL UNIQUE CHECK (archivo ~ '^[a-f0-9]{32}\.jpg$'),
	alt             TEXT NOT NULL CHECK (char_length(alt) BETWEEN 3 AND 140),
	ancho           INTEGER NOT NULL,
	alto            INTEGER NOT NULL,
	orden           INTEGER NOT NULL DEFAULT 0,
	creada          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX fotos_publicacion ON fotos(publicacion_id, orden, id);

-- Órdenes: Pendiente → Por cobrar → Pagada → Entregada (o Cancelada).
CREATE TABLE ordenes (
	id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	comprador_id    BIGINT NOT NULL REFERENCES perfiles(cuenta_id),
	vendedor_id     BIGINT NOT NULL REFERENCES perfiles(cuenta_id),
	publicacion_id  BIGINT REFERENCES publicaciones(id) ON DELETE SET NULL,
	titulo          TEXT NOT NULL,
	unidad          TEXT NOT NULL,
	cantidad        INTEGER NOT NULL CHECK (cantidad BETWEEN 1 AND 100000),
	precio_c        BIGINT NOT NULL CHECK (precio_c > 0),
	total_c         BIGINT NOT NULL,
	nota            TEXT NOT NULL DEFAULT '' CHECK (char_length(nota) <= 300),
	estado          TEXT NOT NULL DEFAULT 'pendiente'
		CHECK (estado IN ('pendiente', 'por_cobrar', 'pagada', 'entregada', 'cancelada')),
	cobro_id        TEXT,
	creada          TIMESTAMPTZ NOT NULL DEFAULT now(),
	actualizada     TIMESTAMPTZ NOT NULL DEFAULT now(),
	CHECK (comprador_id <> vendedor_id),
	CHECK (total_c = cantidad::bigint * precio_c)
);
CREATE INDEX ordenes_comprador ON ordenes(comprador_id, id DESC);
CREATE INDEX ordenes_vendedor ON ordenes(vendedor_id, id DESC);

CREATE TABLE orden_eventos (
	id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	orden_id  BIGINT NOT NULL REFERENCES ordenes(id) ON DELETE CASCADE,
	estado    TEXT NOT NULL,
	por       TEXT NOT NULL CHECK (por IN ('comprador', 'vendedor', 'pagos', 'sistema')),
	nota      TEXT NOT NULL DEFAULT '',
	creado    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX orden_eventos_orden ON orden_eventos(orden_id, id);

-- Seguridad a nivel de fila: aunque un handler olvide filtrar, Postgres solo
-- deja ver/cambiar una orden a su comprador, a su vendedor, o al servicio de
-- pagos. FORCE aplica la regla también al dueño de la tabla.
ALTER TABLE ordenes ENABLE ROW LEVEL SECURITY;
ALTER TABLE ordenes FORCE ROW LEVEL SECURITY;
CREATE POLICY ordenes_partes ON ordenes USING (
	current_setting('app.servicio', true) IN ('pagos', 'sistema')
	OR comprador_id = NULLIF(current_setting('app.cuenta', true), '')::bigint
	OR vendedor_id = NULLIF(current_setting('app.cuenta', true), '')::bigint
);
ALTER TABLE orden_eventos ENABLE ROW LEVEL SECURITY;
ALTER TABLE orden_eventos FORCE ROW LEVEL SECURITY;
CREATE POLICY eventos_partes ON orden_eventos USING (
	EXISTS (SELECT 1 FROM ordenes o WHERE o.id = orden_id)
);
