-- Publicidad de negocios: un negocio paga un plan por semanas para que uno de
-- sus productos aparezca como «Patrocinado». El servidor decide qué anuncio va
-- en cada espacio (así web y app muestran lo mismo y nunca se enciman).

CREATE TABLE planes_promo (
	clave            TEXT PRIMARY KEY,
	nombre           TEXT NOT NULL,
	precio_semana_c  BIGINT NOT NULL CHECK (precio_semana_c > 0),
	prioridad        INTEGER NOT NULL,           -- mayor = se muestra antes
	banner           BOOLEAN NOT NULL DEFAULT false, -- además, banner arriba de Explorar
	en_busquedas     BOOLEAN NOT NULL DEFAULT false, -- también aparece al buscar
	descripcion      TEXT NOT NULL,
	orden            INTEGER NOT NULL
);
INSERT INTO planes_promo (clave, nombre, precio_semana_c, prioridad, banner, en_busquedas, descripcion, orden) VALUES
	('basico', 'Básico', 4900, 1, false, false, 'Tu producto aparece como patrocinado en Explorar y en su categoría, en rotación con otros negocios.', 1),
	('destacado', 'Destacado', 9900, 2, false, true, 'Prioridad sobre el plan Básico y aparece también en las búsquedas relacionadas.', 2),
	('premium', 'Premium', 19900, 3, true, true, 'Banner de tu negocio arriba de Explorar, máxima prioridad y presencia en búsquedas.', 3);

CREATE TABLE promociones (
	id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	vendedor_id     BIGINT NOT NULL REFERENCES perfiles(cuenta_id) ON DELETE CASCADE,
	publicacion_id  BIGINT NOT NULL REFERENCES publicaciones(id) ON DELETE CASCADE,
	plan            TEXT NOT NULL REFERENCES planes_promo(clave),
	mensaje         TEXT NOT NULL DEFAULT '' CHECK (char_length(mensaje) <= 90),
	semanas         INTEGER NOT NULL CHECK (semanas BETWEEN 1 AND 12),
	costo_c         BIGINT NOT NULL CHECK (costo_c > 0),
	inicio          TIMESTAMPTZ NOT NULL DEFAULT now(),
	fin             TIMESTAMPTZ NOT NULL,
	estado          TEXT NOT NULL DEFAULT 'activa' CHECK (estado IN ('activa', 'pausada')),
	impresiones     BIGINT NOT NULL DEFAULT 0,
	clics           BIGINT NOT NULL DEFAULT 0,
	creada          TIMESTAMPTZ NOT NULL DEFAULT now(),
	CHECK (fin > inicio)
);
-- Un producto tiene como máximo una promoción activa a la vez.
CREATE UNIQUE INDEX promociones_una_activa ON promociones(publicacion_id) WHERE estado = 'activa';
CREATE INDEX promociones_vigentes ON promociones(estado, fin);
CREATE INDEX promociones_vendedor ON promociones(vendedor_id, id DESC);
