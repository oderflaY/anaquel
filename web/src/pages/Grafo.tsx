// Mi red en grafo: el vecindario de la tienda en Neo4j (lo que vende, quién lo
// ofrece al mayoreo y qué tiendas parecidas venden lo mismo) dibujado con una
// simulación de fuerzas sencilla, y debajo las recomendaciones que salen de él.
import { useMemo, useState } from 'react'
import { api, media } from '../api'
import { Estado, useDatos } from '../ui'

type Nodo = { id: string; tipo: 'yo' | 'tienda' | 'proveedor' | 'articulo' | 'producto'; etiqueta: string; foto?: string; detalle?: string }
type Liga = { de: string; a: string; tipo: string }
type Mapa = { nodos: Nodo[]; ligas: Liga[] }
type Recomendacion = {
  tipo: string; titulo: string; motivo: string; cantidad?: number; ganancia?: number
  oferta?: { nombre: string; foto?: string }; tienda?: { nombre: string }
}

const COLOR: Record<Nodo['tipo'], string> = {
  yo: '#0e7c5a', proveedor: '#2f5da8', tienda: '#8e44ad', articulo: '#e0871b', producto: '#12a374',
}
const NOMBRE: Record<Nodo['tipo'], string> = {
  yo: 'Tu tienda', proveedor: 'Proveedor (mayoreo)', tienda: 'Tienda parecida', articulo: 'Artículo que vendes', producto: 'Oferta de mayoreo',
}
const RADIO: Record<Nodo['tipo'], number> = { yo: 26, proveedor: 18, tienda: 14, articulo: 12, producto: 9 }
const ETIQUETA_TIPO: Record<string, string> = {
  surtir: 'Surtir', nuevo: 'Nuevo para ti', proveedor: 'De confianza', vender: 'Para vender', exclusivo: 'Exclusivo',
}

type P = { x: number; y: number; vx: number; vy: number }

/** Simulación de fuerzas (repulsión + resortes + gravedad al centro), ya resuelta. */
function acomodar(m: Mapa, ancho: number, alto: number): Map<string, P> {
  const pos = new Map<string, P>()
  const n = m.nodos.length
  m.nodos.forEach((x, i) => {
    const ang = (i / Math.max(1, n)) * Math.PI * 2
    const r = x.tipo === 'yo' ? 0 : x.tipo === 'articulo' ? 0.25 : 0.42
    pos.set(x.id, { x: ancho / 2 + Math.cos(ang) * ancho * r, y: alto / 2 + Math.sin(ang) * alto * r, vx: 0, vy: 0 })
  })
  const largo: Record<string, number> = { VENDE: 90, ES: 45, OFRECE: 55 }
  for (let it = 0; it < 320; it++) {
    const alfa = 1 - it / 320
    for (let i = 0; i < n; i++) {
      const a = pos.get(m.nodos[i].id)!
      for (let j = i + 1; j < n; j++) {
        const b = pos.get(m.nodos[j].id)!
        let dx = a.x - b.x, dy = a.y - b.y
        const d2 = Math.max(dx * dx + dy * dy, 30)
        const f = (1800 / d2) * alfa
        const d = Math.sqrt(d2); dx /= d; dy /= d
        a.vx += dx * f; a.vy += dy * f; b.vx -= dx * f; b.vy -= dy * f
      }
    }
    for (const l of m.ligas) {
      const a = pos.get(l.de), b = pos.get(l.a)
      if (!a || !b) continue
      const dx = b.x - a.x, dy = b.y - a.y
      const d = Math.max(1, Math.hypot(dx, dy))
      const f = ((d - (largo[l.tipo] ?? 70)) / d) * 0.06 * alfa
      a.vx += dx * f; a.vy += dy * f; b.vx -= dx * f; b.vy -= dy * f
    }
    for (const x of m.nodos) {
      const p = pos.get(x.id)!
      if (x.tipo === 'yo') { p.x = ancho / 2; p.y = alto / 2; p.vx = p.vy = 0; continue }
      p.vx += (ancho / 2 - p.x) * 0.004 * alfa; p.vy += (alto / 2 - p.y) * 0.004 * alfa
      p.x += p.vx; p.y += p.vy; p.vx *= 0.55; p.vy *= 0.55
      p.x = Math.min(ancho - 20, Math.max(20, p.x)); p.y = Math.min(alto - 20, Math.max(20, p.y))
    }
  }
  return pos
}

const corto = (t: string, n = 18) => (t.length > n ? t.slice(0, n - 1) + '…' : t)

export default function Grafo() {
  const mapa = useDatos(() => api.get<Mapa>('/grafo/mapa'))
  const recos = useDatos(() => api.get<{ recomendaciones: Recomendacion[]; grafo: boolean }>('/grafo/recomendaciones'))
  const [sel, setSel] = useState<string | null>(null)
  const ancho = 720, alto = 560
  const pos = useMemo(() => (mapa.datos ? acomodar(mapa.datos, ancho, alto) : null), [mapa.datos])
  const vecinos = useMemo(() => {
    const s = new Set<string>()
    if (sel && mapa.datos) for (const l of mapa.datos.ligas) { if (l.de === sel) s.add(l.a); if (l.a === sel) s.add(l.de) }
    return s
  }, [sel, mapa.datos])
  const elegido = mapa.datos?.nodos.find((n) => n.id === sel)

  return (
    <div className="pagina" style={{ display: 'grid', gap: 16 }}>
      <div>
        <h1 style={{ margin: 0 }}>Tu red en grafo</h1>
        <p style={{ color: 'var(--texto-2)', margin: '4px 0 0' }}>
          Cada punto es una tienda, un artículo o una oferta de mayoreo; las líneas dicen quién vende y quién ofrece qué.
          De aquí salen las recomendaciones de tu socio.
        </p>
      </div>
      <Estado error={mapa.error} cargando={!mapa.datos && !mapa.error} />
      {mapa.datos && pos && (
        <div style={{ background: 'var(--superficie)', border: '1px solid var(--borde)', borderRadius: 'var(--radio)', overflow: 'hidden' }}>
          <svg viewBox={`0 0 ${ancho} ${alto}`} style={{ width: '100%', height: 'auto', display: 'block', touchAction: 'manipulation' }}
            onClick={() => setSel(null)} role="img" aria-label="Grafo de tu tienda en la red">
            {mapa.datos.ligas.map((l, i) => {
              const a = pos.get(l.de), b = pos.get(l.a)
              if (!a || !b) return null
              const activa = sel !== null && (l.de === sel || l.a === sel)
              return <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y}
                stroke={activa ? 'var(--marca)' : 'var(--borde)'} strokeWidth={activa ? 2.4 : 1.2}
                strokeDasharray={l.tipo === 'ES' ? '3 3' : undefined} opacity={sel && !activa ? 0.35 : 1} />
            })}
            {mapa.datos.nodos.map((n) => {
              const p = pos.get(n.id)!
              const apagado = sel !== null && n.id !== sel && !vecinos.has(n.id)
              const r = RADIO[n.tipo]
              const conTexto = n.tipo !== 'producto' || n.id === sel || vecinos.has(n.id)
              return (
                <g key={n.id} transform={`translate(${p.x},${p.y})`} opacity={apagado ? 0.25 : 1} style={{ cursor: 'pointer' }}
                  onClick={(e) => { e.stopPropagation(); setSel(n.id === sel ? null : n.id) }}>
                  <circle r={r + 3} fill={COLOR[n.tipo]} opacity={0.18} />
                  <circle r={r} fill={COLOR[n.tipo]} stroke="var(--superficie)" strokeWidth={2} />
                  {conTexto && <text y={r + 13} textAnchor="middle" fontSize={n.tipo === 'yo' ? 13 : 11}
                    fontWeight={n.tipo === 'yo' ? 800 : 600} fill="var(--texto)" style={{ paintOrder: 'stroke', stroke: 'var(--superficie)', strokeWidth: 3 }}>
                    {corto(n.etiqueta, n.tipo === 'yo' ? 26 : 18)}
                  </text>}
                </g>
              )
            })}
          </svg>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, padding: '10px 14px', borderTop: '1px solid var(--borde)', fontSize: 13 }}>
            {(Object.keys(COLOR) as Nodo['tipo'][]).map((t) => (
              <span key={t} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: 'var(--texto-2)' }}>
                <span style={{ width: 10, height: 10, borderRadius: 5, background: COLOR[t] }} />{NOMBRE[t]}
              </span>
            ))}
          </div>
          {elegido && (
            <div style={{ display: 'flex', gap: 12, alignItems: 'center', padding: '12px 14px', borderTop: '1px solid var(--borde)' }}>
              {elegido.foto && <img src={media(elegido.foto)} alt="" width={48} height={48} style={{ objectFit: 'contain', borderRadius: 10, background: '#f4f6f5' }} />}
              <div>
                <div style={{ fontSize: 12, fontWeight: 800, color: COLOR[elegido.tipo], textTransform: 'uppercase', letterSpacing: 1 }}>{NOMBRE[elegido.tipo]}</div>
                <div style={{ fontWeight: 700 }}>{elegido.etiqueta}{elegido.detalle ? ` · ${elegido.detalle}` : ''}</div>
                <div style={{ color: 'var(--texto-2)', fontSize: 13 }}>{vecinos.size} conexiones</div>
              </div>
            </div>
          )}
        </div>
      )}
      {recos.datos && recos.datos.recomendaciones.length > 0 && (
        <div style={{ display: 'grid', gap: 10 }}>
          <h2 style={{ margin: '8px 0 0' }}>Lo que sale del grafo para ti</h2>
          {recos.datos.recomendaciones.map((r, i) => (
            <div key={i} style={{ display: 'flex', gap: 12, alignItems: 'center', background: 'var(--superficie)', border: '1px solid var(--borde)', borderRadius: 'var(--radio)', padding: 12 }}>
              {r.oferta?.foto
                ? <img src={media(r.oferta.foto)} alt="" width={52} height={52} style={{ objectFit: 'contain', borderRadius: 12, background: '#f4f6f5', flexShrink: 0 }} />
                : <span style={{ width: 52, height: 52, borderRadius: 12, background: 'var(--marca-suave)', flexShrink: 0 }} />}
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 11, fontWeight: 800, color: 'var(--marca)', textTransform: 'uppercase', letterSpacing: 1 }}>{ETIQUETA_TIPO[r.tipo] ?? r.tipo}</div>
                <div style={{ fontWeight: 700 }}>{r.titulo}</div>
                <div style={{ color: 'var(--texto-2)', fontSize: 14 }}>{r.motivo}{r.ganancia ? ` · ganas ${r.ganancia}%` : ''}</div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
