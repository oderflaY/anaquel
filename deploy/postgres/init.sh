#!/bin/sh
# Inicializa Postgres para los servicios de Anaquel (se ejecuta una sola vez,
# cuando el volumen está vacío).
#
# Cada servicio tiene su propio usuario y su propio esquema: el usuario de
# "mercado" no puede leer ni una fila de "acceso" (contraseñas, sesiones).
# Las contraseñas llegan por variables de entorno (ver deploy/.env.ejemplo).
set -eu

for v in PG_ACCESO_PASSWORD PG_MERCADO_PASSWORD PG_REELS_PASSWORD PG_PAGOS_PASSWORD; do
	eval "val=\${$v:-}"
	if [ ${#val} -lt 24 ]; then
		echo "init.sh: $v debe tener al menos 24 caracteres" >&2
		exit 1
	fi
done

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	-v acceso="$PG_ACCESO_PASSWORD" -v mercado="$PG_MERCADO_PASSWORD" \
	-v reels="$PG_REELS_PASSWORD" -v pagos="$PG_PAGOS_PASSWORD" <<-'EOSQL'
	-- Nadie más que los roles de la app se conecta, y nadie usa el esquema public.
	REVOKE ALL ON DATABASE :"DBNAME" FROM PUBLIC;
	REVOKE ALL ON SCHEMA public FROM PUBLIC;

	CREATE ROLE acceso_app  LOGIN PASSWORD :'acceso'  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT 40;
	CREATE ROLE mercado_app LOGIN PASSWORD :'mercado' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT 40;
	CREATE ROLE reels_app   LOGIN PASSWORD :'reels'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT 40;
	CREATE ROLE pagos_app   LOGIN PASSWORD :'pagos'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT 40;

	GRANT CONNECT, TEMPORARY ON DATABASE :"DBNAME" TO acceso_app, mercado_app, reels_app, pagos_app;

	CREATE SCHEMA acceso  AUTHORIZATION acceso_app;
	CREATE SCHEMA mercado AUTHORIZATION mercado_app;
	CREATE SCHEMA reels   AUTHORIZATION reels_app;
	CREATE SCHEMA pagos   AUTHORIZATION pagos_app;

	-- Cada rol solo ve su esquema.
	ALTER ROLE acceso_app  SET search_path = acceso;
	ALTER ROLE mercado_app SET search_path = mercado;
	ALTER ROLE reels_app   SET search_path = reels;
	ALTER ROLE pagos_app   SET search_path = pagos;

	-- Límites para que una consulta mala no tumbe la base.
	ALTER ROLE acceso_app  SET statement_timeout = '10s';
	ALTER ROLE mercado_app SET statement_timeout = '10s';
	ALTER ROLE reels_app   SET statement_timeout = '10s';
	ALTER ROLE pagos_app   SET statement_timeout = '10s';
	ALTER ROLE acceso_app  SET idle_in_transaction_session_timeout = '30s';
	ALTER ROLE mercado_app SET idle_in_transaction_session_timeout = '30s';
	ALTER ROLE reels_app   SET idle_in_transaction_session_timeout = '30s';
	ALTER ROLE pagos_app   SET idle_in_transaction_session_timeout = '30s';
EOSQL
