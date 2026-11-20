-- Registro propio de cobros: el sistema de pagos del proyecto guarda cada pago
-- aprobado que le notifica una terminal, se haya iniciado desde la app
-- (origen 'app') o directamente en el aparato (origen 'terminal'). Es la
-- fuente de verdad del proyecto, independiente de los reportes de cada marca.
CREATE TABLE registros (
	id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	cuenta_id    BIGINT NOT NULL,
	terminal_id  BIGINT REFERENCES terminales(id),
	proveedor    TEXT NOT NULL,
	ref_externa  TEXT NOT NULL,
	monto_c      BIGINT NOT NULL CHECK (monto_c > 0),
	origen       TEXT NOT NULL CHECK (origen IN ('app', 'terminal')),
	cobro_id     UUID REFERENCES cobros(id),
	orden_id     BIGINT,
	detalle      TEXT NOT NULL DEFAULT '',
	pagado_en    TIMESTAMPTZ NOT NULL,
	registrado   TIMESTAMPTZ NOT NULL DEFAULT now(),
	UNIQUE (proveedor, ref_externa) -- una notificación repetida nunca duplica el ingreso
);
CREATE INDEX registros_cuenta ON registros(cuenta_id, id DESC);

-- Inmutable: no se borra; de un registro solo puede cambiar orden_id (de vacío
-- a una orden, al vincular una venta directa con su orden).
CREATE FUNCTION registros_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	IF TG_OP = 'DELETE' THEN
		RAISE EXCEPTION 'el registro de cobros no se borra';
	END IF;
	IF OLD.orden_id IS NOT NULL OR NEW.orden_id IS NULL
		OR (NEW.cuenta_id, NEW.terminal_id, NEW.proveedor, NEW.ref_externa, NEW.monto_c, NEW.origen, NEW.cobro_id, NEW.pagado_en, NEW.registrado)
		IS DISTINCT FROM (OLD.cuenta_id, OLD.terminal_id, OLD.proveedor, OLD.ref_externa, OLD.monto_c, OLD.origen, OLD.cobro_id, OLD.pagado_en, OLD.registrado)
	THEN
		RAISE EXCEPTION 'el registro de cobros solo admite vincular una orden una vez';
	END IF;
	RETURN NEW;
END $$;
CREATE TRIGGER registros_sin_cambios BEFORE UPDATE OR DELETE ON registros
	FOR EACH ROW EXECUTE FUNCTION registros_inmutable();
CREATE FUNCTION registros_sin_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION 'el registro de cobros no se vacía';
END $$;
CREATE TRIGGER registros_sin_vaciar BEFORE TRUNCATE ON registros
	FOR EACH STATEMENT EXECUTE FUNCTION registros_sin_truncate();

-- Terminal virtual: se comporta como el servidor de una marca. Guarda sus
-- transacciones y notifica por webhook firmado, igual que Mercado Pago.
CREATE TABLE sim_transacciones (
	id           TEXT PRIMARY KEY,
	dispositivo  TEXT NOT NULL,
	monto_c      BIGINT NOT NULL CHECK (monto_c > 0),
	referencia   TEXT NOT NULL DEFAULT '', -- nuestro cobro (vacío = cobro directo en la terminal)
	concepto     TEXT NOT NULL DEFAULT '',
	estado       TEXT NOT NULL DEFAULT 'en_terminal' CHECK (estado IN ('en_terminal', 'aprobada', 'rechazada', 'cancelada')),
	creada       TIMESTAMPTZ NOT NULL DEFAULT now(),
	actualizada  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sim_dispositivo ON sim_transacciones(dispositivo, estado);
