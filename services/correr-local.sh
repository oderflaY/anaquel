#!/usr/bin/env bash
# Levanta en tu máquina el núcleo de Anaquel + los servicios del mercado.
#
#   ./services/correr-local.sh          # compila y arranca todo
#   ./services/correr-local.sh parar    # detiene todo
#
# Requisitos: Go 1.27+, Docker (Postgres) y el build de la web (cd web && npm run build).
# Abre http://localhost:8080/anaquel/mercado
set -euo pipefail
cd "$(dirname "$0")"
RAIZ="$(cd .. && pwd)"
# En Git Bash (Windows) Docker necesita rutas tipo C:/…
RAIZ_DOCKER="$RAIZ"; command -v cygpath >/dev/null && RAIZ_DOCKER="$(cygpath -m "$RAIZ")"
ENV=".env.local"
PIDS=".pids"

if [[ "${1:-}" == "parar" ]]; then
	[[ -f $PIDS ]] && while read -r p; do kill "$p" 2>/dev/null || true; done < $PIDS
	rm -f $PIDS
	echo "Servicios detenidos."
	exit 0
fi

llave() { head -c 32 /dev/urandom | base64 | tr -d '\n'; }
pass() { head -c 24 /dev/urandom | base64 | tr -d '\n/+=' ; }

# 1) Secretos locales (se generan una sola vez y no se suben a git).
if [[ ! -f $ENV ]]; then
	cat > $ENV <<EOF
CLAVE_INTERNA=$(llave)
CLAVE_DATOS=$(llave)
CLAVE_INDICE=$(llave)
SIMULADOR_SECRETO=$(pass)$(pass)
PG_SUPER=$(pass)
PG_ACCESO_PASSWORD=$(pass)
PG_MERCADO_PASSWORD=$(pass)
PG_REELS_PASSWORD=$(pass)
PG_PAGOS_PASSWORD=$(pass)
EOF
	chmod 600 $ENV
	echo "Secretos nuevos en services/$ENV"
fi
set -a; source $ENV; set +a

# 2) Postgres en Docker (solo escucha en 127.0.0.1).
if ! docker ps --format '{{.Names}}' | grep -q '^anaquel-pg$'; then
	docker rm -f anaquel-pg >/dev/null 2>&1 || true
	MSYS_NO_PATHCONV=1 docker run -d --name anaquel-pg -p 127.0.0.1:54330:5432 \
		-e POSTGRES_PASSWORD="$PG_SUPER" -e POSTGRES_DB=anaquel -e POSTGRES_INITDB_ARGS="--auth=scram-sha-256" \
		-e PG_ACCESO_PASSWORD -e PG_MERCADO_PASSWORD -e PG_REELS_PASSWORD -e PG_PAGOS_PASSWORD \
		-v "$RAIZ_DOCKER/deploy/postgres/init.sh:/docker-entrypoint-initdb.d/init.sh:ro" \
		-v anaquel-pg-datos:/var/lib/postgresql/data postgres:17-alpine >/dev/null
	echo "Esperando a Postgres…"
	until docker logs anaquel-pg 2>&1 | grep -q "PostgreSQL init process complete"; do sleep 1; done; sleep 2
fi
dsn() { echo "postgres://$1_app:$2@127.0.0.1:54330/anaquel?sslmode=disable"; }

# 3) Compilar.
mkdir -p bin datos
for s in pasarela acceso mercado reels pagos; do go build -o "bin/$s" "./cmd/$s"; done
(cd "$RAIZ/backend" && go build -o "$RAIZ/services/bin/nucleo" .)

# 4) Arrancar (modo desarrollo: cookie sin Secure porque es http://localhost).
: > $PIDS
comun=(DEMO=1 COOKIE_INSEGURA=1)
env "${comun[@]}" ADDR=:8097 DATOS="$RAIZ/services/datos/nucleo" WEB="$RAIZ/web/dist" bin/nucleo > datos/nucleo.log 2>&1 & echo $! >> $PIDS
env "${comun[@]}" BD_DSN="$(dsn acceso "$PG_ACCESO_PASSWORD")" bin/acceso > datos/acceso.log 2>&1 & echo $! >> $PIDS
env "${comun[@]}" BD_DSN="$(dsn mercado "$PG_MERCADO_PASSWORD")" DATOS=datos/mercado bin/mercado > datos/mercado.log 2>&1 & echo $! >> $PIDS
env "${comun[@]}" BD_DSN="$(dsn reels "$PG_REELS_PASSWORD")" DATOS=datos/reels bin/reels > datos/reels.log 2>&1 & echo $! >> $PIDS
env "${comun[@]}" BD_DSN="$(dsn pagos "$PG_PAGOS_PASSWORD")" bin/pagos > datos/pagos.log 2>&1 & echo $! >> $PIDS
env "${comun[@]}" CONFIAR_PROXY="${CONFIAR_PROXY:-0}" ORIGENES_PERMITIDOS=http://localhost:5173 bin/pasarela > datos/pasarela.log 2>&1 & echo $! >> $PIDS
echo "Listo: http://localhost:8080/anaquel/mercado  (bitácoras en services/datos/*.log)"
