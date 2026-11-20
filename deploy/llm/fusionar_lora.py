# Fusiona un LoRA de PEFT en el modelo base HF (sin peft): W += (B@A)*alpha/r
import json, os, shutil, sys, torch
from safetensors.torch import load_file, save_file
base, lora, out = sys.argv[1:4]
cfg = json.load(open(f"{lora}/adapter_config.json")); s = cfg["lora_alpha"] / cfg["r"]
W = load_file(f"{base}/model.safetensors"); L = load_file(f"{lora}/adapter_model.safetensors")
n = 0
for k, A in L.items():
    if ".lora_A." not in k: continue
    B = L[k.replace(".lora_A.", ".lora_B.")]
    dst = k.replace("base_model.model.", "", 1).replace(".lora_A.weight", ".weight")
    w = W[dst]; W[dst] = (w.float() + (B.float() @ A.float()) * s).to(w.dtype); n += 1
os.makedirs(out, exist_ok=True)
for f in os.listdir(base):
    if f != "model.safetensors": shutil.copy(f"{base}/{f}", out)
save_file({k: v.contiguous() for k, v in W.items()}, f"{out}/model.safetensors", metadata={"format": "pt"})
print("capas fusionadas:", n)
