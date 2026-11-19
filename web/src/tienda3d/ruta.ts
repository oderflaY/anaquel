// Ruta a pie dentro de la tienda: A* sobre una cuadrícula de 10 cm que evita
// los muebles (con holgura para una persona), luego se endereza y se dicta en
// pasos ("camina 3 metros", "gira a la izquierda", "está a tu derecha…").
import { alturaHablada, describirLugar, huella, type Hueco, type Plano } from './tipos'

export type Paso = { texto: string; voz: string }
export type Ruta = { puntos: [number, number][]; destino: [number, number]; pasos: Paso[]; metros: number; segundos: number }

const CELDA = 0.1
const HOLGURA = 0.28

type Rejilla = { nx: number; nz: number; libre: Uint8Array }

function rejilla(plano: Plano): Rejilla {
  const nx = Math.ceil(plano.ancho / CELDA), nz = Math.ceil(plano.fondo / CELDA)
  const libre = new Uint8Array(nx * nz).fill(1)
  const tapar = (x0: number, z0: number, x1: number, z1: number) => {
    for (let i = Math.max(0, Math.floor(x0 / CELDA)); i <= Math.min(nx - 1, Math.floor(x1 / CELDA)); i++)
      for (let j = Math.max(0, Math.floor(z0 / CELDA)); j <= Math.min(nz - 1, Math.floor(z1 / CELDA)); j++) libre[j * nx + i] = 0
  }
  // Paredes.
  tapar(0, 0, HOLGURA - 0.1, plano.fondo)
  tapar(plano.ancho - HOLGURA + 0.1, 0, plano.ancho, plano.fondo)
  tapar(0, 0, plano.ancho, HOLGURA - 0.1)
  for (const m of plano.muebles) {
    const h = huella(m)
    tapar(m.x - h.w - HOLGURA, m.z - h.d - HOLGURA, m.x + h.w + HOLGURA, m.z + h.d + HOLGURA)
  }
  return { nx, nz, libre }
}

/** La celda libre más cercana a un punto (si cae dentro de un mueble). */
function cercana(r: Rejilla, x: number, z: number): [number, number] | null {
  const ci = Math.min(r.nx - 1, Math.max(0, Math.round(x / CELDA))), cj = Math.min(r.nz - 1, Math.max(0, Math.round(z / CELDA)))
  for (let rad = 0; rad < 40; rad++) {
    let mejor: [number, number] | null = null, md = Infinity
    for (let i = ci - rad; i <= ci + rad; i++) for (let j = cj - rad; j <= cj + rad; j++) {
      if (i < 0 || j < 0 || i >= r.nx || j >= r.nz || !r.libre[j * r.nx + i]) continue
      const d = (i - ci) ** 2 + (j - cj) ** 2
      if (d < md) { md = d; mejor = [i, j] }
    }
    if (mejor) return mejor
  }
  return null
}

function aEstrella(r: Rejilla, a: [number, number], b: [number, number]): [number, number][] | null {
  const N = r.nx * r.nz
  const g = new Float32Array(N).fill(Infinity)
  const de = new Int32Array(N).fill(-1)
  const cerrado = new Uint8Array(N)
  const inicio = a[1] * r.nx + a[0], fin = b[1] * r.nx + b[0]
  // Montículo binario sencillo.
  const heap: [number, number][] = []
  const push = (n: number, f: number) => {
    heap.push([f, n])
    let i = heap.length - 1
    while (i > 0) { const p = (i - 1) >> 1; if (heap[p][0] <= heap[i][0]) break; [heap[p], heap[i]] = [heap[i], heap[p]]; i = p }
  }
  const pop = () => {
    const top = heap[0], last = heap.pop()!
    if (heap.length) {
      heap[0] = last
      let i = 0
      for (;;) {
        const l = i * 2 + 1, rr = l + 1
        let m = i
        if (l < heap.length && heap[l][0] < heap[m][0]) m = l
        if (rr < heap.length && heap[rr][0] < heap[m][0]) m = rr
        if (m === i) break;
        [heap[m], heap[i]] = [heap[i], heap[m]]
        i = m
      }
    }
    return top[1]
  }
  const hx = (n: number) => Math.hypot((n % r.nx) - b[0], Math.floor(n / r.nx) - b[1])
  g[inicio] = 0
  push(inicio, hx(inicio))
  const vec = [[1, 0, 1], [-1, 0, 1], [0, 1, 1], [0, -1, 1], [1, 1, Math.SQRT2], [1, -1, Math.SQRT2], [-1, 1, Math.SQRT2], [-1, -1, Math.SQRT2]]
  while (heap.length) {
    const n = pop()
    if (n === fin) break
    if (cerrado[n]) continue
    cerrado[n] = 1
    const x = n % r.nx, z = Math.floor(n / r.nx)
    for (const [dx, dz, c] of vec) {
      const i = x + dx, j = z + dz
      if (i < 0 || j < 0 || i >= r.nx || j >= r.nz) continue
      const v = j * r.nx + i
      if (!r.libre[v] || cerrado[v]) continue
      if (dx && dz && (!r.libre[z * r.nx + i] || !r.libre[j * r.nx + x])) continue // sin cortar esquinas
      const ng = g[n] + c
      if (ng < g[v]) { g[v] = ng; de[v] = n; push(v, ng + hx(v)) }
    }
  }
  if (de[fin] < 0 && fin !== inicio) return null
  const camino: [number, number][] = []
  for (let n = fin; n >= 0; n = n === inicio ? -1 : de[n]) camino.push([n % r.nx, Math.floor(n / r.nx)])
  return camino.reverse()
}

/** ¿Se ve b desde a sin pasar por un mueble? */
function seVe(r: Rejilla, a: [number, number], b: [number, number]) {
  const pasos = Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) * 2)
  for (let k = 0; k <= pasos; k++) {
    const i = Math.round(a[0] + ((b[0] - a[0]) * k) / Math.max(1, pasos)), j = Math.round(a[1] + ((b[1] - a[1]) * k) / Math.max(1, pasos))
    if (!r.libre[j * r.nx + i]) return false
  }
  return true
}

/** Endereza el camino: solo deja las esquinas necesarias. */
function enderezar(r: Rejilla, c: [number, number][]) {
  if (c.length < 3) return c
  const out = [c[0]]
  let i = 0
  while (i < c.length - 1) {
    let j = c.length - 1
    while (j > i + 1 && !seVe(r, c[i], c[j])) j--
    out.push(c[j])
    i = j
  }
  return out
}

/** Punto de partida: justo adentro de la puerta, o frente a la caja. */
export function puntoInicio(plano: Plano, desde: 'entrada' | 'caja'): [number, number] {
  if (desde === 'caja') {
    const c = plano.muebles.find((m) => m.tipo === 'mostrador')
    if (c) {
      const t = (c.rot * Math.PI) / 180
      return [c.x + Math.sin(t) * (c.fondo / 2 + 0.6), c.z + Math.cos(t) * (c.fondo / 2 + 0.6)]
    }
  }
  return [plano.puerta.x, plano.fondo - 0.45]
}

function giro(a: [number, number], b: [number, number]) {
  // a y b son direcciones; derecha = (-a.z, a.x) en estos ejes.
  const cruz = b[0] * -a[1] + b[1] * a[0]
  const ang = Math.acos(Math.max(-1, Math.min(1, (a[0] * b[0] + a[1] * b[1]) / (Math.hypot(...a) * Math.hypot(...b)))))
  if (ang < 0.35) return ''
  const lado = cruz > 0 ? 'derecha' : 'izquierda'
  return ang > 2.4 ? 'date la vuelta' : ang < 1.0 ? `ve un poco a la ${lado}` : `gira a la ${lado}`
}

const m = (d: number) => (d < 1.5 ? `${Math.round(d * 10) / 10} metros`.replace('1 metros', '1 metro') : `${Math.round(d)} metros`)

export function trazar(plano: Plano, h: Hueco, nombre: string, desde: 'entrada' | 'caja' = 'entrada'): Ruta | null {
  const r = rejilla(plano)
  const ini = puntoInicio(plano, desde)
  const a = cercana(r, ini[0], ini[1])
  const b = cercana(r, h.acceso[0], h.acceso[1])
  if (!a || !b) return null
  const celdas = aEstrella(r, a, b)
  if (!celdas) return null
  const puntos = enderezar(r, celdas).map(([i, j]) => [i * CELDA, j * CELDA] as [number, number])
  puntos[0] = ini
  let metros = 0
  for (let i = 1; i < puntos.length; i++) metros += Math.hypot(puntos[i][0] - puntos[i - 1][0], puntos[i][1] - puntos[i - 1][1])

  const pasos: Paso[] = []
  // Dirección inicial: desde la entrada se mira hacia el fondo (−z).
  let rumbo: [number, number] = desde === 'entrada' ? [0, -1] : [puntos[1]?.[0] - puntos[0][0] || 0, puntos[1]?.[1] - puntos[0][1] || -1]
  // Tramos casi rectos seguidos se dicen como uno solo.
  const tramos: { gi: string; d: number; dir: [number, number] }[] = []
  for (let i = 1; i < puntos.length; i++) {
    const dir: [number, number] = [puntos[i][0] - puntos[i - 1][0], puntos[i][1] - puntos[i - 1][1]]
    const d = Math.hypot(...dir)
    if (d < 0.15) continue
    const gi = giro(rumbo, dir)
    if (!gi && tramos.length) { const t = tramos[tramos.length - 1]; t.d += d; t.dir = dir }
    else tramos.push({ gi, d, dir })
    rumbo = dir
  }
  tramos.forEach((t, i) => {
    const verbo = i === 0 && !t.gi ? (desde === 'entrada' ? 'Entra y camina' : 'Camina') : t.gi ? `${t.gi[0].toUpperCase()}${t.gi.slice(1)} y camina` : 'Sigue derecho'
    pasos.push({ texto: `${verbo} ${m(t.d)}`, voz: `${verbo} ${m(t.d)}.` })
  })
  // Al llegar: de qué lado queda y a qué altura.
  const fin = puntos[puntos.length - 1]
  const hacia: [number, number] = [h.cx - fin[0], h.cz - fin[1]]
  const cruz = hacia[0] * -rumbo[1] + hacia[1] * rumbo[0]
  const frente = (rumbo[0] * hacia[0] + rumbo[1] * hacia[1]) / (Math.hypot(...rumbo) * Math.hypot(...hacia) || 1)
  const lado = frente > 0.7 ? 'enfrente de ti' : cruz > 0 ? 'a tu derecha' : 'a tu izquierda'
  const altura = h.mueble.tipo === 'perchero' ? 'colgado en el perchero' : h.mueble.tipo === 'mesa' ? 'sobre la mesa' : alturaHablada(h.y + 0.1)
  pasos.push({
    texto: `Llegaste: ${nombre} está ${lado}, ${altura}. ${describirLugar(h)}.`,
    voz: `Llegaste. ${nombre} está ${lado}, ${altura}, en ${h.mueble.nombre}.`,
  })
  return { puntos, destino: [h.cx, h.cz], pasos, metros: Math.round(metros * 10) / 10, segundos: Math.max(3, Math.round(metros / 0.9)) }
}
