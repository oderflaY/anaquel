import { useEffect, useRef, useState, type ReactNode } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { ErrorApi } from './api'

// ---- Avisos flotantes ----
let mostrar: (t: string) => void = () => {}
export const avisar = (t: string) => mostrar(t)

export function Toasts() {
  const [texto, setTexto] = useState<string | null>(null)
  useEffect(() => {
    let timer: number
    mostrar = (t) => { setTexto(t); clearTimeout(timer); timer = window.setTimeout(() => setTexto(null), 3500) }
  }, [])
  return texto ? <div className="toast" role="status">{texto}</div> : null
}

export const mensajeDe = (e: unknown) => (e instanceof ErrorApi || e instanceof Error ? e.message : 'Algo salió mal')

// ---- Carga de datos ----
export function useDatos<T>(cargar: () => Promise<T>, deps: unknown[] = []) {
  const [datos, setDatos] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  useEffect(() => {
    let vivo = true
    setError(null)
    cargar().then((d) => vivo && setDatos(d)).catch((e) => vivo && setError(mensajeDe(e)))
    return () => { vivo = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version])
  return { datos, error, recargar: () => setVersion((v) => v + 1), setDatos }
}

export function Estado({ error, cargando }: { error: string | null; cargando: boolean }) {
  if (error) return <div className="aviso error">{error}</div>
  if (cargando) return <div className="vacio-lista">Cargando…</div>
  return null
}

export function Nivel({ nivel }: { nivel: string }) {
  const t: Record<string, string> = {
    lleno: 'Lleno', poco: 'Queda poco', vacio: 'Vacío', bajo: 'Stock bajo',
    hay: 'Hay', agotado: 'Se acabó', enviado: 'Enviado', confirmado: 'Confirmado',
    llego: 'Llegó', cancelado: 'Cancelado', atrasado: 'No ha llegado',
  }
  return <span className={`chip ${nivel}`}>{t[nivel] ?? nivel}</span>
}

// ---- Diálogo modal ----
export function Dialogo({ abierto, onCerrar, titulo, children }: { abierto: boolean; onCerrar: () => void; titulo: string; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const d = ref.current
    if (!d) return
    if (abierto && !d.open) d.showModal()
    if (!abierto && d.open) d.close()
  }, [abierto])
  return (
    <dialog ref={ref} onClose={onCerrar} onClick={(e) => e.target === ref.current && onCerrar()}>
      <div className="cuerpo">
        <div className="fila entre">
          <h2 style={{ margin: 0 }}>{titulo}</h2>
          <button className="boton claro chico" onClick={onCerrar} aria-label="Cerrar">✕</button>
        </div>
        {abierto && children}
      </div>
    </dialog>
  )
}

// ---- Mapa (Leaflet + OpenStreetMap) ----
export type Punto = { lat: number; lng: number; titulo: string; detalle?: string; color?: string; enlace?: string }

const DURANGO: [number, number] = [24.0277, -104.6532]

function icono(color = '#0e7c5a') {
  return L.divIcon({
    className: '',
    html: `<div style="width:22px;height:22px;border-radius:50% 50% 50% 0;transform:rotate(-45deg);background:${color};border:3px solid #fff;box-shadow:0 2px 6px rgb(0 0 0/.35)"></div>`,
    iconSize: [22, 22], iconAnchor: [11, 22], popupAnchor: [0, -20],
  })
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!)

export function Mapa({ puntos, centro, alElegir, elegido, alto }: {
  puntos: Punto[]; centro?: [number, number]; alElegir?: (lat: number, lng: number) => void
  elegido?: { lat: number; lng: number } | null; alto?: number
}) {
  const div = useRef<HTMLDivElement>(null)
  const mapa = useRef<L.Map | null>(null)
  const capa = useRef<L.LayerGroup | null>(null)
  const marca = useRef<L.Marker | null>(null)
  const elegirRef = useRef(alElegir)
  elegirRef.current = alElegir

  useEffect(() => {
    if (!div.current || mapa.current) return
    const m = L.map(div.current, { scrollWheelZoom: false }).setView(centro ?? DURANGO, 13)
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, attribution: '© OpenStreetMap',
    }).addTo(m)
    capa.current = L.layerGroup().addTo(m)
    m.on('click', (e: L.LeafletMouseEvent) => elegirRef.current?.(e.latlng.lat, e.latlng.lng))
    mapa.current = m
    return () => { m.remove(); mapa.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (centro && mapa.current) mapa.current.setView(centro, mapa.current.getZoom())
  }, [centro?.[0], centro?.[1]]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const c = capa.current
    if (!c) return
    c.clearLayers()
    for (const p of puntos) {
      const html = `<b>${esc(p.titulo)}</b>${p.detalle ? `<br>${esc(p.detalle)}` : ''}${p.enlace ? `<br><a href="${esc(p.enlace)}">Ver</a>` : ''}`
      L.marker([p.lat, p.lng], { icon: icono(p.color) }).bindPopup(html).addTo(c)
    }
  }, [puntos])

  useEffect(() => {
    const m = mapa.current
    if (!m) return
    marca.current?.remove()
    marca.current = elegido ? L.marker([elegido.lat, elegido.lng], { icon: icono('#f2b233') }).addTo(m) : null
  }, [elegido?.lat, elegido?.lng]) // eslint-disable-line react-hooks/exhaustive-deps

  return <div ref={div} className="mapa" style={alto ? { height: alto } : undefined} />
}

export function miUbicacion(): Promise<[number, number]> {
  return new Promise((ok, mal) => {
    if (!navigator.geolocation) return mal(new Error('Tu navegador no da la ubicación'))
    navigator.geolocation.getCurrentPosition(
      (p) => ok([p.coords.latitude, p.coords.longitude]),
      () => mal(new Error('No se pudo obtener tu ubicación. Toca el mapa para marcarla.')),
      { enableHighAccuracy: true, timeout: 10000 },
    )
  })
}
