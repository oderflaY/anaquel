#!/bin/bash
# Entrena el asesor (0.5B por defecto) y sube solo el adaptador LoRA al VPS,
# donde deploy/llm/armar_modelo.sh lo fusiona y cuantiza.
set -euo pipefail
TAM=${1:-0.5b}
cd ~/AnaquelIA
.venv/bin/python entrenar.py "$TAM"
SSH=(ssh -i "$HOME/.ssh/id_vps_claude" root@2.25.68.28)
"${SSH[@]}" "rm -rf /root/anaquel/llm/lora-$TAM && mkdir -p /root/anaquel/llm/lora-$TAM"
scp -i "$HOME/.ssh/id_vps_claude" salida/lora-$TAM/adapter_config.json salida/lora-$TAM/adapter_model.safetensors root@2.25.68.28:/root/anaquel/llm/lora-$TAM/
"${SSH[@]}" "cd /root/anaquel/llm && nice ./armar_modelo.sh $TAM"
echo SUBIDO
