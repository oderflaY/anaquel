#!/usr/bin/env bash
# Corre EN EL VPS (/root/anaquel/llm). Arma el modelo del asesor sin subir
# gigas: aquí ya está el modelo base (base-<tam>/, de Hugging Face) y solo se
# sube el adaptador LoRA entrenado en la laptop (lora-<tam>/, ~20 MB).
#   base HF → GGUF f16  +  LoRA → GGUF  →  fusión  →  Q4_K_M  →  anaquel-asesor.gguf
#   ./armar_modelo.sh 0.5b
set -euo pipefail
TAM=${1:-0.5b}
cd "$(dirname "$0")"
IMG=ghcr.io/ggml-org/llama.cpp:full
corre() { docker run --rm -v "$PWD":/m -w /app --entrypoint "$1" "$IMG" "${@:2}"; }
test -s base-$TAM-f16.gguf || corre python3 convert_hf_to_gguf.py /m/base-$TAM --outfile /m/base-$TAM-f16.gguf --outtype f16
if [ -d lora-$TAM ]; then
	corre python3 convert_lora_to_gguf.py /m/lora-$TAM --base /m/base-$TAM --outfile /m/lora-$TAM.gguf --outtype f16
	corre ./llama-export-lora -m /m/base-$TAM-f16.gguf --lora /m/lora-$TAM.gguf -o /m/asesor-$TAM-f16.gguf
	corre ./llama-quantize /m/asesor-$TAM-f16.gguf /m/asesor-$TAM-q4km.gguf Q4_K_M
	rm -f asesor-$TAM-f16.gguf
	ln -sf asesor-$TAM-q4km.gguf anaquel-asesor.gguf
	ls -la anaquel-asesor.gguf asesor-$TAM-q4km.gguf
fi
