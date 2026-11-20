// Tipos de la bodega 3D y la geometría compartida entre la escena, la ruta a pie
// y las pantallas: dónde queda, en metros, el hueco de cada producto.
//
// Ejes del local: x de izquierda a derecha (0..ancho), z del fondo (0) a la
// calle (fondo), donde está la puerta. Un mueble con rot 0 tiene el frente
// hacia la calle (+z); rot 90 hacia la derecha (+x); 180 al fondo; 270 a la izquierda.

export type TipoMueble = 'gondola' | 'anaquel' | 'refri' | 'congelador' | 'rack' | 'tarima' | 'perchero' | 'mesa' | 'mostrador' | 'botanero'
  | 'vitrina' | 'ganchera' | 'cajas'

export type Mueble = {
  id: string; tipo: TipoMueble; nombre: string; x: number; z: number; rot: number
  ancho: number; fondo: number; alto: number; niveles: number; anaquel_id?: number | null
}
export type Area = { nombre: string; x: number; z: number; ancho: number; fondo: number; color: string }
/** Pared interior del armador: de (x1,z1) a (x2,z2); `puerta` = ancho del hueco al centro (0 = corrida). */
export type Pared = { x1: number; z1: number; x2: number; z2: number; alto: number; puerta: number; tipo?: 'muro' | 'media' | 'cristal' }
export type Columna = { x: number; z: number; lado: number }
export type Plano = {
  ancho: number; fondo: number; alto: number; puerta: { x: number; ancho: number }
  piso: 'mosaico' | 'cemento' | 'madera'; muebles: Mueble[]; areas: Area[]
  paredes?: Pared[]; columnas?: Columna[]
}

/** Tramos sólidos de una pared (sin el hueco de la puerta), en metros del local. */
export function tramosPared(w: Pared): [number, number, number, number][] {
  const L = Math.hypot(w.x2 - w.x1, w.z2 - w.z1)
  if (!w.puerta || w.puerta <= 0 || L <= w.puerta + 0.1) return [[w.x1, w.z1, w.x2, w.z2]]
  const ux = (w.x2 - w.x1) / L, uz = (w.z2 - w.z1) / L
  const a = (L - w.puerta) / 2, b = a + w.puerta
  return [[w.x1, w.z1, w.x1 + ux * a, w.z1 + uz * a], [w.x1 + ux * b, w.z1 + uz * b, w.x2, w.z2]]
}
export type Lugar = {
  id: number; codigo: string; nombre: string; categoria: string; unidad: string
  precio_c: number; precio_mayoreo_c: number; minimo_mayoreo: number
  stock: number | null; stock_min: number; publicado: boolean; vendidos30: number
  mueble: string; lado: number; nivel: number; posicion: number
  origen: '' | 'foto'; foto: '' | 'lleno' | 'poco' | 'vacio'
}
export type DatosBodega = {
  plano: Plano; productos: Lugar[]
  anaqueles: { id: number; nombre: string; faltantes: number; ultima_en: number | null }[]
  tienda: { nombre: string; giro: string; slug: string; telefono?: string; direccion?: string }
}

/** Datos de cada tipo de mueble (los mismos que en el backend). */
export const TIPOS: Record<TipoMueble, { nombre: string; ancho: number; fondo: number; alto: number; niveles: number; lados: number; min: number; max: number }> = {
  gondola: { nombre: 'Góndola', ancho: 2.4, fondo: 0.9, alto: 1.6, niveles: 4, lados: 2, min: 2, max: 6 },
  anaquel: { nombre: 'Anaquel de pared', ancho: 2.4, fondo: 0.45, alto: 1.9, niveles: 4, lados: 1, min: 2, max: 7 },
  refri: { nombre: 'Refrigerador', ancho: 1.8, fondo: 0.75, alto: 2.0, niveles: 5, lados: 1, min: 3, max: 6 },
  congelador: { nombre: 'Congelador', ancho: 1.4, fondo: 0.75, alto: 0.9, niveles: 1, lados: 1, min: 1, max: 1 },
  rack: { nombre: 'Rack de carga', ancho: 2.7, fondo: 1.0, alto: 3.6, niveles: 3, lados: 1, min: 2, max: 5 },
  tarima: { nombre: 'Tarima en piso', ancho: 1.2, fondo: 1.0, alto: 1.6, niveles: 1, lados: 1, min: 1, max: 1 },
  perchero: { nombre: 'Perchero', ancho: 1.8, fondo: 0.6, alto: 1.6, niveles: 1, lados: 1, min: 1, max: 2 },
  mesa: { nombre: 'Mesa de exhibición', ancho: 1.6, fondo: 0.9, alto: 0.8, niveles: 1, lados: 1, min: 1, max: 1 },
  mostrador: { nombre: 'Mostrador y caja', ancho: 2.2, fondo: 0.7, alto: 1.0, niveles: 1, lados: 1, min: 1, max: 2 },
  botanero: { nombre: 'Exhibidor de botana', ancho: 0.7, fondo: 0.5, alto: 1.7, niveles: 4, lados: 1, min: 2, max: 6 },
  vitrina: { nombre: 'Vitrina de cristal', ancho: 1.5, fondo: 0.6, alto: 1.1, niveles: 2, lados: 1, min: 1, max: 3 },
  ganchera: { nombre: 'Panel de ganchos', ancho: 1.2, fondo: 0.35, alto: 1.8, niveles: 5, lados: 1, min: 3, max: 8 },
  cajas: { nombre: 'Estante de cajas', ancho: 1.8, fondo: 0.5, alto: 1.8, niveles: 5, lados: 1, min: 3, max: 7 },
}

export const LADOS = ['A (frente)', 'B (atrás)']

/** Huella del mueble en el piso ya girado: medio ancho en x y medio fondo en z. */
export function huella(m: Pick<Mueble, 'ancho' | 'fondo' | 'rot'>) {
  const g = m.rot === 90 || m.rot === 270
  return { w: (g ? m.fondo : m.ancho) / 2, d: (g ? m.ancho : m.fondo) / 2 }
}

/** De coordenadas del mueble (lx a lo ancho, lz hacia su frente) al local. */
export function aMundo(m: Pick<Mueble, 'x' | 'z' | 'rot'>, lx: number, lz: number): [number, number] {
  const t = (m.rot * Math.PI) / 180
  return [m.x + lx * Math.cos(t) + lz * Math.sin(t), m.z - lx * Math.sin(t) + lz * Math.cos(t)]
}

/** Alturas de los entrepaños: base (y del piso del entrepaño n=1) y alto de cada uno. */
export function entrepanos(m: Mueble) {
  switch (m.tipo) {
    case 'refri': return { base: 0.18, h: (m.alto - 0.45) / m.niveles }
    case 'congelador': return { base: 0.3, h: m.alto - 0.35 }
    case 'rack': return { base: 0.12, h: (m.alto - 0.12) / m.niveles }
    case 'tarima': return { base: 0.14, h: m.alto - 0.14 }
    case 'perchero': return { base: 0.25, h: (m.alto - 0.25) / m.niveles }
    case 'mesa': return { base: m.alto, h: 0.45 }
    case 'mostrador': return { base: m.alto, h: 0.35 }
    case 'botanero': return { base: 0.25, h: (m.alto - 0.35) / m.niveles }
    case 'vitrina': return { base: 0.15, h: (m.alto - 0.2) / m.niveles }
    case 'ganchera': return { base: 0.3, h: (m.alto - 0.35) / m.niveles }
    default: return { base: 0.12, h: (m.alto - 0.12) / m.niveles }
  }
}

/** Hueco de un producto en el local. (cx, y, cz) es el centro del piso del hueco. */
export type Hueco = {
  productoId: number; mueble: Mueble; lado: number; nivel: number; orden: number; deCuantos: number
  cx: number; y: number; cz: number; ancho: number; fondo: number; alto: number
  /** Giro del frente del hueco (radianes, como rotation.y de three.js). */
  rot: number
  /** Punto del pasillo frente al hueco, para la ruta a pie. */
  acceso: [number, number]
}

/** Calcula el hueco de cada producto con lugar: los de un mismo entrepaño se reparten el ancho. */
export function calcularHuecos(plano: Plano, productos: Lugar[]): Map<number, Hueco> {
  const muebles = new Map(plano.muebles.map((m) => [m.id, m]))
  const grupos = new Map<string, Lugar[]>()
  for (const p of productos) {
    const m = muebles.get(p.mueble)
    if (!m || p.nivel < 1) continue
    const k = `${m.id}|${Math.min(p.lado, TIPOS[m.tipo].lados - 1)}|${Math.min(p.nivel, m.niveles)}`
    grupos.set(k, [...(grupos.get(k) ?? []), p])
  }
  const out = new Map<number, Hueco>()
  for (const [k, ps] of grupos) {
    const [mid, ls, ns] = k.split('|')
    const m = muebles.get(mid)!
    const lado = Number(ls), nivel = Number(ns)
    ps.sort((a, b) => a.posicion - b.posicion || a.id - b.id)
    const e = entrepanos(m)
    const dosLados = TIPOS[m.tipo].lados === 2
    const margen = m.tipo === 'perchero' ? 0.1 : m.tipo === 'tarima' ? 0.05 : 0.06
    const util = m.ancho - margen * 2
    const w = util / ps.length
    // Fondo del hueco y su centro (en z local): la góndola tiene medio fondo por lado.
    const fondo = dosLados ? m.fondo / 2 - 0.06 : m.tipo === 'anaquel' ? m.fondo - 0.06 : m.tipo === 'perchero' ? 0.5 : m.fondo - 0.1
    const lzC = dosLados ? (lado === 0 ? 1 : -1) * (0.03 + fondo / 2) : m.tipo === 'anaquel' ? 0.03 : 0
    const frenteRot = (m.rot * Math.PI) / 180 + (lado === 1 ? Math.PI : 0)
    ps.forEach((p, i) => {
      // Visto de frente, el orden va de izquierda a derecha.
      const lxBase = -util / 2 + (i + 0.5) * w
      const lx = lado === 1 ? -lxBase : lxBase
      const [cx, cz] = aMundo(m, lx, lzC)
      const salida = m.tipo === 'congelador' || m.tipo === 'mesa' || m.tipo === 'tarima' ? 0.55 : 0.6
      const borde = dosLados ? m.fondo / 2 : m.tipo === 'perchero' ? 0.35 : m.fondo / 2
      const acceso = aMundo(m, lx, (lado === 1 ? -1 : 1) * (borde + salida))
      out.set(p.id, {
        productoId: p.id, mueble: m, lado, nivel, orden: i + 1, deCuantos: ps.length,
        cx, cz, y: e.base + (nivel - 1) * e.h,
        ancho: w, fondo, alto: e.h, rot: frenteRot, acceso,
      })
    })
  }
  return out
}

/** Hueco de un entrepaño completo (para la mercancía que no cabe en su lugar: reserva en racks y tarimas). */
export function huecoEntero(m: Mueble, nivel: number, productoId: number): Hueco {
  const e = entrepanos(m)
  const [cx, cz] = aMundo(m, 0, 0)
  return {
    productoId, mueble: m, lado: 0, nivel, orden: 1, deCuantos: 1, cx, cz, y: e.base + (nivel - 1) * e.h,
    ancho: m.ancho - 0.1, fondo: m.fondo - 0.1, alto: e.h, rot: (m.rot * Math.PI) / 180, acceso: aMundo(m, 0, m.fondo / 2 + 0.6),
  }
}

/** Estado de existencia: agotado, bajo mínimo, bien o sin conteo. */
export function estadoDe(p: Lugar): 'agotado' | 'bajo' | 'bien' | 'sin' {
  if (p.stock == null) return p.foto === 'vacio' ? 'agotado' : p.foto === 'poco' ? 'bajo' : p.foto ? 'bien' : 'sin'
  if (p.stock <= 0) return 'agotado'
  if (p.stock_min > 0 && p.stock <= p.stock_min) return 'bajo'
  return 'bien'
}

export const pesos = (c: number) => '$' + (c / 100).toLocaleString('es-MX', { minimumFractionDigits: c % 100 ? 2 : 0, maximumFractionDigits: 2 })

/** Altura del entrepaño dicha como persona. */
export function alturaHablada(y: number) {
  if (y < 0.35) return 'abajo, casi en el piso'
  if (y < 0.8) return 'a la altura de la rodilla'
  if (y < 1.15) return 'a la altura de la cintura'
  if (y < 1.5) return 'a la altura del pecho'
  if (y < 1.9) return 'a la altura de los ojos'
  return 'arriba'
}

/** "Góndola 1, lado B, entrepaño 3 de 4 (contando desde abajo), el 2º de izquierda a derecha". */
export function describirLugar(h: Hueco) {
  const m = h.mueble
  const partes = [m.nombre]
  if (TIPOS[m.tipo].lados === 2) partes.push(`lado ${h.lado === 0 ? 'A' : 'B'}`)
  if (m.niveles > 1) partes.push(`${m.tipo === 'rack' ? 'nivel' : 'entrepaño'} ${h.nivel} de ${m.niveles} (desde abajo)`)
  if (h.deCuantos > 1) partes.push(`${h.orden}º de izquierda a derecha`)
  return partes.join(' · ')
}

/** Minúsculas, sin acentos ni signos, y en singular aproximado. */
export function normalizar(s: string) {
  return s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9ñ ]/g, ' ').replace(/\s+/g, ' ').trim()
}
export function singular(w: string) {
  if (w.length > 4 && w.endsWith('es') && !w.endsWith('ces')) return w.slice(0, -2)
  if (w.length > 3 && w.endsWith('ces')) return w.slice(0, -3) + 'z'
  if (w.length > 3 && w.endsWith('s')) return w.slice(0, -1)
  return w
}

/** "1 pieza", "16 latas", "3 pares". */
export function unidades(n: number, u: string) {
  const uno = Math.abs(n) === 1
  const nombre = u === 'pza' ? 'pieza' : u
  const plural = uno ? nombre : /([aeiou]|t)$/.test(nombre) ? nombre + 's' : /s$/.test(nombre) ? nombre : nombre + 'es'
  return `${n.toLocaleString('es-MX')} ${plural}`
}

/** El servidor puede mandar listas vacías como null (planos viejos o automáticos). */
export function normalizarDatos(d: DatosBodega): DatosBodega {
  const p = d.plano
  return { ...d, productos: d.productos ?? [], anaqueles: d.anaqueles ?? [],
    plano: { ...p, muebles: p.muebles ?? [], areas: p.areas ?? [], paredes: p.paredes ?? [], columnas: p.columnas ?? [] } }
}
