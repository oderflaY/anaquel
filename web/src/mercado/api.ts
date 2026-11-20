// Cliente de los servicios del mercado. La sesión viaja en una cookie
// HttpOnly (el JavaScript nunca ve el token), así que aquí no hay tokens.
import { BASE } from '../router'

export class ErrorMercado extends Error {
  codigo: number
  clave?: string
  constructor(codigo: number, mensaje: string, clave?: string) { super(mensaje); this.codigo = codigo; this.clave = clave }
}

/** Evento global: la sesión terminó o la cuenta no puede entrar. */
export const EVENTO_SESION = 'mercado:sesion'

async function pedir<T>(metodo: string, ruta: string, cuerpo?: unknown, señal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = {}
  let body: BodyInit | undefined
  if (cuerpo instanceof FormData) body = cuerpo
  else if (cuerpo !== undefined) { body = JSON.stringify(cuerpo); headers['Content-Type'] = 'application/json' }
  let r: Response
  try {
    r = await fetch(BASE + '/api' + ruta, { method: metodo, headers, body, credentials: 'same-origin', signal: señal })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ErrorMercado(0, 'Sin conexión. Revisa tu internet e intenta de nuevo.', 'sin_conexion')
  }
  const datos = await r.json().catch(() => ({}))
  if (!r.ok) {
    const clave: string | undefined = datos.codigo
    if (r.status === 401 || clave === 'en_revision' || clave === 'suspendida') {
      window.dispatchEvent(new CustomEvent(EVENTO_SESION, { detail: clave ?? 'sin_sesion' }))
    }
    throw new ErrorMercado(r.status, datos.error ?? 'Algo salió mal. Intenta de nuevo.', clave)
  }
  return datos as T
}

export const api = {
  get: <T,>(r: string, s?: AbortSignal) => pedir<T>('GET', r, undefined, s),
  post: <T,>(r: string, c?: unknown) => pedir<T>('POST', r, c ?? {}),
  put: <T,>(r: string, c?: unknown) => pedir<T>('PUT', r, c),
  del: <T,>(r: string) => pedir<T>('DELETE', r),
}

export const urlMedia = (u: string) => BASE + u

// ---- Tipos ----
export type Cuenta = {
  id: number; nombre: string; slug: string; email: string; telefono: string; giro: string; descripcion: string
  colonia: string; rol: 'cliente' | 'admin'; estado: 'pendiente' | 'verificada' | 'suspendida'; vende: boolean
  demo: boolean; tienda_slug: string | null
}
export type CuentaDemo = { email: string; nombre: string; que: string }
export type Categoria = { clave: string; nombre: string; icono: string }
export type Foto = { id?: number; url: string; alt: string; ancho?: number; alto?: number }
export type Unidad = 'pieza' | 'kilo' | 'litro' | 'caja' | 'paquete' | 'bulto' | 'docena' | 'metro' | 'servicio'
export type Publicacion = {
  id: number; titulo: string; descripcion?: string; categoria: string; precio_c: number; unidad: Unidad
  existencias: number | null; minimo: number; estado: 'activa' | 'pausada'; foto: Foto | null; fotos?: Foto[]
  vendedor: { nombre: string; slug: string; colonia: string }; distancia_km: number | null; es_mia: boolean; creada: string
}
export type Vendedor = { nombre: string; slug: string; colonia: string; giro: string; descripcion: string; tienda_slug: string | null }
export type Pagina<T> = { items: T[]; siguiente: number | null }
export type EstadoOrden = 'pendiente' | 'por_cobrar' | 'pagada' | 'entregada' | 'cancelada'
export type Orden = {
  id: number; rol: 'compra' | 'venta'; contraparte: string; contraparte_slug: string; publicacion_id: number | null
  titulo: string; unidad: Unidad; cantidad: number; precio_c: number; total_c: number; nota: string; estado: EstadoOrden
  cobro_id: string | null; foto: Foto | null; creada: string; actualizada: string
  eventos?: { estado: string; por: string; nota: string; creado: string }[]
}
export type Reel = {
  id: number; url: string; descripcion: string; duracion_ms: number; ancho: number; alto: number
  vendedor: { nombre: string; slug: string }; publicacion: { id: number; titulo: string; precio_c: number; unidad: Unidad } | null
  es_mio: boolean; creado: string
}
export type Proveedor = { clave: 'mercadopago' | 'clip' | 'simulador'; nombre: string }
export type Terminal = { id: number; proveedor: Proveedor['clave']; marca: string; nombre: string; dispositivo: string; creada: string }
export type Cobro = {
  id: string; orden_id: number; proveedor: string; marca: string; terminal: string; monto_c: number; descripcion: string
  estado: 'creado' | 'enviado' | 'aprobado' | 'rechazado' | 'cancelado' | 'error'; detalle: string; creado: string
}
export type Registro = {
  id: number; monto_c: number; proveedor: string; marca: string; terminal: string; origen: 'app' | 'terminal'
  orden_id: number | null; detalle: string; pagado_en: string
}
export type TransaccionSim = { id: string; terminal: string; monto_c: number; concepto: string; creada: string }

// ---- Formatos ----
const mxn = new Intl.NumberFormat('es-MX', { style: 'currency', currency: 'MXN', minimumFractionDigits: 0, maximumFractionDigits: 2 })
export const pesos = (c: number) => mxn.format(c / 100)
export const UNIDADES: Record<Unidad, [string, string]> = {
  pieza: ['pieza', 'piezas'], kilo: ['kilo', 'kilos'], litro: ['litro', 'litros'], caja: ['caja', 'cajas'],
  paquete: ['paquete', 'paquetes'], bulto: ['bulto', 'bultos'], docena: ['docena', 'docenas'], metro: ['metro', 'metros'],
  servicio: ['servicio', 'servicios'],
}
export const unidad = (u: Unidad, n = 1) => UNIDADES[u]?.[n === 1 ? 0 : 1] ?? u
export const cantidadTxt = (n: number, u: Unidad) => `${n.toLocaleString('es-MX')} ${unidad(u, n)}`
const fechaFmt = new Intl.DateTimeFormat('es-MX', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
export const fecha = (iso: string) => fechaFmt.format(new Date(iso))
export const ESTADOS: Record<EstadoOrden, { txt: string; tono: 'aviso' | 'info' | 'exito' | 'error' | 'neutro' }> = {
  pendiente: { txt: 'Apartado', tono: 'aviso' },
  por_cobrar: { txt: 'Cobrando', tono: 'info' },
  pagada: { txt: 'Pagado', tono: 'exito' },
  entregada: { txt: 'Entregado', tono: 'neutro' },
  cancelada: { txt: 'Cancelado', tono: 'error' },
}
const CONECTORES = new Set(['el', 'la', 'los', 'las', 'de', 'del', 'y', 'e'])
export const iniciales = (n: string) => n.split(/\s+/).filter((p) => p && !CONECTORES.has(p.toLowerCase())).slice(0, 2).map((p) => p[0]).join('').toUpperCase()
