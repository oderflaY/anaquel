"""Modelo de demanda de Anaquel entrenado con ventas reales (M5 / Walmart).

Aprende a pronosticar cuántas piezas de un producto se venderán en los
próximos 14 días a partir SOLO de lo que Anaquel también sabe de una tiendita:
su historial diario de ventas (con huecos si la tienda es nueva), la familia
del producto, la quincena y los días festivos que vienen.

Entrena dos modelos LightGBM:
  * p50  (tweedie): el pronóstico esperado.
  * p90  (cuantil 0.9): «lo más que razonablemente venderás» → para pedir sin
    quedarte sin mercancía 9 de cada 10 veces.
Los exporta a JSON (árboles) para que el backend en Go los evalúe sin Python.

    ../.venv-datos/bin/python entrenar_demanda.py
"""
import json
import math
import time
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd

AQUI = Path(__file__).parent
M5 = AQUI.parent / "reales" / "m5"
SALIDA = AQUI / "modelo"
H = 14  # días a pronosticar

# Mismo orden que backend/pronostico.go (¡no cambiar sin cambiar el Go!).
CARACTERISTICAS = [
    "m7", "m14", "m28", "m56", "m112",      # promedio diario de piezas (NaN si no hay tanta historia)
    "ceros28", "dias_sin_venta", "max28",   # qué tan intermitente es
    "dow_hoy", "dia_mes", "mes",            # calendario del corte
    "festivos14", "dias_a_festivo",         # días festivos que vienen
    "yoy",                                   # lo vendido en esas mismas 2 semanas hace un año / m28 de entonces
    "familia",                               # 0 comida/bebida, 1 hogar/limpieza, 2 otros (papelería, ropa…)
    "historia",                              # días de historial disponibles
]
FAMILIA = {"FOODS": 0, "HOUSEHOLD": 1, "HOBBIES": 2}


def cargar():
    t = time.time()
    ventas = pd.read_csv(M5 / "sales_train_evaluation.csv")
    dias = [c for c in ventas.columns if c.startswith("d_")]
    X = np.array(ventas[dias].to_numpy(dtype=np.float32), copy=True)  # productos × días
    fam = ventas["cat_id"].map(FAMILIA).to_numpy()
    cal = pd.read_csv(M5 / "calendar.csv").iloc[: len(dias)]
    fechas = pd.to_datetime(cal["date"])
    festivo = (cal["event_name_1"].notna() | cal["event_name_2"].notna()).to_numpy()
    # Walmart cierra en Navidad: ese día no cuenta como dato (no fue falta de demanda).
    navidad = ((fechas.dt.month == 12) & (fechas.dt.day == 25)).to_numpy()
    X[:, navidad] = np.nan
    print(f"M5: {X.shape[0]} productos × {X.shape[1]} días, cargado en {time.time() - t:.0f} s")
    return X, fam, fechas, festivo


def media(X, a, b):
    """Promedio diario en [a, b) ignorando días sin dato."""
    with np.errstate(invalid="ignore"):
        return np.nanmean(X[:, a:b], axis=1)


def filas(X, fam, fechas, festivo, corte, rng):
    """Características al cierre del día `corte` (exclusivo) y objetivo = ventas de los 14 días siguientes."""
    n = X.shape[0]
    # Producto vivo: ya vendía algo en los 56 días previos (los que aún no salen al mercado no aportan).
    vivo = np.nansum(X[:, corte - 56:corte], axis=1) > 0
    f = {}
    for k in (7, 14, 28, 56, 112):
        f[f"m{k}"] = media(X, corte - k, corte)
    v28 = X[:, corte - 28:corte]
    f["ceros28"] = np.mean(np.nan_to_num(v28) == 0, axis=1)
    f["max28"] = np.nanmax(np.nan_to_num(v28), axis=1)
    ult = np.full(n, 112.0)
    hist = np.nan_to_num(X[:, corte - 112:corte])
    con = hist > 0
    algun = con.any(axis=1)
    ult[algun] = np.argmax(con[algun, ::-1], axis=1)  # 0 = vendió ayer
    f["dias_sin_venta"] = ult
    fecha = fechas.iloc[corte]
    f["dow_hoy"] = np.full(n, fecha.day_of_week, dtype=np.float32)
    f["dia_mes"] = np.full(n, fecha.day, dtype=np.float32)
    f["mes"] = np.full(n, fecha.month, dtype=np.float32)
    f["festivos14"] = np.full(n, festivo[corte:corte + H].sum(), dtype=np.float32)
    prox = np.nonzero(festivo[corte:corte + 60])[0]
    f["dias_a_festivo"] = np.full(n, prox[0] if len(prox) else 60, dtype=np.float32)
    if corte - 364 - 28 >= 0:
        base = media(X, corte - 364 - 28, corte - 364)
        mismo = media(X, corte - 364, corte - 364 + H)
        with np.errstate(invalid="ignore", divide="ignore"):
            f["yoy"] = np.where(base > 0, mismo / base, np.nan)
    else:
        f["yoy"] = np.full(n, np.nan)
    f["familia"] = fam.astype(np.float32)
    f["historia"] = np.full(n, 365.0, dtype=np.float32)
    df = pd.DataFrame({k: np.asarray(v, dtype=np.float64) for k, v in f.items()})
    # Simula tiendas con poca historia (como las de Anaquel): se ocultan los datos viejos al azar.
    hist_dias = rng.choice([14, 21, 28, 35, 56, 90, 112, 365], size=n, p=[.08, .08, .12, .14, .16, .14, .12, .16])
    df["historia"] = hist_dias.astype(np.float32)
    df.loc[hist_dias < 56, "m56"] = np.nan
    df.loc[hist_dias < 112, "m112"] = np.nan
    df.loc[hist_dias < 365, "yoy"] = np.nan
    corta = hist_dias < 28
    if corta.any():  # con menos de 28 días, m28/ceros/max se calculan con lo que hay
        for h in (14, 21):
            sel = hist_dias == h
            df.loc[sel, "m28"] = media(X[sel], corte - h, corte)
            v = np.nan_to_num(X[sel, corte - h:corte])
            df.loc[sel, "ceros28"] = np.mean(v == 0, axis=1)
            df.loc[sel, "max28"] = v.max(axis=1)
            df.loc[sel, "dias_sin_venta"] = np.minimum(df.loc[sel, "dias_sin_venta"], h)
    y = np.nansum(X[:, corte:corte + H], axis=1)
    df["y"] = y
    df["corte"] = corte
    return df[vivo]


def exportar(b: lgb.Booster, nombre, transformar):
    d = b.dump_model()

    def nodo(n):
        if "leaf_value" in n:
            return {"v": n["leaf_value"]}
        assert n["decision_type"] == "<=", n["decision_type"]
        return {"f": n["split_feature"], "t": n["threshold"], "i": n["default_left"], "m": n["missing_type"],
                "a": nodo(n["left_child"]), "b": nodo(n["right_child"])}

    arboles = [nodo(t["tree_structure"]) for t in d["tree_info"]]
    out = {"caracteristicas": CARACTERISTICAS, "transformar": transformar, "arboles": arboles}
    (SALIDA / f"{nombre}.json").write_text(json.dumps(out, separators=(",", ":")))
    return out


def evaluar_arboles(m, x):
    """Igual que el Go: sirve para comprobar que la exportación es correcta."""
    s = 0.0
    for a in m["arboles"]:
        n = a
        while "v" not in n:
            v = x[n["f"]]
            falta = (v is None or (isinstance(v, float) and math.isnan(v)))
            if n["m"] == "Zero" and not falta and v == 0:
                falta = True
            if n["m"] == "None" and falta:
                v, falta = 0.0, False
            if falta:
                n = n["a"] if n["i"] else n["b"]
            else:
                n = n["a"] if v <= n["t"] else n["b"]
        s += n["v"]
    return math.exp(s) if m["transformar"] == "exp" else s


def main():
    SALIDA.mkdir(exist_ok=True)
    X, fam, fechas, festivo = cargar()
    rng = np.random.default_rng(7)
    ultimo = X.shape[1] - H
    prueba_cortes = [ultimo - 28, ultimo]          # los últimos 2 cortes: el «futuro» que el modelo no ve
    entren_cortes = list(range(ultimo - 56, 400, -19))  # cada 19 días: cae en todos los días de la semana y del mes
    t = time.time()
    entren = pd.concat([filas(X, fam, fechas, festivo, c, rng) for c in entren_cortes], ignore_index=True)
    prueba = pd.concat([filas(X, fam, fechas, festivo, c, rng) for c in prueba_cortes], ignore_index=True)
    print(f"Filas: {len(entren):,} de entrenamiento ({len(entren_cortes)} cortes), {len(prueba):,} de prueba  [{time.time() - t:.0f} s]")
    del X

    Xe, ye = entren[CARACTERISTICAS].astype(np.float32), entren["y"].to_numpy()
    Xp, yp = prueba[CARACTERISTICAS].astype(np.float32), prueba["y"].to_numpy()
    comunes = dict(learning_rate=0.05, num_leaves=63, min_data_in_leaf=200, feature_fraction=0.85,
                   bagging_fraction=0.7, bagging_freq=1, lambda_l2=1.0, verbose=-1, num_threads=6)
    de = lgb.Dataset(Xe, ye, categorical_feature=[], free_raw_data=False)
    dp = lgb.Dataset(Xp, yp, reference=de)

    t = time.time()
    p50 = lgb.train({**comunes, "objective": "tweedie", "tweedie_variance_power": 1.2, "metric": "mae"}, de, 1500,
                    valid_sets=[dp], callbacks=[lgb.early_stopping(60, verbose=False), lgb.log_evaluation(100)])
    print(f"p50: {p50.best_iteration} árboles en {time.time() - t:.0f} s")
    t = time.time()
    p90 = lgb.train({**comunes, "objective": "quantile", "alpha": 0.9, "metric": "quantile"}, de, 1500,
                    valid_sets=[dp], callbacks=[lgb.early_stopping(60, verbose=False), lgb.log_evaluation(100)])
    print(f"p90: {p90.best_iteration} árboles en {time.time() - t:.0f} s")

    # ---- Evaluación contra lo que haría un tendero (y el sistema actual): «lo de los últimos 30 días».
    pred = p50.predict(Xp, num_iteration=p50.best_iteration)
    alto = p90.predict(Xp, num_iteration=p90.best_iteration)
    ingenuo = np.nan_to_num(prueba["m28"].to_numpy()) * H
    mae = lambda a: float(np.mean(np.abs(a - yp)))
    res = {
        "filas_entrenamiento": int(len(entren)), "filas_prueba": int(len(prueba)),
        "mae_modelo": mae(pred), "mae_ultimos_28_dias": mae(ingenuo),
        "mejora_pct": 100 * (1 - mae(pred) / mae(ingenuo)),
        "cobertura_p90": float(np.mean(yp <= alto)),
        # Pedir el p90 vs pedir «lo del mes pasado»: cuántas veces te quedas sin mercancía.
        "sin_mercancia_pidiendo_p90": float(np.mean(yp > np.ceil(alto))),
        "sin_mercancia_pidiendo_ultimos_28": float(np.mean(yp > np.ceil(ingenuo))),
        "sobrante_promedio_p90": float(np.mean(np.maximum(np.ceil(alto) - yp, 0))),
        "sobrante_promedio_ultimos_28": float(np.mean(np.maximum(np.ceil(ingenuo) - yp, 0))),
    }
    for h in (14, 28, 56, 365):
        sel = prueba["historia"].to_numpy() == h
        if sel.any():
            res[f"mejora_pct_historia_{h}d"] = 100 * (1 - np.mean(np.abs(pred[sel] - yp[sel])) / np.mean(np.abs(ingenuo[sel] - yp[sel])))
    imp = dict(zip(CARACTERISTICAS, p50.feature_importance("gain").round(0).tolist()))
    res["importancia_p50"] = dict(sorted(imp.items(), key=lambda x: -x[1]))
    print(json.dumps(res, indent=2, ensure_ascii=False))
    (SALIDA / "evaluacion.json").write_text(json.dumps(res, indent=2, ensure_ascii=False))

    p50.save_model(str(SALIDA / "p50.txt"), num_iteration=p50.best_iteration)
    p90.save_model(str(SALIDA / "p90.txt"), num_iteration=p90.best_iteration)
    m50 = exportar(lgb.Booster(model_file=str(SALIDA / "p50.txt")), "p50", "exp")
    m90 = exportar(lgb.Booster(model_file=str(SALIDA / "p90.txt")), "p90", "")
    # Comprobación: los árboles exportados dan lo mismo que LightGBM, y casos fijos para la prueba del Go.
    casos = []
    for i in rng.choice(len(Xp), 300, replace=False):
        x = [None if (isinstance(v, float) and math.isnan(v)) else float(v) for v in Xp.iloc[i].tolist()]
        a, b = evaluar_arboles(m50, x), evaluar_arboles(m90, x)
        assert abs(a - pred[i]) < 1e-6 * max(1, abs(pred[i])) + 1e-6, (a, pred[i])
        assert abs(b - alto[i]) < 1e-6 * max(1, abs(alto[i])) + 1e-6, (b, alto[i])
        casos.append({"x": x, "p50": a, "p90": b})
    (SALIDA / "casos_prueba.json").write_text(json.dumps(casos[:40]))
    print("Exportado y verificado:", [p.name for p in SALIDA.iterdir()])


if __name__ == "__main__":
    main()
