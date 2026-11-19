#!/usr/bin/env bash
# Sube Anaquel (API + web) al VPS y reconstruye el contenedor → https://2.25.68.28:15443
set -euo pipefail
cd "$(dirname "$0")/.."
SSH="ssh -i $HOME/.ssh/id_vps_claude root@2.25.68.28"
tar czf - --exclude=node_modules --exclude=dist Dockerfile .dockerignore backend web -C deploy docker-compose.yml \
  | $SSH 'mkdir -p /root/anaquel/datos && chown 10001 /root/anaquel/datos && tar xzf - -C /root/anaquel && cd /root/anaquel && docker compose up -d --build'
$SSH 'sleep 3; docker ps --filter name=anaquel-api --format "{{.Names}} {{.Status}}"'
