"""Afina Qwen2.5 con LoRA para «tu socio» (asesor de Anaquel) con el dataset masivo v2.

    .venv/bin/python entrenar.py                 # 1.5B: entrena y guarda el modelo fusionado
    .venv/bin/python entrenar.py 0.5b            # el chico (más rápido en el VPS de 1 CPU)
La pérdida solo cuenta la respuesta del asesor (no la hoja de datos ni la pregunta).
"""
import json
import os
import sys

import torch
from datasets import Dataset
from peft import LoraConfig
from transformers import AutoModelForCausalLM, AutoTokenizer
from trl import SFTConfig, SFTTrainer

TAM = sys.argv[1] if len(sys.argv) > 1 else "1.5b"
BASE = f"modelos/qwen2.5-{TAM}-instruct"
SALIDA = f"salida/lora-{TAM}"
DATOS = os.environ.get("DATOS", "datos/v2")
N = int(os.environ.get("N", "0"))  # 0 = todos
FUSIONADO = f"salida/anaquel-asesor-{TAM}"


def cargar(ruta):
    filas = []
    for l in open(ruta):
        m = json.loads(l)["messages"]
        filas.append({"prompt": m[:2], "completion": m[2:]})
    return Dataset.from_list(filas)


def main():
    tok = AutoTokenizer.from_pretrained(BASE)
    modelo = AutoModelForCausalLM.from_pretrained(BASE, dtype=torch.bfloat16, attn_implementation="sdpa")
    entren, prueba = cargar(f"{DATOS}/entrenamiento.jsonl"), cargar(f"{DATOS}/prueba.jsonl")
    if N:
        entren = entren.select(range(min(N, len(entren))))
    prueba = prueba.select(range(min(200, len(prueba))))
    lora = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
                      target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"])
    cfg = SFTConfig(
        output_dir=SALIDA, num_train_epochs=float(os.environ.get("EPOCAS", "1")),
        per_device_train_batch_size=int(os.environ.get("LOTE", "4")), gradient_accumulation_steps=16 // int(os.environ.get("LOTE", "4")),
        learning_rate=2e-4, lr_scheduler_type="cosine", warmup_steps=50, weight_decay=0.0,
        bf16=True, gradient_checkpointing=os.environ.get("CKPT") == "1", max_length=2048, logging_steps=20,
        eval_strategy="steps", eval_steps=500, per_device_eval_batch_size=2,
        save_strategy="steps", save_steps=250, save_total_limit=2, report_to="none",
        completion_only_loss=True, dataloader_num_workers=4, use_liger_kernel=os.environ.get("LIGER", "1") == "1",
    )
    t = SFTTrainer(model=modelo, args=cfg, train_dataset=entren, eval_dataset=prueba, processing_class=tok, peft_config=lora)
    t.train(resume_from_checkpoint=any(n.startswith("checkpoint") for n in (os.listdir(SALIDA) if os.path.isdir(SALIDA) else [])))
    t.save_model(SALIDA)
    print("Evaluación final:", t.evaluate())
    # Fusionar LoRA en el modelo base para convertirlo a GGUF.
    fus = t.model.merge_and_unload()
    fus.save_pretrained(FUSIONADO, safe_serialization=True)
    tok.save_pretrained(FUSIONADO)
    print("Modelo fusionado en", FUSIONADO)


if __name__ == "__main__":
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    main()
