#!/bin/bash
# Entrena «tu socio» (dataset v2, 40k) con reintentos: si la GPU o el proceso fallan,
# reanuda desde el último checkpoint. Al terminar sube el LoRA al VPS y se desactiva.
cd ~/AnaquelIA
export LOTE=1 PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
# (la espera inicial está en ExecStartPre del servicio)
for i in $(seq 1 12); do
  echo "== intento $i $(date)"
  if ! nvidia-smi -L >/dev/null 2>&1; then echo "GPU no disponible; hace falta reiniciar"; exit 1; fi
  sudo -n nvidia-smi -pm 1 >/dev/null 2>&1
  sudo -n nvidia-smi -lgc 300,1500 >/dev/null 2>&1 && echo "reloj GPU fijo a 1500 MHz"
  if .venv/bin/python entrenar.py 0.5b; then
    echo "== entrenado $(date)"
    SSH=(ssh -i "$HOME/.ssh/id_vps_claude" root@2.25.68.28)
    "${SSH[@]}" "rm -rf /root/anaquel/llm/lora-0.5b && mkdir -p /root/anaquel/llm/lora-0.5b"
    scp -i "$HOME/.ssh/id_vps_claude" salida/lora-0.5b/adapter_config.json salida/lora-0.5b/adapter_model.safetensors root@2.25.68.28:/root/anaquel/llm/lora-0.5b/
    "${SSH[@]}" "cd /root/anaquel/llm && nice ./armar_modelo.sh 0.5b" && echo "== SUBIDO $(date)"
    sudo -n nvidia-smi -rgc >/dev/null 2>&1
    systemctl --user disable anaquelia-entrenar.service
    exit 0
  fi
  echo "== falló el intento $i, reanudo en 60 s"; sleep 60
done
