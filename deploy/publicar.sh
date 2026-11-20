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
# Las imágenes se construyen en la laptop: compilar Go en el VPS (4 GB) se comía
# 2 GB y lo dejaba sin memoria. Solo se suben las que cambiaron.
COMPOSE=(docker compose -p anaquel -f docker-compose.servicios.yml -f docker-compose.vps.yml)
(cd deploy && "${COMPOSE[@]}" --env-file .env.ejemplo build)
cambiadas=()
for img in $(cd deploy && "${COMPOSE[@]}" --env-file .env.ejemplo config --images | grep '^anaquel-'); do
	local_id=$(docker image inspect -f '{{.Id}}' "$img")
	remoto_id=$("${SSH[@]}" "docker image inspect -f '{{.Id}}' $img 2>/dev/null" || true)
	[ "$local_id" = "$remoto_id" ] || cambiadas+=("$img")
done
if [ ${#cambiadas[@]} -gt 0 ]; then
	echo "Subiendo: ${cambiadas[*]}"
	docker save "${cambiadas[@]}" | gzip -1 | "${SSH[@]}" 'gunzip | docker load'
fi
tar czf - deploy --exclude=deploy/.env --exclude=deploy/datos \
	| "${SSH[@]}" 'set -e; mkdir -p /root/anaquel && tar xzf - -C /root/anaquel && cd /root/anaquel/deploy && test -f .env
	docker compose -p anaquel -f docker-compose.servicios.yml -f docker-compose.vps.yml --env-file .env up -d --no-build --remove-orphans'
"${SSH[@]}" 'sleep 5; docker ps --filter name=anaquel- --format "{{.Names}} {{.Status}}"'
