#!/usr/bin/env bash
# Sube Anaquel completo (núcleo + servicios del mercado) al VPS y lo reconstruye.
#   https://2.25.68.28/anaquel/          panel, bodega 3D, chat
#   https://2.25.68.28/anaquel/mercado   mercado (servicios en services/)
# Los secretos viven solo en el VPS: /root/anaquel/deploy/.env (plantilla: deploy/.env.ejemplo).
# Antes de publicar se respalda la base del núcleo en /root/respaldos/.
set -euo pipefail
cd "$(dirname "$0")/.."
SSH=(ssh -i "$HOME/.ssh/id_vps_claude" root@2.25.68.28)
"${SSH[@]}" 'set -e; f=/root/respaldos/anaquel-$(date +%Y-%m-%d-%H%M); mkdir -p $f; cp -a /root/anaquel/deploy/datos/anaquel.db* $f/ 2>/dev/null || true
	docker exec anaquel-postgres-1 pg_dumpall -U postgres > $f/postgres.sql 2>/dev/null || true; echo "Respaldo en $f"'
tar czf - --exclude=node_modules --exclude=dist --exclude=services/bin --exclude=services/datos \
	--exclude=services/.env.local --exclude=services/.pids --exclude=deploy/.env --exclude=deploy/datos \
	Dockerfile .dockerignore backend web services deploy \
	| "${SSH[@]}" 'set -e; mkdir -p /root/anaquel && tar xzf - -C /root/anaquel && cd /root/anaquel/deploy && test -f .env
	docker compose -p anaquel -f docker-compose.servicios.yml -f docker-compose.vps.yml --env-file .env up -d --build'
"${SSH[@]}" 'sleep 5; docker ps --filter name=anaquel- --format "{{.Names}} {{.Status}}"'
