-- Servicio de acceso: cuentas de clientes, sesiones, invitaciones y bitácora.
-- Datos personales (correo, teléfono) van cifrados; el correo se busca por
-- un índice ciego (HMAC), nunca en claro.

CREATE TABLE cuentas (
	id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	email_idx       BYTEA NOT NULL UNIQUE,
	email_cifrado   BYTEA NOT NULL,
	pass_hash       TEXT NOT NULL,
	nombre          TEXT NOT NULL CHECK (char_length(nombre) BETWEEN 2 AND 80),
	slug            TEXT NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND char_length(slug) <= 60),
	giro            TEXT NOT NULL DEFAULT 'otro' CHECK (char_length(giro) <= 30),
	descripcion     TEXT NOT NULL DEFAULT '' CHECK (char_length(descripcion) <= 600),
	telefono_cifrado BYTEA,
	colonia         TEXT NOT NULL DEFAULT '' CHECK (char_length(colonia) <= 80),
	lat             DOUBLE PRECISION CHECK (lat BETWEEN -90 AND 90),
	lng             DOUBLE PRECISION CHECK (lng BETWEEN -180 AND 180),
	rol             TEXT NOT NULL DEFAULT 'cliente' CHECK (rol IN ('cliente', 'admin')),
	estado          TEXT NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'verificada', 'suspendida')),
	vende           BOOLEAN NOT NULL DEFAULT false,
	demo            BOOLEAN NOT NULL DEFAULT false,
	tienda_slug     TEXT CHECK (tienda_slug IS NULL OR tienda_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
	invitada_por    BIGINT REFERENCES cuentas(id) ON DELETE SET NULL,
	verificada_por  BIGINT REFERENCES cuentas(id) ON DELETE SET NULL,
	pass_cambiada   TIMESTAMPTZ NOT NULL DEFAULT now(),
	creada          TIMESTAMPTZ NOT NULL DEFAULT now(),
	CHECK ((lat IS NULL) = (lng IS NULL))
);
CREATE INDEX cuentas_estado ON cuentas(estado, creada);

-- Solo se guarda el SHA-256 del token: quien lea la tabla no puede usar sesiones.
CREATE TABLE sesiones (
	token_hash  BYTEA PRIMARY KEY,
	cuenta_id   BIGINT NOT NULL REFERENCES cuentas(id) ON DELETE CASCADE,
	creada      TIMESTAMPTZ NOT NULL DEFAULT now(),
	ultimo_uso  TIMESTAMPTZ NOT NULL DEFAULT now(),
	expira      TIMESTAMPTZ NOT NULL,
	agente      TEXT NOT NULL DEFAULT '' CHECK (char_length(agente) <= 120)
);
CREATE INDEX sesiones_cuenta ON sesiones(cuenta_id);
CREATE INDEX sesiones_expira ON sesiones(expira);

-- Intentos fallidos por correo (índice ciego) y por IP: bloqueo progresivo.
-- Se cuenta aunque el correo no exista, así no se puede averiguar quién tiene cuenta.
CREATE TABLE intentos (
	clave            BYTEA PRIMARY KEY,
	fallos           INTEGER NOT NULL DEFAULT 0,
	bloqueado_hasta  TIMESTAMPTZ,
	actualizado      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invitaciones (
	id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	codigo_hash  BYTEA NOT NULL UNIQUE,
	creada_por   BIGINT NOT NULL REFERENCES cuentas(id) ON DELETE CASCADE,
	usada_por    BIGINT REFERENCES cuentas(id) ON DELETE SET NULL,
	creada       TIMESTAMPTZ NOT NULL DEFAULT now(),
	expira       TIMESTAMPTZ NOT NULL,
	usada        TIMESTAMPTZ
);
CREATE INDEX invitaciones_creador ON invitaciones(creada_por, creada);

-- Bitácora de seguridad: solo se agregan renglones.
CREATE TABLE bitacora (
	id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	cuenta_id  BIGINT,
	evento     TEXT NOT NULL,
	ip_idx     BYTEA,
	detalle    JSONB NOT NULL DEFAULT '{}',
	creada     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX bitacora_cuenta ON bitacora(cuenta_id, creada);

CREATE FUNCTION bitacora_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION 'la bitácora de seguridad no se modifica ni se borra';
END $$;
CREATE TRIGGER bitacora_sin_cambios BEFORE UPDATE OR DELETE ON bitacora
	FOR EACH ROW EXECUTE FUNCTION bitacora_inmutable();
CREATE TRIGGER bitacora_sin_vaciar BEFORE TRUNCATE ON bitacora
	FOR EACH STATEMENT EXECUTE FUNCTION bitacora_inmutable();
