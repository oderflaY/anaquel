# IA de «tu socio»

Código de entrenamiento (los datos, el modelo base y las salidas no se suben).

- `demanda/entrenar_demanda.py`: pronóstico de demanda (LightGBM p50/p90 sobre M5); se exporta a `backend/modelos`.
- `generar_datos_v2.py`: dataset sintético (40k) con el mismo formato de hoja que `backend/asesor_local.go`.
- `entrenar.py [0.5b|1.5b]`: LoRA sobre Qwen2.5-Instruct (TRL SFT, pérdida solo en la respuesta).
- `entrenar_reanudable.sh` + `anaquelia-entrenar.service`: entrena reanudando desde checkpoints y sube el LoRA al VPS.
- `cortar_en_500.sh`: versión corta, usa el checkpoint 500.
- `evaluar.py`: evalúa un servidor llama.cpp con preguntas de prueba.
- En el VPS, `deploy/llm/armar_modelo.sh 0.5b` fusiona el LoRA y genera `anaquel-asesor.gguf`.
