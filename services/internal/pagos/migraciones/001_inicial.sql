-- Servicio de pagos: terminales de cada vendedor y cobros con terminal.

CREATE TABLE terminales (
	id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	cuenta_id    BIGINT NOT NULL,
	proveedor    TEXT NOT NULL CHECK (proveedor IN ('mercadopago', 'clip', 'simulador')),
	nombre       TEXT NOT NULL CHECK (char_length(nombre) BETWEEN 2 AND 40),
	dispositivo  TEXT NOT NULL CHECK (dispositivo ~ '^[A-Za-z0-9_:-]{3,64}$'),
	activa       BOOLEAN NOT NULL DEFAULT true,
	creada       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Una terminal física solo puede estar vinculada a una cuenta.
CREATE UNIQUE INDEX terminales_dispositivo ON terminales(proveedor, dispositivo) WHERE activa;
CREATE INDEX terminales_cuenta ON terminales(cuenta_id);

CREATE TABLE cobros (
	id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	orden_id     BIGINT NOT NULL,
	cuenta_id    BIGINT NOT NULL, -- vendedor
	terminal_id  BIGINT NOT NULL REFERENCES terminales(id),
	proveedor    TEXT NOT NULL,
	monto_c      BIGINT NOT NULL CHECK (monto_c > 0),
	descripcion  TEXT NOT NULL DEFAULT '',
	estado       TEXT NOT NULL DEFAULT 'creado'
		CHECK (estado IN ('creado', 'enviado', 'aprobado', 'rechazado', 'cancelado', 'error')),
	ref_externa  TEXT,
	detalle      TEXT NOT NULL DEFAULT '',
	notificado   BOOLEAN NOT NULL DEFAULT false, -- ¿ya se avisó a mercado del estado final?
	consultado   TIMESTAMPTZ,
	creado       TIMESTAMPTZ NOT NULL DEFAULT now(),
	actualizado  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Nunca dos cobros en curso ni dos cobros aprobados para la misma orden.
CREATE UNIQUE INDEX cobros_uno_en_curso ON cobros(orden_id) WHERE estado IN ('creado', 'enviado');
CREATE UNIQUE INDEX cobros_uno_aprobado ON cobros(orden_id) WHERE estado = 'aprobado';
CREATE UNIQUE INDEX cobros_ref ON cobros(proveedor, ref_externa) WHERE ref_externa IS NOT NULL;
CREATE INDEX cobros_pendientes ON cobros(estado, actualizado) WHERE estado = 'enviado' OR NOT notificado;

-- Webhooks ya procesados (contra reenvíos/repeticiones).
CREATE TABLE webhooks (
	proveedor  TEXT NOT NULL,
	evento_id  TEXT NOT NULL,
	recibido   TIMESTAMPTZ NOT NULL DEFAULT now(),
	PRIMARY KEY (proveedor, evento_id)
);
