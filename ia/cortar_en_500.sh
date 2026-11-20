#!/bin/bash
# Corta el entrenamiento en el checkpoint 500 y sube ese LoRA al VPS.
cd ~/AnaquelIA
C=salida/lora-0.5b/checkpoint-500
until [ -f $C/adapter_model.safetensors ] && [ -f $C/trainer_state.json ]; do sleep 15; done
sleep 20
systemctl --user stop anaquelia-entrenar.service
systemctl --user disable anaquelia-entrenar.service
sudo -n nvidia-smi -rgc >/dev/null 2>&1
echo "== cortado en 500 $(date)"
python3 -c "import json;[print(e) for e in json.load(open('$C/trainer_state.json'))['log_history'] if 'eval_loss' in e]"
SSH=(ssh -i "$HOME/.ssh/id_vps_claude" root@2.25.68.28)
"${SSH[@]}" "rm -rf /root/anaquel/llm/lora-0.5b && mkdir -p /root/anaquel/llm/lora-0.5b" &&
scp -i "$HOME/.ssh/id_vps_claude" $C/adapter_config.json $C/adapter_model.safetensors root@2.25.68.28:/root/anaquel/llm/lora-0.5b/ &&
"${SSH[@]}" "cd /root/anaquel/llm && nice ./armar_modelo.sh 0.5b" && echo "== SUBIDO $(date)"
