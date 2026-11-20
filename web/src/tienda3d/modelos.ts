// Modelos procedurales de la tienda: muebles (góndola, anaquel, refri, rack…)
// y productos con su forma (botella, lata, bolsa, ropa doblada o colgada,
// gorras…). Cada pieza de un producto es un InstancedMesh: una sola llamada
// de dibujo aunque haya 60 refrescos en el entrepaño.
import * as THREE from 'three'
import { entrepanos, estadoDe, normalizar, pesos, type Hueco, type Lugar, type Mueble } from './tipos'

export const mat = (color: number, extra: THREE.MeshStandardMaterialParameters = {}) =>
  new THREE.MeshStandardMaterial({ color, roughness: 0.75, metalness: 0.05, ...extra })

/** Pseudoaleatorio estable (la escena no "tiembla" al recargar). */
export function rnd(seed: number) {
  let s = Math.abs(Math.floor(seed)) % 2147483647 || 1
  return () => ((s = (s * 16807) % 2147483647) - 1) / 2147483646
}
const hashTexto = (t: string) => { let h = 7; for (const c of t) h = (h * 31 + c.charCodeAt(0)) | 0; return Math.abs(h) }

export function sombras<T extends THREE.Object3D>(o: T, recibe = true): T {
  o.traverse((x) => { if (x instanceof THREE.Mesh) { x.castShadow = true; x.receiveShadow = recibe } })
  return o
}

function caja(w: number, h: number, d: number, m: THREE.Material, x = 0, y = 0, z = 0) {
  const o = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), m)
  o.position.set(x, y, z)
  return o
}

// ---------------------------------------------------------------- texturas

function lienzo(w: number, h: number, dibujar: (c: CanvasRenderingContext2D) => void) {
  const cv = document.createElement('canvas')
  cv.width = w
  cv.height = h
  dibujar(cv.getContext('2d')!)
  const t = new THREE.CanvasTexture(cv)
  t.colorSpace = THREE.SRGBColorSpace
  t.anisotropy = 4
  return t
}

function partirTexto(c: CanvasRenderingContext2D, texto: string, ancho: number, maxLineas: number) {
  const ws = texto.split(' ')
  const out: string[] = []
  let l = ''
  for (const w of ws) {
    const p = l ? l + ' ' + w : w
    if (c.measureText(p).width > ancho && l) { out.push(l); l = w } else l = p
  }
  if (l) out.push(l)
  if (out.length > maxLineas) { out.length = maxLineas; out[maxLineas - 1] = out[maxLineas - 1].replace(/\s*\S*$/, '') + '…' }
  return out
}

const COLOR_ESTADO = { agotado: '#d64532', bajo: '#f2b233', bien: '#0e7c5a', sin: '#7a8a82' }

/** Etiqueta de precio del entrepaño: franja con el estado, nombre y precio. */
export function texturaPrecio(p: Lugar, conEstado = true) {
  return lienzo(320, 140, (c) => {
    c.fillStyle = '#fffef6'
    c.fillRect(0, 0, 320, 140)
    c.fillStyle = conEstado ? COLOR_ESTADO[estadoDe(p)] : '#0e7c5a'
    c.fillRect(0, 0, 320, 18)
    c.fillStyle = '#1d2a24'
    c.font = '600 26px system-ui, sans-serif'
    partirTexto(c, p.nombre, 300, 2).forEach((l, i) => c.fillText(l, 10, 50 + i * 28))
    c.font = '800 40px system-ui, sans-serif'
    c.textAlign = 'right'
    c.fillStyle = '#c0392b'
    c.fillText(pesos(p.precio_c), 310, 128)
    if (p.publicado) {
      c.textAlign = 'left'
      c.font = '700 18px system-ui, sans-serif'
      c.fillStyle = '#2f5da8'
      c.fillText('EN LA RED', 10, 126)
    }
  })
}

/** Letrero con texto (copete de góndola, rótulo de la fachada). */
export function texturaLetrero(texto: string, fondo: string, color = '#ffffff', w = 1024, h = 160, sub = '') {
  return lienzo(w, h, (c) => {
    c.fillStyle = fondo
    c.fillRect(0, 0, w, h)
    c.fillStyle = color
    c.textAlign = 'center'
    c.textBaseline = 'middle'
    let tam = Math.round(h * (sub ? 0.42 : 0.55))
    c.font = `800 ${tam}px system-ui, sans-serif`
    while (c.measureText(texto).width > w * 0.92 && tam > 12) { tam -= 2; c.font = `800 ${tam}px system-ui, sans-serif` }
    c.fillText(texto, w / 2, sub ? h * 0.38 : h / 2)
    if (sub) {
      c.font = `600 ${Math.round(h * 0.2)}px system-ui, sans-serif`
      c.globalAlpha = 0.85
      c.fillText(sub, w / 2, h * 0.78)
    }
  })
}

export function texturaPiso(tipo: 'mosaico' | 'cemento' | 'madera') {
  const t = lienzo(256, 256, (c) => {
    if (tipo === 'mosaico') {
      for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) {
        c.fillStyle = (i + j) % 2 ? '#e9e5dc' : '#d9d3c7'
        c.fillRect(i * 64, j * 64, 64, 64)
      }
      c.strokeStyle = '#b9b2a4'
      c.lineWidth = 2
      for (let i = 0; i <= 4; i++) { c.beginPath(); c.moveTo(i * 64, 0); c.lineTo(i * 64, 256); c.stroke(); c.beginPath(); c.moveTo(0, i * 64); c.lineTo(256, i * 64); c.stroke() }
    } else if (tipo === 'madera') {
      const r = rnd(5)
      for (let j = 0; j < 8; j++) {
        const tono = 150 + Math.floor(r() * 30)
        c.fillStyle = `rgb(${tono + 40},${tono},${tono - 55})`
        c.fillRect(0, j * 32, 256, 32)
        c.fillStyle = 'rgba(70,40,10,.35)'
        c.fillRect(0, j * 32, 256, 2)
        c.fillRect(Math.floor(r() * 256), j * 32, 2, 32)
        for (let k = 0; k < 6; k++) { c.fillStyle = 'rgba(90,50,20,.12)'; c.fillRect(Math.floor(r() * 256), j * 32 + 6 + Math.floor(r() * 20), 40 + r() * 60, 1) }
      }
    } else {
      c.fillStyle = '#b8b8b2'
      c.fillRect(0, 0, 256, 256)
      const r = rnd(9)
      for (let k = 0; k < 2500; k++) { const g = 150 + Math.floor(r() * 70); c.fillStyle = `rgba(${g},${g},${g - 6},.35)`; c.fillRect(r() * 256, r() * 256, 2, 2) }
      c.strokeStyle = 'rgba(90,90,90,.35)'
      c.strokeRect(0, 0, 256, 256)
    }
  })
  t.wrapS = t.wrapT = THREE.RepeatWrapping
  return t
}

function texturaPerforada() {
  const t = lienzo(64, 64, (c) => {
    c.fillStyle = '#e4e7ea'
    c.fillRect(0, 0, 64, 64)
    c.fillStyle = '#b8bec5'
    for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) { c.beginPath(); c.arc(8 + i * 16, 8 + j * 16, 2.4, 0, Math.PI * 2); c.fill() }
  })
  t.wrapS = t.wrapT = THREE.RepeatWrapping
  return t
}

export function texturaToldo(color: string) {
  const t = lienzo(128, 32, (c) => {
    for (let i = 0; i < 8; i++) { c.fillStyle = i % 2 ? '#ffffff' : color; c.fillRect(i * 16, 0, 16, 32) }
  })
  t.wrapS = THREE.RepeatWrapping
  return t
}

// ---------------------------------------------------------------- colores de producto

const PALETA = [0xd64532, 0x2f7fd0, 0x0e7c5a, 0xf2b233, 0x8e44ad, 0xe67e22, 0x16a085, 0xc0392b, 0x2c3e50, 0xd35400, 0x27ae60, 0x2980b9]
const COLORES: [RegExp, number][] = [
  [/cola/, 0x7a1414], [/naranja/, 0xf07d14], [/manzana/, 0x7cbc3c], [/agua/, 0x9fd3f2], [/leche/, 0xf4f6fb], [/yogur|fresa/, 0xf28cb1],
  [/crema/, 0xf5f0dc], [/jamon/, 0xf2a7a0], [/queso/, 0xf8f1d0], [/huevo/, 0xc9b28d], [/miel/, 0xd99a1c], [/cafe/, 0x5a3a22],
  [/arroz/, 0xf2f2ea], [/frijol/, 0x8a4f3a], [/azucar|sal /, 0xfafafa], [/harina/, 0xf0dca0], [/aceite/, 0xf2cf3a], [/atun/, 0x2f6fb0],
  [/jalapeno|chile/, 0x3c8c2c], [/tomate/, 0xc8261c], [/cereal|maiz/, 0xf2b233], [/avena/, 0xc6a46a], [/mayonesa/, 0xf6f0d8],
  [/cloro/, 0x3d8fd6], [/detergente/, 0x3a6fd8], [/jabon/, 0x6bb7e8], [/shampoo/, 0xa66bd8], [/dental/, 0xe8eef8], [/papel hig|servilleta/, 0xffffff],
  [/papas/, 0xf6c813], [/churrito/, 0xe8452c], [/cacahuate/, 0xd6451f], [/palomita/, 0xe8d24a], [/chocolate|galleta/, 0x6b3a1e],
  [/chicle/, 0x3dc0a8], [/paleta/, 0xe8457a], [/tostada/, 0xe5b867], [/pasta/, 0xf2c94c],
  [/mezclilla|jeans/, 0x34507a], [/sudadera/, 0x6d7480], [/bufanda/, 0xb03a48], [/pijama/, 0xc9a0dc], [/playera/, 0xf4f4f4], [/short/, 0x2a9d8f],
  [/chamarra/, 0x4b6584], [/gorra beisbolera/, 0x1f3a93], [/trucker/, 0xc0392b], [/gorro/, 0x8e44ad], [/chaleco/, 0x2d3436], [/sueter|navide/, 0xb71c1c],
  [/guante/, 0x5d4037], [/cuaderno/, 0x2f7fd0], [/china/, 0xe84393], [/picado/, 0x00b894],
]
export function colorDe(nombre: string): number {
  const n = normalizar(nombre) + ' '
  for (const [re, c] of COLORES) if (re.test(n)) return c
  return PALETA[hashTexto(n) % PALETA.length]
}

// ---------------------------------------------------------------- formas de producto

export type Forma = 'botella' | 'botellaGrande' | 'lata' | 'carton' | 'bolsa' | 'bolsaKilo' | 'caja' | 'cajaChica' | 'frasco' | 'huevo'
  | 'queso' | 'charola' | 'barra' | 'rollo' | 'paleta' | 'vaso' | 'limpieza' | 'ropaDoblada' | 'ropaColgada' | 'gorra' | 'gorro'
  | 'bufanda' | 'guantes' | 'cuaderno' | 'papel' | 'corrugado'

export function formaDe(p: Pick<Lugar, 'nombre' | 'categoria' | 'unidad'>, tipoMueble: Mueble['tipo']): Forma {
  if (tipoMueble === 'rack' || tipoMueble === 'tarima') return 'corrugado'
  const n = ' ' + normalizar(p.nombre + ' ' + p.categoria) + ' '
  const nombre = ' ' + normalizar(p.nombre) + ' '
  const colgable = /chamarra|sudadera|sueter|chaleco|vestido|camisa|blusa|abrigo|pijama|uniforme/.test(nombre)
  if (tipoMueble === 'perchero') return 'ropaColgada'
  if (colgable || /playera|jeans|short|pantalon|falda/.test(nombre)) return 'ropaDoblada'
  if (/gorra/.test(nombre)) return 'gorra'
  if (/gorro/.test(nombre)) return 'gorro'
  if (/bufanda/.test(nombre)) return 'bufanda'
  if (/guante|calceta/.test(nombre)) return 'guantes'
  if (/cuaderno|libreta|libro/.test(nombre)) return 'cuaderno'
  if (/papel china|papel picado|cartulina|papel /.test(nombre) && !/higienico/.test(nombre)) return 'papel'
  if (/huevo/.test(nombre)) return 'huevo'
  if (/ 2 l |2 litros|garrafon/.test(nombre)) return 'botellaGrande'
  if (/leche|jugo de naranja|jugo 1|pure /.test(nombre)) return 'carton'
  if (/refresco|agua|jugo|cerveza|bebible|aceite|vinagre/.test(nombre)) return 'botella'
  if (/cloro|shampoo|suavizante|limpiador|jabon liquido/.test(nombre)) return 'limpieza'
  if (/lata|atun|sardina|chiles|refritos/.test(nombre) || p.unidad === 'lata') return 'lata'
  if (/miel|cafe|mayonesa|mermelada|salsa|frasco/.test(nombre) || p.unidad === 'frasco') return 'frasco'
  if (/queso/.test(nombre)) return 'queso'
  if (/jamon|salchicha|tocino/.test(nombre)) return 'charola'
  if (/crema|yogur(?! bebible)/.test(nombre)) return 'vaso'
  if (/jabon de barra|chocolate de barra|barra/.test(nombre)) return 'barra'
  if (/papel higienico|rollo/.test(nombre)) return 'rollo'
  if (/paleta/.test(nombre)) return 'paleta'
  if (/arroz|frijol|azucar|sal de|harina|pasta para|avena|detergente|tostada/.test(nombre)) return 'bolsaKilo'
  if (/papas|churrito|cacahuate|botana|fritura|bolsa|servilleta/.test(n) || p.unidad === 'bolsa') return 'bolsa'
  if (/chicle|pasta dental|cajita|pila/.test(nombre)) return 'cajaChica'
  return 'caja'
}

/** Una pieza de un artículo: geometría, material y su lugar dentro del artículo. */
type Pieza = { geo: THREE.BufferGeometry; m: THREE.Material; x?: number; y?: number; z?: number; rx?: number; ry?: number; rz?: number; colorPorPieza?: boolean }
/** Artículo: medidas (ancho, alto, fondo), cuántos se apilan y sus piezas. */
type Articulo = { w: number; h: number; d: number; pila: number; piezas: Pieza[] }

const GEO = new Map<string, THREE.BufferGeometry>()
const COMPARTIDAS = new Set<THREE.BufferGeometry>()
const g = <T extends THREE.BufferGeometry>(k: string, f: () => T): T => {
  if (!GEO.has(k)) { const x = f(); GEO.set(k, x); COMPARTIDAS.add(x) }
  return GEO.get(k) as T
}
/** Las geometrías de artículos se reusan entre productos: no se liberan al redibujar. */
export const esCompartida = (geo: THREE.BufferGeometry) => COMPARTIDAS.has(geo)

export function articulo(forma: Forma, color: number, nombre: string): Articulo {
  const c2 = new THREE.Color(color).offsetHSL(0, 0, -0.18).getHex()
  switch (forma) {
    case 'botella': case 'botellaGrande': {
      const k = forma === 'botellaGrande' ? 1.45 : /1 l/.test(normalizar(nombre)) ? 1.22 : 1
      const agua = /agua|aceite/.test(normalizar(nombre))
      const cuerpo = mat(agua ? color : c2, { transparent: true, opacity: agua ? 0.55 : 0.85, roughness: 0.15 })
      return {
        w: 0.072 * k, h: 0.24 * k, d: 0.072 * k, pila: 1, piezas: [
          { geo: g('bot-c', () => new THREE.CylinderGeometry(0.033, 0.033, 0.15, 14)), m: cuerpo, y: 0.075 * k },
          { geo: g('bot-h', () => new THREE.CylinderGeometry(0.014, 0.033, 0.06, 14)), m: cuerpo, y: 0.18 * k },
          { geo: g('bot-e', () => new THREE.CylinderGeometry(0.0336, 0.0336, 0.06, 14)), m: mat(color, { roughness: 0.4 }), y: 0.08 * k },
          { geo: g('bot-t', () => new THREE.CylinderGeometry(0.0145, 0.0145, 0.022, 10)), m: mat(agua ? 0x2f7fd0 : 0xd8d8d8, { roughness: 0.3 }), y: 0.22 * k },
        ].map((p) => ({ ...p, geo: k === 1 ? p.geo : escalar(p.geo, k) })),
      }
    }
    case 'lata':
      return {
        w: 0.08, h: 0.105, d: 0.08, pila: 3, piezas: [
          { geo: g('lata', () => new THREE.CylinderGeometry(0.038, 0.038, 0.1, 18)), m: mat(color, { metalness: 0.3, roughness: 0.4 }), y: 0.05 },
          { geo: g('lata-t', () => new THREE.CylinderGeometry(0.036, 0.036, 0.006, 18)), m: mat(0xc8ccd0, { metalness: 0.8, roughness: 0.25 }), y: 0.102 },
        ],
      }
    case 'carton':
      return {
        w: 0.075, h: 0.24, d: 0.075, pila: 1, piezas: [
          { geo: g('cart', () => new THREE.BoxGeometry(0.07, 0.2, 0.07)), m: mat(0xffffff, { roughness: 0.6 }), y: 0.1 },
          { geo: g('cart-b', () => new THREE.BoxGeometry(0.071, 0.08, 0.071)), m: mat(color === 0xf4f6fb ? 0x2f6fd0 : color, { roughness: 0.6 }), y: 0.09 },
          { geo: g('cart-t', () => new THREE.BoxGeometry(0.05, 0.05, 0.05)), m: mat(0xffffff), y: 0.2, rx: Math.PI / 4 },
        ],
      }
    case 'bolsa':
      return {
        w: 0.15, h: 0.22, d: 0.06, pila: 1, piezas: [
          { geo: g('bolsa', () => inflar(new THREE.BoxGeometry(0.14, 0.2, 0.05, 4, 4, 2), 0.022)), m: mat(color, { roughness: 0.35, metalness: 0.25 }), y: 0.11 },
          { geo: g('bolsa-s', () => new THREE.BoxGeometry(0.142, 0.012, 0.012)), m: mat(c2, { metalness: 0.3 }), y: 0.215 },
          { geo: g('bolsa-v', () => new THREE.CircleGeometry(0.035, 16)), m: mat(0xffffff, { roughness: 0.5 }), y: 0.12, z: 0.034 },
        ],
      }
    case 'bolsaKilo':
      return {
        w: 0.12, h: 0.2, d: 0.08, pila: 2, piezas: [
          { geo: g('kilo', () => inflar(new THREE.BoxGeometry(0.11, 0.18, 0.075, 3, 4, 3), 0.012)), m: mat(color, { roughness: 0.55 }), y: 0.09 },
          { geo: g('kilo-e', () => new THREE.BoxGeometry(0.112, 0.06, 0.077)), m: mat(color === 0xfafafa || color === 0xf2f2ea ? 0x2f7fd0 : 0xffffff, { roughness: 0.6 }), y: 0.1 },
        ],
      }
    case 'caja':
      return {
        w: 0.19, h: 0.27, d: 0.065, pila: 1, piezas: [
          { geo: g('caja', () => new THREE.BoxGeometry(0.18, 0.26, 0.06)), m: mat(color, { roughness: 0.6 }), y: 0.13 },
          { geo: g('caja-f', () => new THREE.PlaneGeometry(0.12, 0.1)), m: mat(0xffffff, { roughness: 0.6 }), y: 0.15, z: 0.031 },
        ],
      }
    case 'cajaChica':
      return {
        w: 0.17, h: 0.05, d: 0.05, pila: 5, piezas: [
          { geo: g('cch', () => new THREE.BoxGeometry(0.16, 0.045, 0.045)), m: mat(color, { roughness: 0.5 }), y: 0.0225 },
          { geo: g('cch-f', () => new THREE.BoxGeometry(0.06, 0.046, 0.046)), m: mat(0xffffff), y: 0.0225, x: 0.04 },
        ],
      }
    case 'frasco':
      return {
        w: 0.085, h: 0.13, d: 0.085, pila: 2, piezas: [
          { geo: g('fra', () => new THREE.CylinderGeometry(0.04, 0.04, 0.105, 16)), m: mat(color, { transparent: true, opacity: 0.85, roughness: 0.1 }), y: 0.0525 },
          { geo: g('fra-e', () => new THREE.CylinderGeometry(0.0405, 0.0405, 0.045, 16)), m: mat(0xfaf3e0), y: 0.05 },
          { geo: g('fra-t', () => new THREE.CylinderGeometry(0.037, 0.037, 0.02, 16)), m: mat(0xc9a227, { metalness: 0.7, roughness: 0.3 }), y: 0.115 },
        ],
      }
    case 'huevo': {
      const piezas: Pieza[] = [{ geo: g('hue-c', () => new THREE.BoxGeometry(0.3, 0.06, 0.11)), m: mat(0xb8b0a2, { roughness: 1 }), y: 0.03 }]
      const huevo = mat(color === 0xc9b28d ? 0xe9d6b8 : color, { roughness: 0.8 })
      for (let i = 0; i < 6; i++) for (let j = 0; j < 2; j++) piezas.push({ geo: g('hue', () => new THREE.SphereGeometry(0.022, 10, 8)), m: huevo, x: -0.125 + i * 0.05, y: 0.065, z: -0.025 + j * 0.05 })
      return { w: 0.31, h: 0.09, d: 0.12, pila: 3, piezas }
    }
    case 'queso':
      return {
        w: 0.15, h: 0.06, d: 0.15, pila: 3, piezas: [
          { geo: g('que', () => new THREE.CylinderGeometry(0.07, 0.07, 0.05, 20)), m: mat(color, { roughness: 0.9 }), y: 0.025 },
          { geo: g('que-e', () => new THREE.CylinderGeometry(0.0705, 0.0705, 0.018, 20)), m: mat(0x0e7c5a), y: 0.025 },
        ],
      }
    case 'charola':
      return {
        w: 0.15, h: 0.03, d: 0.11, pila: 4, piezas: [
          { geo: g('cha', () => new THREE.BoxGeometry(0.14, 0.022, 0.1)), m: mat(color, { roughness: 0.5 }), y: 0.011 },
          { geo: g('cha-t', () => new THREE.BoxGeometry(0.145, 0.026, 0.105)), m: mat(0xffffff, { transparent: true, opacity: 0.25, roughness: 0.1 }), y: 0.013 },
        ],
      }
    case 'vaso':
      return {
        w: 0.1, h: 0.09, d: 0.1, pila: 2, piezas: [
          { geo: g('vas', () => new THREE.CylinderGeometry(0.045, 0.038, 0.08, 16)), m: mat(0xffffff, { roughness: 0.5 }), y: 0.04 },
          { geo: g('vas-t', () => new THREE.CylinderGeometry(0.047, 0.047, 0.008, 16)), m: mat(color === 0xf5f0dc ? 0x2f7fd0 : color, { metalness: 0.4, roughness: 0.3 }), y: 0.084 },
        ],
      }
    case 'barra':
      return {
        w: 0.1, h: 0.035, d: 0.065, pila: 6, piezas: [
          { geo: g('bar', () => new THREE.BoxGeometry(0.095, 0.03, 0.06)), m: mat(color, { roughness: 0.5 }), y: 0.015 },
          { geo: g('bar-f', () => new THREE.BoxGeometry(0.04, 0.031, 0.061)), m: mat(0xffffff), y: 0.015 },
        ],
      }
    case 'rollo': {
      const blanco = mat(0xffffff, { roughness: 0.95 })
      const piezas: Pieza[] = []
      for (let i = 0; i < 2; i++) for (let j = 0; j < 2; j++) piezas.push({ geo: g('rol', () => new THREE.CylinderGeometry(0.052, 0.052, 0.1, 16)), m: blanco, x: -0.053 + i * 0.106, y: 0.05, z: -0.053 + j * 0.106 })
      piezas.push({ geo: g('rol-p', () => new THREE.BoxGeometry(0.22, 0.104, 0.22)), m: mat(0x8fc4f0, { transparent: true, opacity: 0.35, roughness: 0.2 }), y: 0.052 })
      return { w: 0.23, h: 0.11, d: 0.23, pila: 3, piezas }
    }
    case 'paleta':
      return {
        w: 0.045, h: 0.13, d: 0.045, pila: 1, piezas: [
          { geo: g('pal-p', () => new THREE.CylinderGeometry(0.003, 0.003, 0.09, 6)), m: mat(0xffffff), y: 0.045 },
          { geo: g('pal-d', () => new THREE.SphereGeometry(0.02, 12, 10)), m: mat(color, { roughness: 0.2 }), y: 0.105, colorPorPieza: true },
        ],
      }
    case 'limpieza':
      return {
        w: 0.1, h: 0.27, d: 0.07, pila: 1, piezas: [
          { geo: g('lim', () => inflar(new THREE.BoxGeometry(0.09, 0.22, 0.06, 3, 4, 3), 0.01)), m: mat(color, { roughness: 0.3 }), y: 0.11 },
          { geo: g('lim-t', () => new THREE.CylinderGeometry(0.016, 0.02, 0.04, 10)), m: mat(0xffffff, { roughness: 0.3 }), y: 0.24 },
          { geo: g('lim-e', () => new THREE.PlaneGeometry(0.06, 0.08)), m: mat(0xffffff), y: 0.11, z: 0.032 },
        ],
      }
    case 'ropaDoblada':
      return {
        w: 0.32, h: 0.045, d: 0.26, pila: 8, piezas: [
          { geo: g('dob', () => inflar(new THREE.BoxGeometry(0.3, 0.04, 0.24, 4, 1, 4), 0.006)), m: mat(color, { roughness: 1 }), y: 0.02, colorPorPieza: true },
          { geo: g('dob-c', () => new THREE.BoxGeometry(0.1, 0.012, 0.02)), m: mat(c2, { roughness: 1 }), y: 0.042, z: 0.1 },
        ],
      }
    case 'ropaColgada': {
      const tela = mat(color, { roughness: 1, side: THREE.DoubleSide })
      const capucha = /sudadera/.test(normalizar(nombre))
      const largo = /chamarra|sueter|sudadera|chaleco/.test(normalizar(nombre)) ? 0.62 : 0.9
      const piezas: Pieza[] = [
        { geo: g('gan', () => new THREE.TorusGeometry(0.2, 0.006, 4, 16, Math.PI)), m: mat(0x5d4037, { roughness: 0.6 }), y: -0.06, ry: Math.PI / 2 },
        { geo: g('gan-g', () => new THREE.TorusGeometry(0.02, 0.003, 4, 10, Math.PI * 1.4)), m: mat(0xb0b6bc, { metalness: 0.8 }), y: 0.0, ry: Math.PI / 2 },
        { geo: g(`ropa-${largo}`, () => inflar(new THREE.BoxGeometry(0.035, largo, 0.42, 1, 6, 4), 0.01)), m: tela, y: -0.06 - largo / 2, colorPorPieza: true },
      ]
      if (!/chaleco/.test(normalizar(nombre))) piezas.push({ geo: g(`manga-${largo}`, () => new THREE.BoxGeometry(0.03, largo * 0.85, 0.08)), m: tela, y: -0.08 - largo * 0.42, z: 0.24, rx: -0.12, colorPorPieza: true }, { geo: g(`manga-${largo}`, () => new THREE.BoxGeometry(0.03, largo * 0.85, 0.08)), m: tela, y: -0.08 - largo * 0.42, z: -0.24, rx: 0.12, colorPorPieza: true })
      if (capucha) piezas.push({ geo: g('capucha', () => new THREE.SphereGeometry(0.09, 10, 8, 0, Math.PI * 2, 0, Math.PI / 2)), m: tela, y: -0.05, rz: Math.PI / 2, x: 0.01, colorPorPieza: true })
      return { w: 0.07, h: largo + 0.1, d: 0.5, pila: 1, piezas }
    }
    case 'gorra':
      return {
        w: 0.21, h: 0.03, d: 0.27, pila: 8, piezas: [
          { geo: g('gor', () => new THREE.SphereGeometry(0.095, 16, 8, 0, Math.PI * 2, 0, Math.PI / 2)), m: mat(color, { roughness: 0.9 }), y: 0, colorPorPieza: true },
          { geo: g('gor-v', () => new THREE.CylinderGeometry(0.075, 0.075, 0.008, 16, 1, false, -Math.PI / 2, Math.PI)), m: mat(c2, { roughness: 0.8 }), y: 0.004, z: 0.085, colorPorPieza: false },
          { geo: g('gor-b', () => new THREE.SphereGeometry(0.012, 8, 6)), m: mat(c2), y: 0.094 },
        ],
      }
    case 'gorro':
      return {
        w: 0.18, h: 0.16, d: 0.18, pila: 1, piezas: [
          { geo: g('gorro', () => new THREE.SphereGeometry(0.085, 14, 10, 0, Math.PI * 2, 0, Math.PI * 0.55)), m: mat(color, { roughness: 1 }), y: 0.0, colorPorPieza: true },
          { geo: g('gorro-d', () => new THREE.CylinderGeometry(0.087, 0.087, 0.035, 14)), m: mat(c2, { roughness: 1 }), y: 0.0, colorPorPieza: false },
          { geo: g('gorro-p', () => new THREE.SphereGeometry(0.035, 10, 8)), m: mat(0xffffff, { roughness: 1 }), y: 0.105 },
        ],
      }
    case 'bufanda':
      return {
        w: 0.22, h: 0.11, d: 0.12, pila: 3, piezas: [
          { geo: g('buf', () => new THREE.CylinderGeometry(0.055, 0.055, 0.2, 14)), m: mat(color, { roughness: 1 }), y: 0.055, rz: Math.PI / 2, colorPorPieza: true },
          { geo: g('buf-f', () => new THREE.BoxGeometry(0.06, 0.004, 0.1)), m: mat(c2, { roughness: 1 }), y: 0.0, x: 0.08, z: 0.06 },
        ],
      }
    case 'guantes':
      return {
        w: 0.13, h: 0.04, d: 0.2, pila: 5, piezas: [
          { geo: g('gua', () => inflar(new THREE.BoxGeometry(0.055, 0.03, 0.19, 1, 1, 3), 0.006)), m: mat(color, { roughness: 1 }), x: -0.03, y: 0.015, colorPorPieza: true },
          { geo: g('gua', () => inflar(new THREE.BoxGeometry(0.055, 0.03, 0.19, 1, 1, 3), 0.006)), m: mat(color, { roughness: 1 }), x: 0.03, y: 0.015, colorPorPieza: true },
        ],
      }
    case 'cuaderno':
      return {
        w: 0.22, h: 0.014, d: 0.29, pila: 14, piezas: [
          { geo: g('cua', () => new THREE.BoxGeometry(0.21, 0.012, 0.28)), m: mat(color, { roughness: 0.5 }), y: 0.006, colorPorPieza: true },
          { geo: g('cua-e', () => new THREE.BoxGeometry(0.012, 0.013, 0.28)), m: mat(0xb0b6bc, { metalness: 0.7 }), y: 0.0065, x: -0.1 },
        ],
      }
    case 'papel':
      return {
        w: 0.26, h: 0.012, d: 0.34, pila: 16, piezas: [
          { geo: g('pap', () => new THREE.BoxGeometry(0.25, 0.01, 0.33)), m: mat(color, { roughness: 0.9 }), y: 0.005, colorPorPieza: true },
        ],
      }
    case 'corrugado':
      return {
        w: 0.5, h: 0.35, d: 0.4, pila: 6, piezas: [
          { geo: g('cor', () => new THREE.BoxGeometry(0.49, 0.34, 0.39)), m: mat(0xc49a63, { roughness: 0.95 }), y: 0.17 },
          { geo: g('cor-c', () => new THREE.BoxGeometry(0.06, 0.342, 0.392)), m: mat(0xd8b88a, { roughness: 0.6 }), y: 0.17 },
          { geo: g('cor-e', () => new THREE.PlaneGeometry(0.2, 0.12)), m: mat(color, { roughness: 0.7 }), y: 0.19, z: 0.196, x: 0.1 },
        ],
      }
  }
}

function escalar(geo: THREE.BufferGeometry, k: number) {
  const c = geo.clone()
  c.scale(k, k, k)
  return c
}

/** Abomba una caja (bolsas, ropa doblada): empuja los vértices hacia afuera del centro. */
function inflar(geo: THREE.BoxGeometry, cuanto: number) {
  const p = geo.attributes.position
  geo.computeBoundingBox()
  const b = geo.boundingBox!
  const s = new THREE.Vector3()
  b.getSize(s)
  for (let i = 0; i < p.count; i++) {
    const x = p.getX(i) / (s.x / 2), y = p.getY(i) / (s.y / 2), z = p.getZ(i) / (s.z / 2)
    const k = (1 - Math.abs(y) ** 4) * cuanto
    p.setZ(i, p.getZ(i) + Math.sign(z) * k * (1 - Math.abs(x) ** 4))
    p.setX(i, p.getX(i) - Math.sign(x) * k * 0.25 * Math.abs(y) ** 2)
  }
  geo.computeVertexNormals()
  return geo
}

/** Cuántas piezas de cada producto se pueden mostrar en una caja de corrugado. */
export function porCaja(p: Lugar) {
  const n = normalizar(p.nombre)
  if (/gorra|gorro/.test(n)) return 50
  if (/playera|sudadera|chaleco|chamarra|pijama|sueter/.test(n)) return 20
  if (/bufanda|guante/.test(n)) return 36
  if (p.unidad === 'caja' || p.unidad === 'kit') return 1
  return 12
}

// ---------------------------------------------------------------- colocar productos en su hueco

export type ProductoDibujado = { grupo: THREE.Group; materiales: THREE.MeshStandardMaterial[]; capacidad: number; visibles: number; porCaja: number }

/**
 * Llena el hueco con el producto: cuadrícula de frentes, filas hacia atrás y
 * pilas hacia arriba, empezando por el frente. Sin conteo usa la foto del día.
 */
export function dibujarProducto(p: Lugar, h: Hueco): ProductoDibujado {
  const grupo = new THREE.Group()
  grupo.position.set(h.cx, h.y, h.cz)
  grupo.rotation.y = h.rot
  grupo.userData.productoId = p.id
  const tipo = h.mueble.tipo
  const forma = formaDe(p, tipo)
  const color = colorDe(p.nombre)
  const a = articulo(forma, color, p.nombre)
  const r = rnd(p.id * 7919)
  const materiales: THREE.MeshStandardMaterial[] = []
  const ms: THREE.Matrix4[] = []
  const colores: THREE.Color[] = []
  let capacidad = 0

  // Medidas del hueco en ejes del artículo (x a lo ancho, z hacia el frente).
  const W = h.ancho - 0.02
  const D = h.fondo
  const H = Math.max(0.1, h.alto - 0.06)

  // Variaciones de color para ropa, papel, gorras, cuadernos.
  const variado = a.piezas.some((x) => x.colorPorPieza) && /surtid|colores|china|picado|gorra|playera|cuaderno|sudadera|bufanda|gorro|guante|paleta/.test(normalizar(p.nombre))
  const tonos = [color, 0x2d3436, 0xb03a48, 0x1f3a93, 0x6d7480, 0x2a9d8f, 0xf2b233, 0xe84393, 0xffffff]

  if (forma === 'ropaColgada') {
    // Prendas colgadas a lo largo del tubo, de lado.
    const paso = 0.075
    capacidad = Math.max(1, Math.floor(W / paso))
    const n = cuantos(p, capacidad)
    const yTubo = H + 0.0
    for (let i = 0; i < n; i++) {
      const x = -W / 2 + paso / 2 + i * paso
      ms.push(M(x, yTubo, 0, (r() - 0.5) * 0.15))
      colores.push(new THREE.Color(variado ? tonos[i % tonos.length] : color))
    }
  } else if (forma === 'corrugado') {
    // Cajas de corrugado sobre tarima, con playo; cuántas según la existencia.
    const cols = Math.max(1, Math.floor(W / 0.52)), filas = Math.max(1, Math.floor(D / 0.42)), capas = Math.max(1, Math.min(a.pila, Math.floor((H - 0.16) / 0.355)))
    const tarimas = Math.max(1, Math.floor(W / 1.25))
    for (let t = 0; t < tarimas; t++) {
      const tx = -W / 2 + (t + 0.5) * (W / tarimas)
      grupo.add(sombras(tarimaMadera(Math.min(1.2, W / tarimas - 0.05), Math.min(1.0, D)), true).translateX(tx))
    }
    capacidad = cols * filas * capas
    const cajas = p.stock == null ? Math.round(capacidad * nivelFoto(p)) : Math.min(capacidad, Math.ceil(Math.max(0, p.stock) / porCaja(p)))
    let k = 0
    for (let c = 0; c < capas && k < cajas; c++) for (let j = 0; j < filas && k < cajas; j++) for (let i = 0; i < cols && k < cajas; i++, k++) {
      ms.push(M(-W / 2 + (i + 0.5) * (W / cols), 0.145 + c * 0.355, D / 2 - (j + 0.5) * (D / filas), (r() - 0.5) * 0.04))
    }
    if (k > 0) {
      // Playo (película) alrededor de lo estibado.
      const altoPila = Math.min(capas, Math.ceil(k / (cols * filas))) * 0.355
      const film = new THREE.Mesh(new THREE.BoxGeometry(W - 0.02, altoPila, D - 0.02), new THREE.MeshStandardMaterial({ color: 0xdfefff, transparent: true, opacity: 0.18, roughness: 0.1, depthWrite: false }))
      film.position.y = 0.145 + altoPila / 2
      grupo.add(film)
      materiales.push(film.material as THREE.MeshStandardMaterial)
    }
    capacidad = Math.max(capacidad, 1)
  } else {
    // Entrepaño de tienda: frentes × filas × pila.
    const gap = 0.008
    const cols = Math.max(1, Math.floor(W / (a.w + gap)))
    const filas = Math.max(1, Math.floor(D / (a.d + gap)))
    const pila = Math.max(1, Math.min(a.pila, Math.floor(H / (a.h + 0.002))))
    capacidad = cols * filas * pila
    const n = cuantos(p, capacidad)
    const x0 = -(cols * (a.w + gap)) / 2 + (a.w + gap) / 2
    const z0 = D / 2 - a.d / 2 - 0.01
    let k = 0
    // Primero llena el frente (todas las columnas y su pila), luego hacia atrás.
    for (let j = 0; j < filas && k < n; j++) for (let c = 0; c < pila && k < n; c++) for (let i = 0; i < cols && k < n; i++, k++) {
      const giro = forma === 'gorra' || forma === 'ropaDoblada' || forma === 'cuaderno' || forma === 'papel' ? (r() - 0.5) * 0.08 : (r() - 0.5) * 0.12
      const yy = forma === 'gorra' ? c * 0.028 : c * (a.h + 0.002)
      ms.push(M(x0 + i * (a.w + gap) + (r() - 0.5) * 0.004, yy, z0 - j * (a.d + gap), giro))
      colores.push(new THREE.Color(variado ? tonos[(i * 3 + c + j) % tonos.length] : color))
    }
  }

  for (const pz of a.piezas) {
    const off = new THREE.Matrix4().compose(
      new THREE.Vector3(pz.x ?? 0, pz.y ?? 0, pz.z ?? 0),
      new THREE.Quaternion().setFromEuler(new THREE.Euler(pz.rx ?? 0, pz.ry ?? 0, pz.rz ?? 0)),
      new THREE.Vector3(1, 1, 1))
    const mesh = new THREE.InstancedMesh(pz.geo, pz.m, Math.max(1, ms.length))
    ms.forEach((m, i) => mesh.setMatrixAt(i, m.clone().multiply(off)))
    if (pz.colorPorPieza && variado) {
      ;(pz.m as THREE.MeshStandardMaterial).color.setHex(0xffffff)
      colores.forEach((c, i) => mesh.setColorAt(i, c))
    }
    mesh.count = ms.length
    mesh.castShadow = true
    mesh.receiveShadow = true
    mesh.userData.productoId = p.id
    grupo.add(mesh)
    if (pz.m instanceof THREE.MeshStandardMaterial) materiales.push(pz.m)
  }
  return { grupo, materiales, capacidad, visibles: ms.length, porCaja: forma === 'corrugado' ? porCaja(p) : 1 }
}

function nivelFoto(p: Lugar) {
  return p.foto === 'lleno' ? 1 : p.foto === 'poco' ? 0.25 : p.foto === 'vacio' ? 0 : 0.6
}
function cuantos(p: Lugar, capacidad: number) {
  if (p.stock == null) return Math.round(capacidad * nivelFoto(p))
  return Math.max(0, Math.min(capacidad, p.stock))
}
const M = (x: number, y: number, z: number, ry = 0) =>
  new THREE.Matrix4().compose(new THREE.Vector3(x, y, z), new THREE.Quaternion().setFromEuler(new THREE.Euler(0, ry, 0)), new THREE.Vector3(1, 1, 1))

export function tarimaMadera(w: number, d: number) {
  const gr = new THREE.Group()
  const madera = mat(0xa9824f, { roughness: 0.9 })
  for (let i = 0; i < 5; i++) gr.add(caja(w, 0.022, d / 5 - 0.02, madera, 0, 0.13, -d / 2 + (i + 0.5) * (d / 5)))
  for (const x of [-w / 2 + 0.05, 0, w / 2 - 0.05]) gr.add(caja(0.09, 0.1, d, madera, x, 0.065, 0))
  for (const z of [-d / 2 + 0.05, d / 2 - 0.05]) gr.add(caja(w, 0.02, 0.09, madera, 0, 0.01, z))
  return gr
}

// ---------------------------------------------------------------- muebles

const ACERO_BLANCO = () => mat(0xeef0f2, { metalness: 0.35, roughness: 0.45 })

/**
 * Estructura del mueble en ejes locales (x a lo ancho, frente en +z, origen al
 * centro del piso). `copete` es el texto del letrero de arriba.
 */
export function construirMueble(m: Mueble, copete: string, marca: number): THREE.Group {
  const gr = new THREE.Group()
  const { ancho: w, fondo: d, alto: H } = m
  const e = entrepanos(m)
  switch (m.tipo) {
    case 'gondola': case 'anaquel': {
      const lados = m.tipo === 'gondola'
      const metal = ACERO_BLANCO()
      const zocalo = mat(0x3a3f47, { roughness: 0.6 })
      const perf = texturaPerforada()
      perf.repeat.set(w * 6, H * 6)
      const respaldo = mat(0xffffff, { map: perf, roughness: 0.8 })
      // Respaldo perforado, al centro (góndola) o al fondo (pared).
      gr.add(caja(w, H, 0.035, respaldo, 0, H / 2, lados ? 0 : -d / 2 + 0.018))
      for (const x of [-w / 2, w / 2]) gr.add(caja(0.035, H, d, metal, x, H / 2, 0))
      gr.add(caja(w, 0.1, d - 0.02, zocalo, 0, 0.05, 0))
      for (let n = 1; n <= m.niveles; n++) {
        const y = e.base + (n - 1) * e.h
        for (const s of lados ? [1, -1] : [1]) {
          const fondo = lados ? d / 2 - 0.02 : d - 0.04
          const cz = lados ? s * (0.02 + fondo / 2) : 0.02
          gr.add(caja(w - 0.04, 0.022, fondo, metal, 0, y - 0.011, cz))
          // Riel de precios al frente del entrepaño.
          gr.add(caja(w - 0.04, 0.045, 0.012, mat(n === 1 ? 0x3a3f47 : 0xf7f7f7, { roughness: 0.5 }), 0, y - 0.02, cz + s * (fondo / 2 + 0.006)))
        }
      }
      // Copete con el nombre o las categorías.
      if (copete) {
        const tex = texturaLetrero(copete, '#' + marca.toString(16).padStart(6, '0'), '#ffffff', 1024, 140)
        const letrero = new THREE.Mesh(new THREE.BoxGeometry(w, 0.24, 0.03), [metal, metal, metal, metal, mat(0xffffff, { map: tex, roughness: 0.6 }), lados ? mat(0xffffff, { map: tex, roughness: 0.6 }) : metal])
        letrero.position.set(0, H + 0.14, lados ? 0 : -d / 2 + 0.06)
        gr.add(letrero)
      }
      // Cabeceras de la góndola (los extremos) con su color.
      if (lados) for (const x of [-w / 2 - 0.02, w / 2 + 0.02]) gr.add(caja(0.02, H - 0.1, d, mat(marca, { roughness: 0.6 }), x, H / 2 + 0.05, 0))
      break
    }
    case 'refri': {
      const cuerpo = mat(0xc8221c, { roughness: 0.45, metalness: 0.2 })
      const blanco = mat(0xf4f4f4, { roughness: 0.5 })
      gr.add(caja(w, 0.16, d, cuerpo, 0, 0.08, 0))
      gr.add(caja(w, H - 0.16 - 0.3, 0.04, blanco, 0, 0.16 + (H - 0.46) / 2, -d / 2 + 0.02))
      for (const x of [-w / 2 + 0.03, w / 2 - 0.03]) gr.add(caja(0.06, H, d, cuerpo, x, H / 2, 0))
      gr.add(caja(w, 0.3, d, cuerpo, 0, H - 0.15, 0))
      // Letrero luminoso.
      const tex = texturaLetrero('BIEN FRÍAS', '#c8221c', '#ffffff', 1024, 200)
      const luz = new THREE.Mesh(new THREE.PlaneGeometry(w - 0.1, 0.24), new THREE.MeshStandardMaterial({ map: tex, emissive: 0xffffff, emissiveMap: tex, emissiveIntensity: 0.55 }))
      luz.position.set(0, H - 0.15, d / 2 + 0.002)
      gr.add(luz)
      // Entrepaños de alambre y luz interior.
      for (let n = 1; n <= m.niveles; n++) gr.add(caja(w - 0.12, 0.012, d - 0.12, mat(0xc9ced4, { metalness: 0.6, roughness: 0.35 }), 0, e.base + (n - 1) * e.h - 0.006, 0))
      gr.add(caja(w - 0.14, 0.02, 0.03, new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xeaf6ff, emissiveIntensity: 1.2 }), 0, H - 0.32, d / 2 - 0.08))
      // Puertas de vidrio con marco y jaladera.
      const puertas = Math.max(1, Math.round((w - 0.06) / 0.62))
      const pw = (w - 0.06) / puertas
      const vidrio = new THREE.MeshStandardMaterial({ color: 0xcfe9ff, transparent: true, opacity: 0.16, roughness: 0.05, metalness: 0.1, depthWrite: false })
      const aluminio = mat(0xb6bcc2, { metalness: 0.8, roughness: 0.3 })
      for (let i = 0; i < puertas; i++) {
        const x = -w / 2 + 0.03 + (i + 0.5) * pw
        gr.add(caja(pw - 0.02, H - 0.48, 0.012, vidrio, x, 0.16 + (H - 0.48) / 2, d / 2 - 0.006))
        for (const s of [-1, 1]) gr.add(caja(0.03, H - 0.46, 0.03, aluminio, x + s * (pw / 2 - 0.015), 0.16 + (H - 0.46) / 2, d / 2 - 0.01))
        gr.add(caja(0.02, 0.4, 0.04, aluminio, x + pw / 2 - 0.07, 1.05, d / 2 + 0.02))
      }
      break
    }
    case 'congelador': {
      const blanco = mat(0xf7f7f7, { roughness: 0.4 })
      gr.add(caja(w, 0.3, d, blanco, 0, 0.15, 0))
      for (const z of [-d / 2 + 0.03, d / 2 - 0.03]) gr.add(caja(w, H - 0.3, 0.06, blanco, 0, 0.3 + (H - 0.3) / 2, z))
      for (const x of [-w / 2 + 0.03, w / 2 - 0.03]) gr.add(caja(0.06, H - 0.3, d, blanco, x, 0.3 + (H - 0.3) / 2, 0))
      gr.add(caja(w - 0.1, 0.02, d - 0.1, new THREE.MeshStandardMaterial({ color: 0xcfe9ff, transparent: true, opacity: 0.2, depthWrite: false }), 0, H, 0))
      const tex = texturaLetrero('HELADOS', '#2f7fd0', '#ffffff', 512, 120)
      const et = new THREE.Mesh(new THREE.PlaneGeometry(w * 0.6, 0.14), mat(0xffffff, { map: tex }))
      et.position.set(0, 0.45, d / 2 + 0.002)
      gr.add(et)
      break
    }
    case 'rack': {
      const poste = mat(0x2f5d8a, { metalness: 0.5, roughness: 0.45 })
      const larguero = mat(0xe8730c, { metalness: 0.4, roughness: 0.5 })
      for (const x of [-w / 2, w / 2]) for (const z of [-d / 2, d / 2]) gr.add(caja(0.08, H, 0.08, poste, x, H / 2, z))
      for (const x of [-w / 2, w / 2]) for (let k = 0; k < 4; k++) {
        const dg = caja(0.03, Math.hypot(d, H / 4), 0.03, poste, x, (k + 0.5) * (H / 4), 0)
        dg.rotation.x = (k % 2 ? 1 : -1) * Math.atan2(d, H / 4)
        gr.add(dg)
      }
      for (let n = 1; n <= m.niveles; n++) {
        const y = e.base + (n - 1) * e.h
        for (const z of [-d / 2, d / 2]) gr.add(caja(w, 0.1, 0.05, larguero, 0, y - 0.05, z))
        gr.add(caja(w - 0.1, 0.015, d, mat(0x9aa3ad, { metalness: 0.4, roughness: 0.5, wireframe: false }), 0, y - 0.008, 0))
      }
      gr.add(caja(w, 0.1, 0.05, larguero, 0, H - 0.05, d / 2))
      // Letrero del rack.
      const tex = texturaLetrero(m.nombre, '#f2b233', '#1d2a24', 512, 120)
      const et = new THREE.Mesh(new THREE.PlaneGeometry(0.7, 0.16), mat(0xffffff, { map: tex }))
      et.position.set(0, H - 0.05, d / 2 + 0.03)
      gr.add(et)
      break
    }
    case 'tarima': {
      // Marca amarilla en el piso; la tarima y las cajas las pone el producto.
      const amarillo = mat(0xf5b400, { roughness: 0.6 })
      for (const s of [-1, 1]) {
        gr.add(caja(w + 0.1, 0.01, 0.05, amarillo, 0, 0.005, s * (d / 2 + 0.05)))
        gr.add(caja(0.05, 0.01, d + 0.1, amarillo, s * (w / 2 + 0.05), 0.005, 0))
      }
      break
    }
    case 'perchero': {
      const cromo = mat(0xd5d9de, { metalness: 0.9, roughness: 0.2 })
      for (const x of [-w / 2, w / 2]) {
        const tubo = new THREE.Mesh(new THREE.CylinderGeometry(0.018, 0.018, H, 10), cromo)
        tubo.position.set(x, H / 2, 0)
        gr.add(tubo)
        gr.add(caja(0.05, 0.03, d, cromo, x, 0.015, 0))
        for (const z of [-d / 2, d / 2]) { const r = new THREE.Mesh(new THREE.CylinderGeometry(0.025, 0.025, 0.04, 10), mat(0x222222)); r.position.set(x, 0.02, z); gr.add(r) }
      }
      for (let n = 1; n <= m.niveles; n++) {
        const riel = new THREE.Mesh(new THREE.CylinderGeometry(0.014, 0.014, w, 10), cromo)
        riel.rotation.z = Math.PI / 2
        riel.position.y = e.base + n * e.h
        gr.add(riel)
      }
      break
    }
    case 'mesa': {
      const madera = mat(0x9b6b3d, { roughness: 0.7 })
      gr.add(caja(w, 0.05, d, madera, 0, H - 0.025, 0))
      gr.add(caja(w - 0.04, H - 0.1, d - 0.04, mat(0xefe6d8, { roughness: 0.9 }), 0, (H - 0.05) / 2, 0))
      gr.add(caja(w + 0.02, 0.08, d + 0.02, mat(marca, { roughness: 0.6 }), 0, H - 0.11, 0))
      break
    }
    case 'mostrador': {
      const madera = mat(0xa0703f, { roughness: 0.65 })
      const cubierta = mat(0x2d3436, { roughness: 0.35, metalness: 0.2 })
      gr.add(caja(w, H - 0.04, d, madera, 0, (H - 0.04) / 2, 0))
      gr.add(caja(w + 0.06, 0.04, d + 0.06, cubierta, 0, H - 0.02, 0))
      gr.add(caja(w - 0.04, 0.12, 0.01, mat(marca, { roughness: 0.5 }), 0, H - 0.18, d / 2 + 0.005))
      // Caja registradora del lado derecho (visto por el cliente).
      const reg = new THREE.Group()
      reg.add(caja(0.36, 0.1, 0.32, mat(0x30343a, { roughness: 0.4 }), 0, 0.05, 0))
      const pantalla = caja(0.26, 0.17, 0.02, mat(0x111418, { emissive: 0x2fb487, emissiveIntensity: 0.25 }), 0, 0.2, -0.08)
      pantalla.rotation.x = -0.35
      reg.add(pantalla)
      reg.add(caja(0.2, 0.012, 0.12, mat(0xdedede), 0, 0.105, 0.08))
      reg.position.set(w / 2 - 0.3, H, -0.05)
      reg.rotation.y = Math.PI
      gr.add(reg)
      // Báscula.
      const bascula = new THREE.Group()
      bascula.add(caja(0.3, 0.06, 0.3, mat(0xe9ecef, { roughness: 0.4 }), 0, 0.03, 0))
      bascula.add(caja(0.32, 0.01, 0.32, mat(0xc4c9ce, { metalness: 0.8, roughness: 0.25 }), 0, 0.065, 0))
      const disp = caja(0.16, 0.08, 0.02, mat(0x101418, { emissive: 0xff4d2e, emissiveIntensity: 0.4 }), 0, 0.1, -0.14)
      bascula.add(disp)
      bascula.position.set(-w / 2 + 0.3, H, 0)
      gr.add(bascula)
      // La persona que atiende, detrás del mostrador.
      const t = persona(marca, true)
      t.position.set(0.1, 0, -d / 2 - 0.45)
      gr.add(t)
      break
    }
    case 'botanero': {
      const alambre = mat(0x30343a, { metalness: 0.6, roughness: 0.4 })
      for (const x of [-w / 2, w / 2]) for (const z of [-d / 2 + 0.02, d / 2 - 0.02]) { const p = caja(0.02, H, 0.02, alambre, x, H / 2, z); gr.add(p) }
      for (let n = 1; n <= m.niveles; n++) {
        const y = e.base + (n - 1) * e.h
        const cesta = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(w - 0.02, 0.12, d - 0.04, 4, 1, 3)), new THREE.LineBasicMaterial({ color: 0x30343a }))
        cesta.position.y = y + 0.05
        gr.add(cesta)
        gr.add(caja(w - 0.02, 0.008, d - 0.04, alambre, 0, y - 0.004, 0))
      }
      const tex = texturaLetrero('BOTANAS', '#f2b233', '#7a1414', 512, 160)
      const letrero = new THREE.Mesh(new THREE.PlaneGeometry(w, 0.22), mat(0xffffff, { map: tex, side: THREE.DoubleSide }))
      letrero.position.set(0, H + 0.08, d / 2 - 0.02)
      gr.add(letrero)
      break
    }
    case 'vitrina': {
      // Mostrador de cristal: base de madera, caja de vidrio con entrepaños y luz.
      const madera = mat(0x6d4c33, { roughness: 0.6 })
      const vidrio = new THREE.MeshStandardMaterial({ color: 0xcfeaff, transparent: true, opacity: 0.22, roughness: 0.05, metalness: 0.1, depthWrite: false })
      const aluminio = mat(0xc9ced3, { metalness: 0.8, roughness: 0.3 })
      gr.add(caja(w, 0.15, d, madera, 0, 0.075, 0))
      gr.add(caja(w - 0.02, H - 0.17, d - 0.02, vidrio, 0, 0.15 + (H - 0.17) / 2, 0))
      for (const x of [-w / 2, w / 2]) for (const z of [-d / 2, d / 2]) gr.add(caja(0.025, H - 0.15, 0.025, aluminio, x, 0.15 + (H - 0.15) / 2, z))
      gr.add(caja(w, 0.02, d, aluminio, 0, H - 0.01, 0))
      for (let n = 1; n <= m.niveles; n++) gr.add(caja(w - 0.04, 0.012, d - 0.04, vidrio, 0, e.base + (n - 1) * e.h, 0))
      const tira = caja(w - 0.06, 0.01, 0.03, new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xfff3d6, emissiveIntensity: 0.9 }), 0, H - 0.03, 0)
      gr.add(tira)
      gr.add(caja(w - 0.02, 0.05, 0.01, mat(marca, { roughness: 0.5 }), 0, 0.11, d / 2 + 0.005))
      break
    }
    case 'ganchera': {
      // Panel perforado de pared con ganchos (ferretería, accesorios, dulces colgados).
      const perf = texturaPerforada()
      perf.repeat.set(w * 8, H * 8)
      gr.add(caja(w, H - 0.1, 0.03, mat(0xd8c3a0, { map: perf, roughness: 0.85 }), 0, 0.1 + (H - 0.1) / 2, -d / 2 + 0.015))
      gr.add(caja(w, 0.1, d, mat(0x3a3f47, { roughness: 0.6 }), 0, 0.05, 0))
      for (const x of [-w / 2, w / 2]) gr.add(caja(0.03, H, 0.05, mat(marca, { roughness: 0.6 }), x, H / 2, -d / 2 + 0.03))
      const gancho = mat(0xb0b5ba, { metalness: 0.85, roughness: 0.3 })
      for (let n = 1; n <= m.niveles; n++) {
        const y = e.base + (n - 1) * e.h + e.h * 0.85
        for (let x = -w / 2 + 0.12; x < w / 2 - 0.05; x += 0.2) {
          const g = new THREE.Mesh(new THREE.CylinderGeometry(0.004, 0.004, d - 0.05, 4), gancho)
          g.rotation.x = Math.PI / 2
          g.position.set(x, y, 0.01)
          gr.add(g)
        }
      }
      break
    }
    case 'cajas': {
      // Estante metálico con cajas de plástico de colores al frente de cada nivel.
      const metal = mat(0x5b6470, { metalness: 0.5, roughness: 0.5 })
      for (const x of [-w / 2 + 0.02, w / 2 - 0.02]) for (const z of [-d / 2 + 0.02, d / 2 - 0.02]) gr.add(caja(0.035, H, 0.035, metal, x, H / 2, z))
      const colores = [0x2f80ed, 0xeb5757, 0xf2c94c, 0x27ae60, 0x9b51e0]
      for (let n = 1; n <= m.niveles; n++) {
        const y = e.base + (n - 1) * e.h
        gr.add(caja(w - 0.02, 0.02, d - 0.02, metal, 0, y - 0.01, 0))
        const k = Math.max(2, Math.round((w - 0.1) / 0.32))
        const cw = (w - 0.1) / k
        for (let i = 0; i < k; i++) {
          const c = caja(cw - 0.02, Math.min(0.14, e.h * 0.45), 0.06, mat(colores[(i + n) % colores.length], { roughness: 0.6 }), -w / 2 + 0.05 + (i + 0.5) * cw, y + 0.07, d / 2 - 0.05)
          gr.add(c)
        }
      }
      break
    }
  }
  return sombras(gr)
}

/** Una persona sencilla: la que atiende (con mandil) o la que camina la ruta (con canasta). */
export function persona(color: number, mandil: boolean) {
  const gr = new THREE.Group()
  const pantalon = mat(0x2b3a55)
  const piernas: THREE.Mesh[] = []
  for (const x of [-0.09, 0.09]) {
    const p = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.78, 0.15), pantalon)
    p.geometry.translate(0, -0.39, 0)
    p.position.set(x, 0.82, 0)
    piernas.push(p)
    gr.add(p)
  }
  const torso = new THREE.Mesh(new THREE.CapsuleGeometry(0.19, 0.4, 4, 12), mat(mandil ? 0xf4f4f4 : color))
  torso.position.y = 1.15
  gr.add(torso)
  if (mandil) {
    const m = caja(0.3, 0.55, 0.02, mat(color, { roughness: 0.8 }), 0, 1.05, 0.19)
    gr.add(m)
  }
  const brazos: THREE.Mesh[] = []
  for (const x of [-0.24, 0.24]) {
    const b = new THREE.Mesh(new THREE.CapsuleGeometry(0.05, 0.42, 4, 8), mat(mandil ? 0xf4f4f4 : color))
    b.geometry.translate(0, -0.25, 0)
    b.position.set(x, 1.42, 0)
    brazos.push(b)
    gr.add(b)
  }
  const cabeza = new THREE.Mesh(new THREE.SphereGeometry(0.12, 16, 12), mat(0xc99a72))
  cabeza.position.y = 1.58
  const pelo = new THREE.Mesh(new THREE.SphereGeometry(0.125, 16, 8, 0, Math.PI * 2, 0, Math.PI / 2), mat(0x2b1d14))
  pelo.position.y = 1.6
  gr.add(cabeza, pelo)
  if (!mandil) {
    // Canasta de súper en la mano derecha.
    const cesta = new THREE.Group()
    cesta.add(caja(0.34, 0.16, 0.22, mat(0xd64532, { roughness: 0.6 }), 0, 0, 0))
    const asa = new THREE.Mesh(new THREE.TorusGeometry(0.12, 0.01, 6, 16, Math.PI), mat(0x30343a))
    asa.position.y = 0.08
    cesta.add(asa)
    cesta.position.set(0, -0.55, 0.08)
    cesta.rotation.y = Math.PI / 2
    brazos[1].add(cesta)
  }
  gr.userData.piernas = piernas
  gr.userData.brazos = brazos
  return sombras(gr)
}

/** Letrero de precio para colgar al frente del hueco. */
export function etiquetaPrecio(p: Lugar, h: Hueco): THREE.Mesh {
  const tex = texturaPrecio(p)
  const tipo = h.mueble.tipo
  const w = Math.min(0.16, Math.max(0.09, h.ancho - 0.02))
  const et = new THREE.Mesh(new THREE.PlaneGeometry(w, w * 0.44), new THREE.MeshBasicMaterial({ map: tex, side: THREE.DoubleSide }))
  if (tipo === 'perchero') et.position.set(-h.ancho / 2 + w / 2 + 0.02, h.alto + 0.06, 0.03)
  else if (tipo === 'rack' || tipo === 'tarima') { et.scale.setScalar(2.2); et.position.set(0, 0.06, h.fondo / 2 + 0.02) }
  else if (tipo === 'mesa' || tipo === 'mostrador' || tipo === 'congelador') { et.position.set(0, 0.02, h.fondo / 2 + 0.03); et.rotation.x = -0.6 }
  else et.position.set(0, -0.02, h.fondo / 2 + 0.014)
  et.userData.productoId = p.id
  return et
}
