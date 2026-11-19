// Cliente de la API de Anaquel.
import { BASE } from './router'

/** Las fotos llegan como /media/x.jpg; se sirven bajo BASE. */
export const media = (u: string) => BASE + u

export type Tienda = {
  id: number; nombre: string; slug: string; email?: string; telefono: string
  direccion: string; lat: number | null; lng: number | null
}
export type Producto = {
  id: number; codigo: string; nombre: string; categoria: string; precio_c: number
  stock: number | null; stock_min: number; proveedor_id: number | null
  productor_id: number | null; productor?: string
}
export type Nivel = 'lleno' | 'poco' | 'vacio'
export type Zona = {
  id: number; producto_id: number | null; producto: string
  x: number; y: number; w: number; h: number; nivel: Nivel; score: number
}
export type Anaquel = {
  id: number; nombre: string; foto_ref: string; ultima_foto: string | null
  ultima_en: number | null; zonas: Zona[]; faltantes: number
}
export type Sugerencia = {
  tipo: 'proveedor' | 'productor'; id: number; nombre: string; telefono: string
  lugar?: string; distancia_km?: number; razon: string
}
export type Faltante = {
  producto: Producto; nivel: 'vacio' | 'poco' | 'bajo'; motivo: string
  en_pedido: number | null; sugerido: number; sugerencias: Sugerencia[]
}
export type Proveedor = { id: number; nombre: string; telefono: string; surte: string }
export type Productor = {
  id: number; nombre: string; telefono: string; productos: string; descripcion: string
  lugar: string; lat: number; lng: number; distancia_km?: number
}
export type ItemPedido = { producto_id: number | null; nombre: string; cantidad: number; recibido: boolean }
export type Pedido = {
  id: number; tipo: 'proveedor' | 'productor' | 'otro'; destino: string; telefono: string
  estado: 'enviado' | 'confirmado' | 'llego' | 'cancelado'; nota: string; creado: number
  actualizado: number; llego_auto: boolean; atrasado: boolean; dias: number
  items: ItemPedido[]; mensaje: string; whatsapp: string
}
export type Resumen = {
  faltantes: number; sin_pedir: number; pedidos_abiertos: number; atrasados: number
  anaqueles: number; sin_foto_hoy: number; productos: number
}
export type ResultadoFoto = { anaquel: Anaquel; metodo: string; faltantes: Zona[]; llegaron: Pedido[] }
export type ProductoPublico = {
  nombre: string; categoria: string; precio_c: number; estado: 'hay' | 'poco' | 'agotado'
  pedido: boolean; productor?: string; lugar?: string
}
export type TiendaPublica = {
  tienda: Tienda; productos: ProductoPublico[]; hecho_cerca: ProductoPublico[]; actualizado: number | null
}
export type TiendaCercana = { nombre: string; slug: string; direccion: string; lat: number; lng: number; distancia_km?: number }

const CLAVE = 'anaquel.token'

export function token(): string | null {
  try { return localStorage.getItem(CLAVE) } catch { return null }
}
export function guardarToken(t: string | null) {
  try { if (t) localStorage.setItem(CLAVE, t); else localStorage.removeItem(CLAVE) } catch { /* sin almacenamiento */ }
}

export class ErrorApi extends Error {
  codigo: number
  constructor(codigo: number, mensaje: string) { super(mensaje); this.codigo = codigo }
}

async function pedir<T>(metodo: string, ruta: string, cuerpo?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  const t = token()
  if (t) headers.Authorization = `Bearer ${t}`
  let body: BodyInit | undefined
  if (cuerpo instanceof FormData) body = cuerpo
  else if (cuerpo !== undefined) { body = JSON.stringify(cuerpo); headers['Content-Type'] = 'application/json' }
  let r: Response
  try {
    r = await fetch(BASE + '/api' + ruta, { method: metodo, headers, body })
  } catch {
    throw new ErrorApi(0, 'Sin conexión. Revisa tu internet.')
  }
  const datos = await r.json().catch(() => ({}))
  if (!r.ok) {
    if (r.status === 401 && t) { guardarToken(null); window.dispatchEvent(new Event('anaquel:salir')) }
    throw new ErrorApi(r.status, datos.error ?? 'Algo salió mal')
  }
  return datos as T
}

export const api = {
  get: <T,>(r: string) => pedir<T>('GET', r),
  post: <T,>(r: string, c?: unknown) => pedir<T>('POST', r, c),
  put: <T,>(r: string, c?: unknown) => pedir<T>('PUT', r, c),
  del: <T,>(r: string) => pedir<T>('DELETE', r),
}

/** Reduce la foto en el navegador (máx. 1600 px) antes de subirla: menos datos, más rápido. */
export async function prepararFoto(archivo: File): Promise<Blob> {
  try {
    const bmp = await createImageBitmap(archivo, { imageOrientation: 'from-image' })
    const f = Math.min(1, 1600 / Math.max(bmp.width, bmp.height))
    const c = document.createElement('canvas')
    c.width = Math.round(bmp.width * f)
    c.height = Math.round(bmp.height * f)
    c.getContext('2d')!.drawImage(bmp, 0, 0, c.width, c.height)
    return await new Promise((ok) => c.toBlob((b) => ok(b ?? archivo), 'image/jpeg', 0.85))
  } catch {
    return archivo
  }
}

export async function formFoto(archivo: File, extra: Record<string, string> = {}) {
  const fd = new FormData()
  fd.append('foto', await prepararFoto(archivo), 'foto.jpg')
  for (const [k, v] of Object.entries(extra)) fd.append(k, v)
  return fd
}

export const pesos = (c: number) =>
  (c / 100).toLocaleString('es-MX', { style: 'currency', currency: 'MXN' })

export function hace(ms: number | null): string {
  if (!ms) return 'nunca'
  const min = Math.round((Date.now() - ms) / 60000)
  if (min < 1) return 'hace un momento'
  if (min < 60) return `hace ${min} min`
  const h = Math.round(min / 60)
  if (h < 24) return `hace ${h} h`
  const d = Math.round(h / 24)
  return d === 1 ? 'ayer' : `hace ${d} días`
}
