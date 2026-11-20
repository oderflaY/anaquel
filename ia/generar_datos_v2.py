"""Dataset masivo para «tu socio» (el asesor de Anaquel), versión 2.

Amplía generar_datos.py: cada tienda simulada tiene historial DIARIO de ventas
(días de la semana, quincena, tendencia, productos intermitentes, tiendas
nuevas con poca historia) y el pronóstico de 14 días de cada producto lo
calcula EL MISMO modelo de demanda que usa el servidor (LightGBM entrenado con
ventas reales de M5, ver demanda/). La hoja trae las secciones nuevas del
servidor (NÚMEROS DEL NEGOCIO, PRONÓSTICO 14 DÍAS, RECOMENDACIONES DEL GRAFO)
y hay preguntas de decisión: cuánto pedir, qué comprar con un presupuesto,
qué producto nuevo meter, a cuánto vender, plan del día, si voy mejor que el
mes pasado…

    .venv-datos/bin/python generar_datos_v2.py --n 40000 --salida datos/v2/entrenamiento.jsonl
"""
import argparse
import datetime
import json
import math
import os
import random

import lightgbm as lgb
import numpy as np

import generar_datos as v1
from generar_datos import CATALOGO, MESES, Mundo, corto, pesos, pesos_largo, redondear_c, sin_acentos, var

# Mismo texto que promptLocal en backend/asesor_local.go.
PROMPT = ("Eres «tu socio», el amigo que sabe de negocios y asesora a tienditas y negocios de Durango en Anaquel. "
          "Contesta en español de México, cálido y directo, breve (máximo 10 renglones), usando SOLO los datos de la tienda que te doy. "
          "Da cifras exactas (piezas, precios, distancias, porcentajes, pronósticos) tal como aparecen; cuando recomiendes algo, di por qué con un dato. "
          "Si un dato no está, dilo y sugiere qué hacer en Anaquel; nunca inventes productos, precios ni proveedores. "
          "Termina con una acción concreta (pedir en el chat, tocar «Negociar», publicar que lo buscas).")
v1.PROMPT = PROMPT

AQUI = os.path.dirname(os.path.abspath(__file__))
P50 = lgb.Booster(model_file=os.path.join(AQUI, "demanda/modelo/p50.txt"))
P90 = lgb.Booster(model_file=os.path.join(AQUI, "demanda/modelo/p90.txt"))
H = 14


def gr(x):
    """Redondeo como math.Round de Go (mitades hacia arriba)."""
    return int(math.floor(x + 0.5))


def r1(x):
    return math.floor(x * 10 + 0.5) / 10


def miles(n):
    return f"{n:,}"


def familia(cat, giro):
    c = sin_acentos(cat + " " + giro)
    if any(k in c for k in ("limpieza", "higiene", "hogar", "jarceria", "farmacia", "cuidado")):
        return 1
    if any(k in c for k in ("abarrote", "bebida", "botana", "lacteo", "enlatado", "dulce", "pan", "comida", "carne", "fruta",
                            "verdura", "galleta", "reposteria", "cafe", "cereal", "semilla", "salsa", "miel")):
        return 0
    return 2


class Mundo2(Mundo):
    def __init__(self, r: random.Random):
        super().__init__(r)
        self.anio = 2026
        self.hoy = datetime.date(self.anio, self.mes, self.dia)
        # Historia de la tienda: muchas son nuevas en Anaquel (como en la vida real).
        self.hist = r.choice([r.randint(3, 6), r.randint(7, 20), r.randint(21, 45), r.randint(21, 45), r.randint(46, 120), r.randint(120, 400)])
        festivos = [t["faltan"] for t in self.temporadas if t["faltan"] > 0]
        self.festivos14 = sum(1 for f in festivos if f < H)
        self.a_festivo = min(festivos) if festivos else 60
        filas, cuales = [], []
        for p in self.productos:
            self._simular(p, r)
            x = self._caracteristicas(p)
            if x is not None:
                filas.append(x)
                cuales.append(p)
        if filas:
            X = np.array(filas, dtype=np.float64)
            a50 = np.maximum(0, P50.predict(X))
            a90 = np.maximum(a50, P90.predict(X))
            for p, f50, f90, x in zip(cuales, a50, a90, filas):
                p["p50"], p["p90"] = r1(f50), r1(f90)
                p["pedir"] = max(0, math.ceil(f90) - p["stock"])
                p["dias"] = int(p["stock"] / (f50 / H)) if f50 > 0.01 else None
                m28 = x[2]
                p["tend"] = "estable"
                if m28 > 0:
                    rr = x[0] / m28
                    p["tend"] = "sube" if rr >= 1.25 else ("baja" if rr <= 0.75 else "estable")
        self.recos = self._recomendaciones(r)
        self.numeros_lineas = self.numeros()

    # ---------- historial diario ----------
    def _simular(self, p, r):
        hist = self.hist if r.random() < 0.8 else r.randint(min(3, self.hist), self.hist)
        p["hist"] = hist
        base = p["vendidos"] / 30.0
        if base == 0 and r.random() < 0.3:
            base = r.uniform(0.02, 0.15)  # casi no se vende
        tr = r.choice([1, 1, 1, r.uniform(0.5, 0.8), r.uniform(1.25, 1.8)])
        s = []
        for d in range(hist):  # 0 = ayer
            f = self.hoy - datetime.timedelta(days=d + 1)
            w = 1.3 if f.weekday() >= 5 else 1.0
            if f.day in (15, 16, 30, 31, 1, 2):
                w *= 1.2
            t = tr if d < 7 else (1 + (tr - 1) * max(0, 30 - d) / 23)
            lam = base * w * t
            s.append(float(np.random.poisson(lam)) if lam > 0 else 0.0)
        p["serie"] = s
        p["vendidos"] = int(sum(s[:30]))
        p["vendidos_ant"] = int(sum(s[30:60])) if hist >= 60 else None
        p["v7"] = int(sum(s[:7]))

    def _caracteristicas(self, p):
        s, hist = p["serie"], p["hist"]
        if hist < 7:
            return None
        hist = min(hist, 400)
        media = lambda a, b: sum(s[a:b]) / (b - a)
        m = lambda k: media(0, k) if hist >= k else float("nan")
        h28 = min(28, hist)
        v28 = s[:h28]
        sin = min(112, hist)
        for d in range(min(112, hist)):
            if s[d] > 0:
                sin = d
                break
        yoy = float("nan")
        if hist >= 365 + 28 and media(364, 392) > 0:
            yoy = media(350, 364) / media(364, 392)
        x = [m(7), m(14), media(0, h28), m(56), m(112), sum(1 for v in v28 if v == 0) / h28, sin, max(v28),
             self.hoy.weekday(), self.hoy.day, self.hoy.month, self.festivos14, self.a_festivo, yoy,
             familia(p["cat"], self.giro), min(hist, 365)]
        if hist < 14:
            x[1] = media(0, hist)
        return x

    # ---------- recomendaciones del grafo (mismos textos que backend/grafo.go) ----------
    def _precio_txt(self, o):
        t = pesos_largo(o["costo"])
        if o["minimo"] > 1:
            t += f" desde {o['minimo']}"
        return t

    def _recomendaciones(self, r):
        recos, vistos = [], set()
        # surtir
        cands = []
        for p in self.productos:
            os_ = self.ofertas.get(p["nombre"])
            if not os_:
                continue
            tiene = "p50" in p
            if not (p["stock"] <= p["minimo"] or (tiene and p["stock"] < p["p50"] * 10.0 / 14)
                    or (not tiene and p["vendidos"] > 0 and p["stock"] * 30.0 / p["vendidos"] < 10)):
                continue
            cands.append(p)
        cands.sort(key=lambda p: (0 if p["stock"] <= 0 else 1, -p["vendidos"]))
        for p in cands[:8]:
            os_ = self.ofertas[p["nombre"]]
            o = os_[0]
            costo = o["costo"]
            sug = math.ceil(p["vendidos"] / 2)
            if "p50" in p:
                sug = math.ceil(p["p90"])
            sug -= p["stock"]
            sug = max(sug, p["minimo"], o["minimo"])
            if p["stock"] <= 0:
                dias = "Se te acabó"
            elif "p50" in p and p["p50"] > 0:
                dias = (f"Te alcanza para ~{max(1, int(p['stock'] / (p['p50'] / 14)))} días: en 2 semanas venderás unas "
                        f"{gr(p['p50'])} (hasta {math.ceil(p['p90'])})")
            elif p["vendidos"] > 0:
                dias = f"Te alcanza para ~{max(1, p['stock'] * 30 // p['vendidos'])} días"
            else:
                dias = f"Te quedan {p['stock']}"
            motivo = f"{dias}. {o['prov']} lo tiene a {self._precio_txt(o)}"
            if len(os_) > 1:
                motivo += f" (el más barato de {len(os_)} proveedores)"
            g = (p["precio"] - costo) * 100 // p["precio"] if costo < p["precio"] else 0
            sc = 100 + p["vendidos"] + (100 if p["stock"] <= 0 else 0)
            recos.append(dict(tipo="surtir", titulo="Surte " + p["nombre"], motivo=motivo, cantidad=sug, ganancia=g, score=sc,
                              prod=p, oferta=o))
            vistos.add(p["nombre"])
        # exclusivo
        if self.proveedores and r.random() < 0.15:
            prov = r.choice(self.proveedores)
            _, cat = CATALOGO[self.giro]
            n, c, u, pr = r.choice(cat)
            o = dict(prov=prov["nombre"], costo=redondear_c(pr * r.uniform(0.5, 0.8)), minimo=r.choice([6, 12, 24]), km=prov["km"], u=u)
            mio = next((p for p in self.productos if p["nombre"] == n), None)
            g = (mio["precio"] - o["costo"]) * 100 // mio["precio"] if mio and o["costo"] < mio["precio"] else 0
            recos.append(dict(tipo="exclusivo", titulo="Precio solo para ti",
                              motivo=f"{prov['nombre']} te compartió {n} a {self._precio_txt(o)}; nadie más ve ese precio",
                              cantidad=0, ganancia=g, score=90, prod=mio, oferta=o, nombre=n))
        # nuevo (tiendas parecidas)
        _, cat = CATALOGO[self.giro]
        mios = {p["nombre"] for p in self.productos}
        nuevos = [c for c in cat if c[0] not in mios]
        if self.proveedores and nuevos and r.random() < 0.7:
            for n, c, u, pr in r.sample(nuevos, min(len(nuevos), r.randint(1, 4))):
                tiendas = r.choice([1, 1, 2, 2, 3, 4, 5])
                vend = r.randint(8, 90) * tiendas
                par = redondear_c(pr * r.uniform(0.92, 1.1))
                prov = r.choice(self.proveedores)
                o = dict(prov=prov["nombre"], costo=redondear_c(par * r.uniform(0.55, 0.85)), minimo=r.choice([6, 10, 12, 24, 30]),
                         km=prov["km"], u=u)
                quien = (f"{tiendas} tiendas parecidas a la tuya lo venden" if tiendas > 1 else "Una tienda parecida a la tuya lo vende")
                motivo = f"{quien} ({miles(vend)} piezas en 30 días) y tú no. {o['prov']} lo da a {self._precio_txt(o)}; ellas lo venden a ~{pesos_largo(par)}"
                g = (par - o["costo"]) * 100 // par if o["costo"] < par else 0
                recos.append(dict(tipo="nuevo", titulo="Empieza a vender " + n, motivo=motivo, cantidad=max(o["minimo"], 6), ganancia=g,
                                  score=60 + tiendas * 10, nombre=n, oferta=o, tiendas=tiendas, vend=vend, par=par))
        # proveedor de confianza
        if self.proveedores and r.random() < 0.45:
            prov = r.choice(self.proveedores)
            suyos = [(p, o) for p in self.productos for o in self.ofertas.get(p["nombre"], []) if o["prov"] == prov["nombre"]]
            if suyos:
                p, o = max(suyos, key=lambda t: t[0]["vendidos"])
                veces = r.randint(1, 7)
                g = (p["precio"] - o["costo"]) * 100 // p["precio"] if o["costo"] < p["precio"] else 0
                recos.append(dict(tipo="proveedor", titulo="Tu proveedor de confianza",
                                  motivo=f"Ya le compraste {veces} {'vez' if veces == 1 else 'veces'} a {prov['nombre']}. "
                                         f"También tiene {p['nombre']} a {self._precio_txt(o)}",
                                  cantidad=0, ganancia=g, score=50 + veces * 5, prod=p, oferta=o, veces=veces))
        recos.sort(key=lambda x: -x["score"])
        return recos[:8]

    # ---------- números del negocio (mismo cálculo que backend/asesor_plan.go) ----------
    def numeros(self):
        d30 = sum(p["vendidos"] * p["precio"] for p in self.productos)
        pz30 = sum(p["vendidos"] for p in self.productos)
        d7 = sum(p["v7"] * p["precio"] for p in self.productos)
        ant = [p["vendidos_ant"] for p in self.productos if p["vendidos_ant"] is not None]
        dant = sum(p["vendidos_ant"] * p["precio"] for p in self.productos if p["vendidos_ant"] is not None) if ant else 0
        self.d30, self.dant, self.d7 = d30, dant, d7
        out = []
        if dant > 0:
            cambio = int((d30 - dant) * 100 / dant)
            out.append(f"Ventas 30 días: {pesos_largo(d30)} ({cambio:+d}% vs. mes anterior)")
        else:
            out.append(f"Ventas 30 días: {pesos_largo(d30)} ({miles(pz30)} piezas)")
        out.append(f"Al día (promedio): {pesos_largo(d30 // 3000 * 100)} (últimos 7 días: {pesos_largo(d7 // 100 * 100)})")
        valor = sum(p["stock"] * p["precio"] for p in self.productos if p["stock"] > 0)
        parado = sum(p["stock"] * p["precio"] for p in self.productos if p["stock"] > 0 and p["vendidos"] == 0)
        self.valor, self.parado = valor, parado
        l = f"Mercancía en tienda: {pesos_largo(valor)}"
        if valor > 0 and parado > 0:
            l += f" ({parado * 100 // valor}% sin moverse en 30 días)"
        out.append(l)
        con = [p for p in self.productos if "p50" in p]
        if con:
            esperado = sum(int(p["p50"] * p["precio"]) for p in con)
            self.esperado = esperado // 100 * 100
            sube = sum(1 for p in con if p["tend"] == "sube")
            det = "según tu historial y el calendario"
            if sube:
                det = f"{sube} {'producto viene' if sube == 1 else 'productos vienen'} vendiéndose más"
            out.append(f"Venderás en 2 semanas: {pesos_largo(self.esperado)} ({det})")
        else:
            self.esperado = None
        bajo = [p for p in self.productos if p["minimo"] > 0 and p["stock"] <= p["minimo"]]
        out.append(f"Por surtir: {len(bajo)} (productos en su mínimo)")
        return out

    def por_venta(self):
        return sorted(self.productos, key=lambda p: -p["vendidos"])

    def pronostico_lineas(self):
        out = []
        for p in self.por_venta():
            if "p50" not in p or p["p50"] < 1 or len(out) >= 6:
                continue
            l = f"{p['nombre']}: venderás ~{gr(p['p50'])} (hasta {math.ceil(p['p90'])}), tienes {p['stock']}"
            if p["dias"] is not None:
                l += f", te alcanza ~{p['dias']} días"
            if p["pedir"] > 0:
                l += f", pide {p['pedir']}"
            if p["tend"] != "estable":
                l += ", va de " + ("subida" if p["tend"] == "sube" else "bajada")
            out.append(l)
        return out

    def recos_lineas(self):
        out = []
        for x in self.recos[:5]:
            l = x["titulo"] + ": " + x["motivo"]
            if x["cantidad"] > 0:
                l += f"; sugiero {x['cantidad']}"
            if x["ganancia"] > 0:
                l += f" (ganas {x['ganancia']}%)"
            out.append(l)
        return out

    def hoja(self, pregunta_con_claves, objetivo=None):
        base = super().hoja(pregunta_con_claves, objetivo).split("\n")
        extra = []

        def lista(titulo, lineas):
            extra.append(titulo + ":")
            extra.extend("- " + l for l in lineas) if lineas else extra.append("- (nada)")

        lista("NÚMEROS DEL NEGOCIO", self.numeros_lineas)
        lista("PRONÓSTICO 14 DÍAS", self.pronostico_lineas())
        lista("RECOMENDACIONES DEL GRAFO", self.recos_lineas())
        return "\n".join(base[:1] + extra + base[1:])


# ---------------- respuestas de decisión ----------------

def oferta_de(m, p):
    os_ = m.ofertas.get(p["nombre"])
    return os_[0] if os_ else None


def resp_recomienda(m: Mundo2, r):
    rs = m.recos[:5]
    if not rs:
        return ("Revisé tu tienda contra la red y hoy no veo una oportunidad clara: nada se te está acabando y no hay ofertas nuevas para ti. "
                "Registra tus ventas todos los días y te aviso en cuanto algo convenga.")
    ls = [var(r, "Revisé tu tienda contra toda la red. Yo haría esto, en orden:", "Esto es lo que te recomiendo, de lo más urgente a lo menos:",
              "Va, esto es lo que yo compraría:")]
    for i, x in enumerate(rs[:4], 1):
        l = f"{i}. {x['titulo']}: {x['motivo']}"
        if x["cantidad"] > 0:
            l += f". Pide {x['cantidad']}"
        if x["ganancia"] > 0:
            l += f" (ganas {x['ganancia']}%)"
        ls.append(l + ".")
    ls.append(var(r, "Toca «Negociar» en la oferta que te convenga.", "Ábrele el chat al proveedor y arma el pedido.",
                  "Si quieres te ayudo a armar el pedido con tu presupuesto."))
    return "\n".join(ls)


def resp_cuanto(m: Mundo2, r, p):
    if "p50" not in p:
        return (f"Todavía no tengo suficiente historial de {p['nombre']} para pronosticarlo (necesito al menos una semana de ventas). "
                f"Por ahora tienes {p['stock']} y tu mínimo es {p['minimo']}. Registra cada venta y en unos días te digo cuánto pedir.")
    o = oferta_de(m, p)
    ls = [f"En las próximas 2 semanas vas a vender unas {gr(p['p50'])} de {p['nombre']} (hasta {math.ceil(p['p90'])} si se vende bien)."]
    if p["pedir"] <= 0:
        dur = f", te alcanza ~{p['dias']} días" if p["dias"] is not None else ""
        ls.append(f"Tienes {p['stock']}{dur}: no necesitas pedir todavía.")
        ls.append("Revisa otra vez la semana que entra.")
        return "\n".join(ls)
    ls.append(f"Tienes {p['stock']}, así que pide {p['pedir']}: con eso cubres las 2 semanas 9 de cada 10 veces sin quedarte sin mercancía.")
    if o:
        q = p["pedir"]
        if q < o["minimo"]:
            ls.append(f"Ojo: {o['prov']} lo da a {pesos(o['costo'])} c/u pero desde {o['minimo']}. Si pides {o['minimo']} te sobra para más tiempo; "
                      f"si no, cómpralo suelto a {pesos(o['suelto'])}.")
            q = o["minimo"]
        else:
            ls.append(f"Con {o['prov']} a {pesos(o['costo'])} c/u{v1.km(o['km'])}.")
        inv = q * o["costo"]
        l = f"Inviertes {pesos_largo(inv)}"
        if o["costo"] < p["precio"]:
            l += f" y, vendiéndolo a {pesos(p['precio'])}, ganas {pesos_largo((p['precio'] - o['costo']) * q)}"
        ls.append(l + ".")
        ls.append(var(r, "Pídeselo por el chat del proveedor.", "Toca «Negociar» o ábrele el chat."))
    else:
        ls.append(f"Nadie de la red lo tiene en su vitrina ahora; dime «publica que busco {corto(p['nombre'])}» y aviso a los proveedores.")
    return "\n".join(ls)


def resp_dura(m: Mundo2, r, p):
    if "p50" not in p:
        if p["vendidos"] > 0:
            d = max(1, p["stock"] * 30 // p["vendidos"])
            return (f"De {p['nombre']} tienes {p['stock']} y vendiste {p['vendidos']} en 30 días: te alcanza para unos {d} días. "
                    "Todavía no tengo historial suficiente para un pronóstico más fino.")
        return f"De {p['nombre']} tienes {p['stock']}, pero no tengo ventas registradas para calcular cuánto te dura. Anota cada venta en Inventario."
    if p["stock"] <= 0:
        return f"Ya no tienes {p['nombre']}. En 2 semanas venderías unas {gr(p['p50'])}: pide {p['pedir']} cuanto antes para no perder esas ventas."
    if p["dias"] is None:
        return f"De {p['nombre']} tienes {p['stock']} y casi no se vende: te dura mucho. No lo pidas por ahora."
    ls = [f"Tienes {p['stock']} de {p['nombre']} y el pronóstico dice que venderás unas {gr(p['p50'])} en 2 semanas: te alcanza ~{p['dias']} días."]
    if p["dias"] < 7:
        ls.append(f"Es poco: pide {p['pedir']} esta semana.")
    elif p["pedir"] > 0:
        ls.append(f"Para cubrir 2 semanas con seguridad te faltan {p['pedir']}.")
    else:
        ls.append("Vas bien, no necesitas pedir todavía.")
    return " ".join(ls)


def resp_pronostico(m: Mundo2, r):
    con = [p for p in m.por_venta() if "p50" in p and p["p50"] >= 1]
    if m.esperado is None or not con:
        return ("Todavía no tengo suficiente historial para pronosticar tus ventas (necesito al menos una semana de ventas registradas). "
                "Anota cada venta en Inventario y en unos días te digo cuánto vas a vender.")
    ls = [f"En las próximas 2 semanas calculo que venderás unos {pesos_largo(m.esperado)}."]
    ls.append("Lo que más se va a vender: " + ", ".join(f"{p['nombre']} (~{gr(p['p50'])})" for p in con[:3]) + ".")
    sube = [p for p in con if p["tend"] == "sube"]
    baja = [p for p in con if p["tend"] == "baja"]
    if sube:
        ls.append("Viene de subida: " + ", ".join(p["nombre"] for p in sube[:3]) + "; tenlos bien surtidos.")
    if baja:
        ls.append("Va de bajada: " + ", ".join(p["nombre"] for p in baja[:3]) + "; no compres de más.")
    corto_ = [p for p in con if p["dias"] is not None and p["dias"] < 7]
    if corto_:
        p = corto_[0]
        ls.append(f"Ojo con {p['nombre']}: te alcanza ~{p['dias']} días; pide {p['pedir']}.")
    else:
        ls.append("Con lo que tienes cubres bien la semana.")
    return "\n".join(ls)


def resp_presupuesto(m: Mundo2, r, presupuesto):
    items = []
    for x in m.recos:
        if x["tipo"] in ("surtir", "nuevo", "exclusivo") and x.get("oferta"):
            q = x["cantidad"] if x["cantidad"] > 0 else x["oferta"]["minimo"]
            nombre = x["prod"]["nombre"] if x.get("prod") else x.get("nombre")
            items.append((x, nombre, q, x["oferta"]))
    if not items:
        return (f"Con {pesos_largo(presupuesto)} no veo nada urgente que comprar: nada se te acaba y no hay ofertas nuevas para ti. "
                "Guárdalo para cuando suba la venta o pregúntame qué viene de temporada.")
    ls = [f"Con {pesos_largo(presupuesto)} yo lo repartiría así (primero lo urgente):"]
    resto, total = presupuesto, 0
    for x, nombre, q, o in items:
        if resto < o["costo"] * o["minimo"]:
            continue
        q = min(q, resto // o["costo"])
        q = max(q, o["minimo"])
        sub = q * o["costo"]
        if sub > resto:
            continue
        resto -= sub
        total += sub
        why = "se te acaba" if x["tipo"] == "surtir" else ("producto nuevo que se vende en tiendas como la tuya" if x["tipo"] == "nuevo" else "precio exclusivo")
        ls.append(f"- {q} de {nombre} con {o['prov']} a {pesos(o['costo'])}: {pesos_largo(sub)} ({why})")
        if len(ls) >= 6:
            break
    if total == 0:
        x, nombre, q, o = items[0]
        return (f"Con {pesos_largo(presupuesto)} no te alcanza para el mínimo de mayoreo de lo más urgente: {nombre} con {o['prov']} pide "
                f"{o['minimo']} a {pesos(o['costo'])} ({pesos_largo(o['minimo'] * o['costo'])}). Cómpralo suelto o junta un poco más.")
    ls.append(f"Total: {pesos_largo(total)}; te sobran {pesos_largo(resto)} para imprevistos.")
    ls.append("Arma los pedidos desde el chat de cada proveedor.")
    return "\n".join(ls)


def resp_nuevo(m: Mundo2, r):
    nv = [x for x in m.recos if x["tipo"] == "nuevo"]
    if not nv:
        if m.temporadas:
            t = m.temporadas[0]
            return (f"No veo en la red productos que vendan tiendas como la tuya y tú no. Lo que sí viene es {t['nombre']}: "
                    f"{t['sug']}. Pregunta por esos en Inicio.")
        return "Por ahora no veo productos nuevos que te convengan. Concéntrate en no quedarte sin lo que más vendes."
    ls = [var(r, "Esto venden tiendas parecidas a la tuya y tú todavía no:", "Productos que te pueden jalar más clientes:")]
    for x in nv[:3]:
        o = x["oferta"]
        l = f"- {x['nombre']}: {x['motivo'].split(' y tú no')[0].lower()}. {o['prov']} lo da a {pesos(o['costo'])}"
        if x["ganancia"] > 0:
            l += f" y ellas lo venden a ~{pesos(x['par'])} (ganas {x['ganancia']}%)"
        ls.append(l)
    x = nv[0]
    ls.append(f"Empieza con poco: pide {x['cantidad']} de {x['nombre']} y si en 2 semanas se vende, súbele.")
    return "\n".join(ls)


def resp_plan(m: Mundo2, r):
    ls = [var(r, "Tu plan para hoy:", "Esto haría hoy, en orden:", "Va, lo importante de hoy:")]
    i = 1
    urg = [x for x in m.recos if x["tipo"] == "surtir"]
    if urg:
        x = urg[0]
        ls.append(f"{i}. {x['titulo']}: {x['motivo'].split('. ')[0]}. Pide {x['cantidad']}.")
        i += 1
    if m.pendientes:
        ls.append(f"{i}. Contesta tus {m.pendientes} pedidos o tratos pendientes.")
        i += 1
    q = m.quietos()
    if q:
        ls.append(f"{i}. Mueve lo que no se vende ({', '.join(p['nombre'] for p in q[:2])}): promoción o publícalo al mayoreo.")
        i += 1
    if m.temporadas:
        t = m.temporadas[0]
        cuando = "ya empezó" if t["faltan"] == 0 else f"faltan {t['faltan']} días"
        ls.append(f"{i}. Prepárate para {t['nombre']} ({cuando}): {t['sug'].split(', ')[0]}.")
        i += 1
    nv = [x for x in m.recos if x["tipo"] == "nuevo"]
    if nv and i <= 4:
        ls.append(f"{i}. Prueba un producto nuevo: {nv[0]['nombre']}.")
        i += 1
    if i == 1:
        ls.append("Todo en orden: nada se te acaba ni tienes pendientes. Aprovecha para tomar la foto del anaquel.")
    return "\n".join(ls)


def resp_ventas(m: Mundo2, r):
    if m.dant > 0:
        cambio = int((m.d30 - m.dant) * 100 / m.dant)
        if cambio >= 0:
            ls = [f"¡Vas mejor! Llevas {pesos_largo(m.d30)} en 30 días, {cambio:+d}% contra el mes anterior."]
        else:
            ls = [f"Vas un poco abajo: {pesos_largo(m.d30)} en 30 días, {cambio:+d}% contra el mes anterior."]
    else:
        ls = [f"En 30 días llevas {pesos_largo(m.d30)} en ventas. Todavía no tengo un mes anterior completo para comparar."]
    ls.append(f"Al día vendes en promedio {pesos_largo(m.d30 // 3000 * 100)}; en los últimos 7 días, {pesos_largo(m.d7 // 100 * 100)}.")
    top = m.top()
    if top:
        ls.append(f"Tu estrella: {top[0]['nombre']} ({top[0]['vendidos']} vendidos).")
    if m.dant > 0 and m.d30 < m.dant:
        acaba = m.acaba()
        ls.append("Revisa que no te falte lo que más se vende" + (f": {acaba[0]['nombre']} está en su mínimo." if acaba else "."))
    elif m.esperado:
        ls.append(f"Para las próximas 2 semanas calculo {pesos_largo(m.esperado)}.")
    return "\n".join(ls)


def resp_precio_mio(m: Mundo2, r, p):
    o = oferta_de(m, p)
    if not o:
        return (f"Vendes {p['nombre']} a {pesos(p['precio'])}, pero no tengo su costo en la red para calcular tu ganancia. "
                "Busca quién lo vende al mayoreo en Inicio y te digo cuánto le ganas.")
    margen = (p["precio"] - o["costo"]) * 100 // p["precio"] if o["costo"] < p["precio"] else 0
    ls = [f"{p['nombre']}: lo vendes a {pesos(p['precio'])} y lo más barato en la red es {pesos(o['costo'])} con {o['prov']}."]
    if margen < 20:
        nuevo = redondear_c(o["costo"] / 0.75)
        ls.append(f"Le ganas solo {margen}% por pieza. Súbelo a unos {pesos(nuevo)} (25%) o busca un proveedor más barato.")
    elif margen > 55 and p["vendidos"] < 5:
        ls.append(f"Le ganas {margen}%, pero casi no se vende ({p['vendidos']} en 30 días). Prueba bajarlo un poco para que rote.")
    else:
        ls.append(f"Le ganas {pesos(p['precio'] - o['costo'])} por pieza ({margen}%): está bien, déjalo así.")
    ls.append("Recuerda comparar con lo que cobran las tiendas de tu zona.")
    return "\n".join(ls)


def resp_dormido(m: Mundo2, r):
    q = m.quietos()
    if not q:
        return "No tienes dinero dormido: todo lo que tienes se vendió al menos una vez en 30 días. ¡Bien!"
    ls = [f"Tienes {pesos_largo(m.parado)} en mercancía que no se ha vendido en 30 días:"]
    for p in q[:3]:
        ls.append(f"- {p['nombre']}: {p['stock']} a {pesos(p['precio'])}")
    top = m.top()
    ls.append("Tres ideas para sacarlo:")
    ls.append("1. Descuento del 10-15% esta semana.")
    if top:
        ls.append(f"2. Arma un paquete con {top[0]['nombre']}, que es lo que más vendes.")
    else:
        ls.append("2. Ponlo a la vista, junto a la caja.")
    ls.append("3. Publícalo al mayoreo en tu vitrina para que otra tienda te lo compre. Y no vuelvas a pedirlo hasta que se mueva.")
    return "\n".join(ls)


def resp_saludo2(m: Mundo2, r):
    nombre = m.nombre.replace(" (demo)", "")
    extra = ""
    urg = [x for x in m.recos if x["tipo"] == "surtir"]
    if urg:
        extra = f" Por cierto: {urg[0]['titulo'].lower()}, {urg[0]['motivo'].split('. ')[0].lower()}."
    elif m.esperado:
        extra = f" Para las próximas 2 semanas calculo que venderás {pesos_largo(m.esperado)}."
    return (f"¡Qué gusto, {nombre}! Soy tu socio. Te ayudo a decidir qué pedir y cuánto, dónde comprar más barato, "
            f"qué producto nuevo meter y cómo van tus ventas.{extra}")


P_RECOMIENDA = ["¿qué me recomiendas comprar?", "que me recomiendas", "¿qué me conviene?", "dame recomendaciones", "que compro",
                "¿qué oportunidades hay en la red?", "¿qué debería comprar?", "aconsejame que pedir", "¿qué hago para vender más?"]
P_CUANTO = ["¿cuánto pido de {x}?", "cuanto {x} compro", "¿cuántas {x} pido?", "¿cuánto {x} necesito para 2 semanas?", "cuanto pido de {x}"]
P_DURA = ["¿cuánto me dura el {x}?", "¿me alcanza el {x}?", "cuantos dias me dura {x}", "¿para cuánto me alcanza la {x}?"]
P_PRONOSTICO = ["¿cuánto voy a vender?", "pronóstico de ventas", "¿cuánto venderé en las próximas semanas?", "que se va a vender mas",
                "¿cómo pinta la quincena?"]
P_PRESUPUESTO = ["tengo {b} para surtir, ¿qué compro?", "solo traigo {b}, que compro primero", "¿en qué invierto {b}?",
                 "con {b} qué pido", "me sobraron {b}, ¿qué me conviene comprar?"]
P_NUEVO = ["¿qué producto nuevo puedo vender?", "que mas puedo vender", "¿qué le falta a mi tienda?", "quiero meter algo nuevo",
           "¿qué venden otras tiendas que yo no?"]
P_PLAN = ["¿qué hago hoy?", "plan de hoy", "¿por dónde empiezo?", "dame mi plan de la semana", "¿qué es lo más importante hoy?"]
P_VENTAS = ["¿voy mejor que el mes pasado?", "como van mis ventas", "¿estoy vendiendo más o menos?", "cuanto llevo vendido",
            "¿cuánto vendo al día?"]
P_PRECIO_MIO = ["¿a cuánto vendo {x}?", "¿le subo al {x}?", "¿estoy dando muy barato el {x}?", "cuanto le gano al {x}"]
P_DORMIDO = ["tengo mucha mercancía parada", "¿qué hago con lo que no se vende?", "¿cuánto dinero tengo dormido?", "como saco lo que no se mueve"]
P_SALUDO2 = ["hola", "buenas", "qué onda socio", "hola socio", "buenos días"]


def ejemplo2(r: random.Random):
    tipo = r.choices(["v1", "recomienda", "cuanto", "dura", "pronostico", "presupuesto", "nuevo", "plan", "ventas", "precio_mio",
                      "dormido", "saludo"],
                     weights=[34, 10, 9, 5, 6, 7, 6, 6, 6, 5, 4, 2])[0]
    if tipo == "v1":
        # Los tipos de la v1 (surtir, precio en la red, ganancia, temporada…) pero con la hoja nueva.
        m = Mundo2(r)
        v1_mundo = v1.Mundo
        v1.Mundo = lambda _r: m
        try:
            e, intentos = None, 0
            while e is None and intentos < 5:
                e = v1.ejemplo(r)
                intentos += 1
        finally:
            v1.Mundo = v1_mundo
        if e is None:
            return None
        e["messages"][0]["content"] = PROMPT
        e["tipo"] = "v1_" + e["tipo"]
        return e
    m = Mundo2(r)
    claves, objetivo = False, None
    if tipo == "recomienda":
        q, a = r.choice(P_RECOMIENDA), resp_recomienda(m, r)
    elif tipo in ("cuanto", "dura", "precio_mio"):
        ps = [p for p in m.productos if p["vendidos"] > 0 or r.random() < 0.2]
        if not ps:
            return None
        p = r.choice(ps)
        objetivo = corto(p["nombre"])
        claves = True
        plantillas = {"cuanto": P_CUANTO, "dura": P_DURA, "precio_mio": P_PRECIO_MIO}[tipo]
        q = r.choice(plantillas).format(x=objetivo)
        a = {"cuanto": resp_cuanto, "dura": resp_dura, "precio_mio": resp_precio_mio}[tipo](m, r, p)
    elif tipo == "pronostico":
        q, a = r.choice(P_PRONOSTICO), resp_pronostico(m, r)
    elif tipo == "presupuesto":
        b = r.choice([50000, 100000, 150000, 200000, 300000, 500000, 800000, 1000000])
        q = r.choice(P_PRESUPUESTO).format(b=pesos_largo(b) if r.random() < 0.6 else f"{b // 100} pesos")
        a = resp_presupuesto(m, r, b)
    elif tipo == "nuevo":
        q, a = r.choice(P_NUEVO), resp_nuevo(m, r)
    elif tipo == "plan":
        q, a = r.choice(P_PLAN), resp_plan(m, r)
    elif tipo == "ventas":
        q, a = r.choice(P_VENTAS), resp_ventas(m, r)
    elif tipo == "dormido":
        q, a = r.choice(P_DORMIDO), resp_dormido(m, r)
    else:
        q, a = r.choice(P_SALUDO2), resp_saludo2(m, r)
    if r.random() < 0.3:
        q = sin_acentos(q).replace("¿", "").replace("?", "")
    hoja = m.hoja(claves, objetivo)
    return {"messages": [
        {"role": "system", "content": PROMPT},
        {"role": "user", "content": "DATOS DE MI TIENDA:\n" + hoja + "\nPREGUNTA: " + q},
        {"role": "assistant", "content": a},
    ], "tipo": tipo}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=40000)
    ap.add_argument("--semilla", type=int, default=11)
    ap.add_argument("--salida", default="datos/v2/entrenamiento.jsonl")
    args = ap.parse_args()
    r = random.Random(args.semilla)
    np.random.seed(args.semilla)
    os.makedirs(os.path.dirname(args.salida), exist_ok=True)
    n, tipos = 0, {}
    with open(args.salida, "w") as f:
        while n < args.n:
            e = ejemplo2(r)
            if e is None:
                continue
            tipos[e["tipo"]] = tipos.get(e["tipo"], 0) + 1
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
            n += 1
            if n % 5000 == 0:
                print(n, flush=True)
    print(n, "ejemplos →", args.salida, dict(sorted(tipos.items(), key=lambda x: -x[1])))


if __name__ == "__main__":
    main()
