"""Prueba el modelo servido por llama.cpp con hojas reales del backend (hojas/*.txt).

    .venv/bin/python evaluar.py http://127.0.0.1:8099
Cada hoja tiene la hoja de datos; se le hacen varias preguntas y se revisa que
las cifras que contesta existan en la hoja (para cachar inventos).
"""
import json
import re
import sys
import time
import urllib.request

from generar_datos import PROMPT as SISTEMA

URL = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8099") + "/v1/chat/completions"
PREGUNTAS = ["¿qué me conviene surtir?", "¿cómo va mi negocio?", "¿dónde compro azúcar más barata?",
             "¿qué viene de temporada?", "¿qué no se vende?", "¿tengo pendientes?", "¿cuánto cuesta un iPhone?"]


def preguntar(hoja, q):
    cuerpo = {"messages": [{"role": "system", "content": SISTEMA},
                           {"role": "user", "content": f"DATOS DE MI TIENDA:\n{hoja}\n\nPREGUNTA: {q}"}],
              "temperature": 0.2, "max_tokens": 300}
    req = urllib.request.Request(URL, json.dumps(cuerpo).encode(), {"Content-Type": "application/json"})
    t = time.time()
    r = json.load(urllib.request.urlopen(req, timeout=300))
    return r["choices"][0]["message"]["content"], time.time() - t


def main():
    import glob
    malos = total = 0
    for ruta in sorted(glob.glob(sys.argv[2] if len(sys.argv) > 2 else "hojas/*.txt")):
        hoja = open(ruta).read().strip()
        cifras_hoja = set(re.findall(r"\d+(?:[.,]\d+)?", hoja))
        for q in PREGUNTAS:
            resp, seg = preguntar(hoja, q)
            # Cifras de la respuesta que no salen en la hoja (los totales calculados son válidos, se marcan para revisar).
            raras = [c for c in re.findall(r"\d+(?:[.,]\d+)?", resp) if c not in cifras_hoja]
            total += 1
            malos += bool(raras)
            print(f"\n### {ruta} · {q}  ({seg:.1f}s){'  ⚠ cifras fuera de la hoja: ' + ', '.join(raras) if raras else ''}\n{resp}")
    print(f"\n{total} respuestas, {malos} con cifras que no vienen tal cual en la hoja (revisar a mano: pueden ser totales).")


if __name__ == "__main__":
    main()
