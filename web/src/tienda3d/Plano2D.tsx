// Plano 2D de la bodega: la misma tienda vista desde arriba en SVG, para
// equipos con pocos recursos (sin WebGL o con poca memoria) y como mesa de
// trabajo del armador: mover muebles, trazar paredes, poner columnas, borrar.
// Coordenadas en metros: x a lo ancho, y = z del local (fondo arriba, calle abajo).
import { useMemo, useRef, useState, type PointerEvent as PE } from 'react'
import type { Modo } from './escena'
import { encerrados, type Ruta } from './ruta'
import { calcularHuecos, estadoDe, huella, tramosPared, TIPOS, type Columna, type Lugar, type Mueble, type Pared, type Plano } from './tipos'

export type Herramienta = 'mover' | 'muro' | 'media' | 'cristal' | 'columna' | 'borrar'

const COLOR_ESTADO = { agotado: '#d64532', bajo: '#f2a33a', bien: '#2fb487', sin: '#9aa5ad' }
const COLOR_MUEBLE: Record<string, string> = {
  gondola: '#e3efe9', anaquel: '#e7ecf4', refri: '#fde2df', congelador: '#dff1fb', rack: '#efe6d6', tarima: '#f1e3cc',
  perchero: '#ece3f4', mesa: '#f3e6d3', mostrador: '#e9dccb', botanero: '#fdf0cf', vitrina: '#e0f2fb', ganchera: '#f2e9d8', cajas: '#e3ecfb',
}
export const ICONO_2D: Record<string, string> = {
  gondola: '🛒', anaquel: '🗄️', refri: '🧊', congelador: '🍦', rack: '🏗️', tarima: '📦', perchero: '👕', mesa: '🪵',
  mostrador: '💵', botanero: '🍿', vitrina: '💎', ganchera: '🪝', cajas: '🧺',
}

function colorDe(p: Lugar, modo: Modo, maxV: number, maxValor: number): string {
  if (modo === 'rotacion') {
    const t = maxV ? p.vendidos30 / maxV : 0
    return t > 0.66 ? '#d64532' : t > 0.33 ? '#f2a33a' : t > 0 ? '#2f80ed' : '#9aa5ad'
  }
  if (modo === 'valor') {
    const t = maxValor ? (Math.max(0, p.stock ?? 0) * p.precio_c) / maxValor : 0
    return t > 0.5 ? '#6c3fc4' : t > 0.15 ? '#9b7be0' : '#d7cdf3'
  }
  if (modo === 'foto') return p.foto === 'vacio' ? '#d64532' : p.foto === 'poco' ? '#f2a33a' : p.foto === 'lleno' ? '#2fb487' : '#9aa5ad'
  return COLOR_ESTADO[estadoDe(p)]
}

const PEOR = { agotado: 3, bajo: 2, sin: 1, bien: 0 }
const r2 = (v: number, paso: number) => Math.round(v / paso) * paso

export function Plano2D({
  plano, productos, resaltados = [], ruta = null, modo = 'normal', editor = false, herramienta = 'mover', sel = null,
  onSel, onMover, onPlano, onProducto, publica = false,
}: {
  plano: Plano; productos: Lugar[]; resaltados?: number[]; ruta?: Ruta | null; modo?: Modo
  editor?: boolean; herramienta?: Herramienta; sel?: string | null
  onSel?: (id: string | null) => void; onMover?: (id: string, x: number, z: number) => void
  onPlano?: (p: Plano) => void; onProducto?: (id: number) => void; publica?: boolean
}) {
  const svg = useRef<SVGSVGElement>(null)
  const [arrastre, setArrastre] = useState<{ id: string; dx: number; dz: number } | null>(null)
  const [inicio, setInicio] = useState<[number, number] | null>(null) // primer punto de la pared que se traza
  const [cursor, setCursor] = useState<[number, number] | null>(null)
  const M = 0.6 // margen alrededor del local (para la calle y la puerta)
  const huecos = useMemo(() => calcularHuecos(plano, productos), [plano, productos])
  const marcados = useMemo(() => new Set(resaltados), [resaltados])
  const maxV = useMemo(() => Math.max(0, ...productos.map((p) => p.vendidos30)), [productos])
  const maxValor = useMemo(() => Math.max(0, ...productos.map((p) => Math.max(0, p.stock ?? 0) * p.precio_c)), [productos])

  // Un punto por lugar del piso: los productos de distintos entrepaños se juntan y gana el peor estado.
  const puntos = useMemo(() => {
    const g = new Map<string, { x: number; z: number; ps: Lugar[]; w: number }>()
    for (const p of productos) {
      const h = huecos.get(p.id)
      if (!h) continue
      const k = `${h.cx.toFixed(2)}|${h.cz.toFixed(2)}`
      const v = g.get(k) ?? { x: h.cx, z: h.cz, ps: [], w: h.ancho }
      v.ps.push(p)
      g.set(k, v)
    }
    return [...g.values()]
  }, [productos, huecos])

  const enMetros = (e: PE): [number, number] => {
    const s = svg.current!
    const pt = s.createSVGPoint()
    pt.x = e.clientX; pt.y = e.clientY
    const r = pt.matrixTransform(s.getScreenCTM()!.inverse())
    return [Math.max(0, Math.min(plano.ancho, r.x)), Math.max(0, Math.min(plano.fondo, r.y))]
  }

  /** Imán para las paredes: a puntas de otras paredes, a la orilla del local, a 25 cm y en ángulo recto. */
  const iman = ([x, z]: [number, number], desde?: [number, number] | null): [number, number] => {
    const puntas: [number, number][] = (plano.paredes ?? []).flatMap((w) => [[w.x1, w.z1], [w.x2, w.z2]] as [number, number][])
    for (const p of puntas) if (Math.hypot(p[0] - x, p[1] - z) < 0.3) return p
    let nx = r2(x, 0.25), nz = r2(z, 0.25)
    if (x < 0.2) nx = 0; if (x > plano.ancho - 0.2) nx = plano.ancho
    if (z < 0.2) nz = 0; if (z > plano.fondo - 0.2) nz = plano.fondo
    if (desde) {
      const ang = Math.abs(Math.atan2(nz - desde[1], nx - desde[0]) * 180 / Math.PI)
      if (ang < 10 || ang > 170) nz = desde[1]
      else if (Math.abs(ang - 90) < 10) nx = desde[0]
    }
    return [nx, nz]
  }

  const paredes = plano.paredes ?? []
  const columnas = plano.columnas ?? []
  const cambiar = (c: Partial<Plano>) => onPlano?.({ ...plano, ...c })

  function abajo(e: PE<SVGSVGElement>) {
    if (!editor) return
    const p = enMetros(e)
    if (herramienta === 'muro' || herramienta === 'media' || herramienta === 'cristal') {
      const q = iman(p, inicio)
      if (!inicio) { setInicio(q); return }
      if (Math.hypot(q[0] - inicio[0], q[1] - inicio[1]) >= 0.3) {
        const nueva: Pared = { x1: inicio[0], z1: inicio[1], x2: q[0], z2: q[1], alto: herramienta === 'media' ? 1.2 : plano.alto, puerta: 0, tipo: herramienta }
        cambiar({ paredes: [...paredes, nueva] })
      }
      setInicio(q) // sigue trazando desde ahí (doble clic o Esc para terminar)
    } else if (herramienta === 'columna') {
      const c: Columna = { x: r2(p[0], 0.05), z: r2(p[1], 0.05), lado: 0.4 }
      cambiar({ columnas: [...columnas, c] })
    } else if (herramienta === 'mover') onSel?.(null)
  }

  function moverPuntero(e: PE<SVGSVGElement>) {
    const p = enMetros(e)
    if (inicio) setCursor(iman(p, inicio))
    else if (editor && herramienta !== 'mover') setCursor(herramienta === 'borrar' ? p : iman(p))
    if (arrastre && onMover) {
      const m = plano.muebles.find((x) => x.id === arrastre.id)
      if (!m) return
      const h = huella(m)
      onMover(m.id, Math.min(plano.ancho - h.w, Math.max(h.w, r2(p[0] - arrastre.dx, 0.05))), Math.min(plano.fondo - h.d, Math.max(h.d, r2(p[1] - arrastre.dz, 0.05))))
    }
  }

  const terminar = () => { setInicio(null); setCursor(null) }

  function tocarMueble(e: PE, m: Mueble) {
    e.stopPropagation()
    if (!editor) { onSel?.(m.id); return }
    if (herramienta === 'borrar') { if (confirm(`¿Quitar «${m.nombre}»? Sus productos quedan sin lugar.`)) cambiar({ muebles: plano.muebles.filter((x) => x.id !== m.id) }); return }
    if (herramienta !== 'mover') return
    const p = enMetros(e)
    onSel?.(m.id)
    setArrastre({ id: m.id, dx: p[0] - m.x, dz: p[1] - m.z })
    ;(e.target as Element).setPointerCapture?.(e.pointerId)
  }

  const borrarPared = (i: number) => editor && herramienta === 'borrar' && cambiar({ paredes: paredes.filter((_, k) => k !== i) })
  const borrarColumna = (i: number) => editor && herramienta === 'borrar' && cambiar({ columnas: columnas.filter((_, k) => k !== i) })
  const px0 = plano.puerta.x - plano.puerta.ancho / 2, px1 = plano.puerta.x + plano.puerta.ancho / 2
  // En el armador se marca lo que quedó sin pasillo para llegar.
  const sinPaso = useMemo(() => (editor ? encerrados(plano) : new Set<string>()), [editor, plano])
  const trazo = ruta ? ruta.puntos.map(([x, z]) => `${x},${z}`).join(' ') : ''

  return (
    <svg
      ref={svg} className={`plano2d ${editor ? 'editando herr-' + herramienta : ''}`} role="img"
      aria-label={`Plano de la tienda, ${plano.ancho} por ${plano.fondo} metros, ${plano.muebles.length} muebles`}
      viewBox={`${-M} ${-M} ${plano.ancho + 2 * M} ${plano.fondo + 2 * M + 0.4}`} preserveAspectRatio="xMidYMid meet"
      onPointerDown={abajo} onPointerMove={moverPuntero} onPointerUp={() => setArrastre(null)} onPointerLeave={() => { setArrastre(null); if (!inicio) setCursor(null) }}
      onDoubleClick={terminar} onKeyDown={(e) => e.key === 'Escape' && terminar()} tabIndex={0}
    >
      <defs>
        <pattern id="p2d-fina" width="0.5" height="0.5" patternUnits="userSpaceOnUse"><path d="M0.5 0H0V0.5" fill="none" stroke="currentColor" strokeOpacity="0.12" strokeWidth="0.01" /></pattern>
        <pattern id="p2d-metro" width="1" height="1" patternUnits="userSpaceOnUse"><rect width="1" height="1" fill="url(#p2d-fina)" /><path d="M1 0H0V1" fill="none" stroke="currentColor" strokeOpacity="0.22" strokeWidth="0.015" /></pattern>
        <marker id="p2d-flecha" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="4" markerHeight="4" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#0e7c5a" /></marker>
      </defs>
      {/* Piso y paredes del local */}
      <rect className="p2d-piso" x={0} y={0} width={plano.ancho} height={plano.fondo} rx={0.05} />
      <rect x={0} y={0} width={plano.ancho} height={plano.fondo} fill="url(#p2d-metro)" />
      <path className="p2d-muro-ext" d={`M${px0} ${plano.fondo} H0 V0 H${plano.ancho} V${plano.fondo} H${px1}`} />
      <path className="p2d-puerta" d={`M${px0} ${plano.fondo} A${plano.puerta.ancho / 2} ${plano.puerta.ancho / 2} 0 0 1 ${plano.puerta.x} ${plano.fondo - plano.puerta.ancho / 2}`} />
      <text className="p2d-calle" x={plano.puerta.x} y={plano.fondo + 0.42} textAnchor="middle">ENTRADA · CALLE</text>
      {/* Áreas */}
      {plano.areas.map((a, i) => (
        <g key={'a' + i}>
          <rect x={a.x} y={a.z} width={a.ancho} height={a.fondo} fill={a.color} opacity={0.55} rx={0.04} />
          <text className="p2d-area" x={a.x + 0.12} y={a.z + 0.28}>{a.nombre}</text>
        </g>
      ))}
      {/* Paredes interiores y columnas */}
      {paredes.map((w, i) => (
        <g key={'w' + i} className={`p2d-pared ${w.tipo ?? 'muro'}`} onPointerDown={(e) => { if (herramienta === 'borrar') { e.stopPropagation(); borrarPared(i) } }}>
          {tramosPared(w).map(([x1, z1, x2, z2], k) => <line key={k} x1={x1} y1={z1} x2={x2} y2={z2} />)}
          {w.puerta > 0 && <circle className="p2d-hueco" cx={(w.x1 + w.x2) / 2} cy={(w.z1 + w.z2) / 2} r={w.puerta / 2} />}
        </g>
      ))}
      {columnas.map((c, i) => (
        <rect key={'c' + i} className="p2d-columna" x={c.x - c.lado / 2} y={c.z - c.lado / 2} width={c.lado} height={c.lado}
          onPointerDown={(e) => { if (herramienta === 'borrar') { e.stopPropagation(); borrarColumna(i) } }} />
      ))}
      {/* Muebles */}
      {plano.muebles.map((m) => {
        const encima = plano.muebles.some((o) => { if (o.id === m.id) return false; const a = huella(m), b = huella(o); return Math.abs(m.x - o.x) < a.w + b.w - 0.01 && Math.abs(m.z - o.z) < a.d + b.d - 0.01 })
        return (
          // La posición va en un <g> y la animación en otro: si no, el transform de CSS pisa al del SVG.
          <g key={m.id} transform={`translate(${m.x} ${m.z}) rotate(${-m.rot})`}>
          <g className={`p2d-mueble ${sel === m.id ? 'sel' : ''} ${encima ? 'choca' : ''} ${sinPaso.has(m.id) ? 'encerrado' : ''}`}
            onPointerDown={(e) => tocarMueble(e, m)} role="button" aria-label={`${m.nombre}, ${TIPOS[m.tipo].nombre}`}>
            <title>{m.nombre} · {TIPOS[m.tipo].nombre} · {m.ancho} × {m.fondo} m</title>
            <rect x={-m.ancho / 2} y={-m.fondo / 2} width={m.ancho} height={m.fondo} rx={0.05} fill={COLOR_MUEBLE[m.tipo] ?? '#eee'} />
            {TIPOS[m.tipo].lados === 2 && <line x1={-m.ancho / 2 + 0.05} y1={0} x2={m.ancho / 2 - 0.05} y2={0} className="p2d-respaldo" />}
            {editor && <line className="p2d-frente" x1={0} y1={m.fondo / 2 - 0.02} x2={0} y2={m.fondo / 2 + 0.28} markerEnd="url(#p2d-flecha)" />}
            <text className="p2d-icono" x={0} y={0} textAnchor="middle" dominantBaseline="central" fontSize={Math.min(0.42, Math.min(m.ancho, m.fondo) * 0.6)}>{ICONO_2D[m.tipo]}</text>
          </g>
          </g>
        )
      })}
      {/* Nombres de los muebles (sin girar, para que se lean) */}
      {plano.muebles.map((m) => {
        const h = huella(m)
        return <text key={'n' + m.id} className="p2d-nombre" x={m.x} y={m.z + h.d + 0.2} textAnchor="middle">{m.nombre.length > 22 ? m.nombre.slice(0, 21) + '…' : m.nombre}</text>
      })}
      {/* Productos: un punto por lugar, del color de su estado */}
      {!editor && puntos.map((pt, i) => {
        const peor = pt.ps.reduce((a, b) => (PEOR[estadoDe(b)] > PEOR[estadoDe(a)] ? b : a))
        const on = pt.ps.some((p) => marcados.has(p.id))
        const r = Math.max(0.05, Math.min(0.13, pt.w / 2.6))
        return (
          <g key={'p' + i} className={`p2d-prod ${on ? 'on' : ''} ${marcados.size && !on ? 'apagado' : ''}`}
            onPointerDown={(e) => { e.stopPropagation(); onProducto?.((pt.ps.find((p) => marcados.has(p.id)) ?? peor).id) }} style={{ animationDelay: `${(i % 30) * 18}ms` }}>
            <title>{pt.ps.map((p) => `${p.nombre}${publica || p.stock == null ? '' : ` (${p.stock})`}`).join('\n')}</title>
            {on && <circle className="p2d-pulso" cx={pt.x} cy={pt.z} r={r * 2.2} />}
            <circle cx={pt.x} cy={pt.z} r={r} fill={colorDe(peor, modo, maxV, maxValor)} />
          </g>
        )
      })}
      {/* Ruta a pie */}
      {ruta && (
        <g className="p2d-ruta">
          <polyline points={trazo} className="fondo" />
          <polyline points={trazo} className="linea" />
          <circle cx={ruta.puntos[0][0]} cy={ruta.puntos[0][1]} r={0.14} className="inicio" />
          <circle cx={ruta.destino[0]} cy={ruta.destino[1]} r={0.16} className="destino" />
          <circle r={0.11} className="caminante">
            <animateMotion dur={`${Math.max(2, ruta.metros / 1.2)}s`} repeatCount="indefinite" path={'M' + ruta.puntos.map(([x, z]) => `${x} ${z}`).join(' L')} />
          </circle>
        </g>
      )}
      {/* Pared que se está trazando */}
      {inicio && cursor && (
        <g className="p2d-borrador">
          <line x1={inicio[0]} y1={inicio[1]} x2={cursor[0]} y2={cursor[1]} />
          <text x={(inicio[0] + cursor[0]) / 2} y={(inicio[1] + cursor[1]) / 2 - 0.12} textAnchor="middle">{Math.hypot(cursor[0] - inicio[0], cursor[1] - inicio[1]).toFixed(2)} m</text>
        </g>
      )}
      {editor && cursor && herramienta !== 'mover' && herramienta !== 'borrar' && <circle className="p2d-mira" cx={cursor[0]} cy={cursor[1]} r={0.07} />}
      {/* Medidas del local */}
      <text className="p2d-medida" x={plano.ancho / 2} y={-0.2} textAnchor="middle">{plano.ancho} m</text>
      <text className="p2d-medida" x={-0.2} y={plano.fondo / 2} textAnchor="middle" transform={`rotate(-90 -0.2 ${plano.fondo / 2})`}>{plano.fondo} m</text>
    </svg>
  )
}

/** ¿Conviene el 2D? Sin WebGL, poca memoria o el usuario lo pidió (?vista=2d). */
export function vistaInicial(): '2d' | '3d' {
  try {
    const q = new URLSearchParams(location.search).get('vista')
    if (q === '2d' || q === '3d') return q
    const g = localStorage.getItem('bodega-vista')
    if (g === '2d' || g === '3d') return g
  } catch { /* sin almacenamiento */ }
  const nav = navigator as Navigator & { deviceMemory?: number }
  if ((nav.deviceMemory ?? 8) <= 2 || (navigator.hardwareConcurrency ?? 8) <= 2) return '2d'
  try {
    const c = document.createElement('canvas')
    if (!(c.getContext('webgl2') || c.getContext('webgl'))) return '2d'
  } catch { return '2d' }
  return '3d'
}
