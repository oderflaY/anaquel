import { useEffect, useMemo, useRef, useState } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { api, media } from '../api'
import { ir } from '../router'

// Mapa de los negocios asociados a la red: tiendas, mayoristas, servicios y
// productores. Tocas un punto y abajo sale su tarjeta (deslizable) con Chat y
// Pedir. Dentro de la app (?app=1) esos botones navegan a anaquel://…, que la
// app atrapa para abrir su chat nativo.

type Punto = {
  id: number; nombre: string; slug: string; giro: string; telefono: string; descripcion: string
  distancia_km?: number; productos: number; chat_id?: number
  lat: number; lng: number; direccion: string; horario?: string; entrega?: string
  verificada: boolean; calificacion: number; resenas: number
  tipo: 'mayorista' | 'tienda' | 'servicio'; destacados: string[]; foto?: string
}
type Productor = { id: number; nombre: string; productos: string; lugar: string; telefono: string; lat: number; lng: number }
type MapaRedDatos = { yo: { nombre: string; lat: number; lng: number } | null; tiendas: Punto[]; productores: Productor[] }

type Item = { clave: string; tipo: Punto['tipo'] | 'productor'; lat: number; lng: number; nombre: string; t?: Punto; p?: Productor }

const COLOR: Record<Item['tipo'], string> = { mayorista: '#0e7c5a', tienda: '#2f5da8', servicio: '#7b4bc4', productor: '#e0871b' }
const NOMBRE: Record<Item['tipo'], string> = { mayorista: 'Mayoristas', tienda: 'Tiendas', servicio: 'Servicios', productor: 'Productores' }
const ICONO: Record<Item['tipo'], string> = { mayorista: '🏭', tienda: '🏪', servicio: '🚚', productor: '🌽' }

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!)
const sinDemo = (s: string) => s.replace(/ \(demo\)$/, '')
const km = (d?: number) => (d == null ? '' : d < 1 ? `a ${Math.round(d * 1000)} m` : `a ${d.toFixed(1)} km`)

function pin(it: Item, activo: boolean) {
  const c = COLOR[it.tipo]
  const tam = activo ? 46 : it.tipo === 'mayorista' ? 38 : 32
  return L.divIcon({
    className: '',
    html: `<div class="pin-red ${activo ? 'activo' : ''}" style="--c:${c};width:${tam}px;height:${tam}px"><span>${ICONO[it.tipo]}</span></div>`,
    iconSize: [tam, tam], iconAnchor: [tam / 2, tam],
  })
}

export default function MapaRed() {
  const enApp = new URLSearchParams(location.search).get('app') === '1'
  const [datos, setDatos] = useState<MapaRedDatos | null>(null)
  const [error, setError] = useState('')
  const [filtro, setFiltro] = useState<Item['tipo'] | null>(null)
  const [activo, setActivo] = useState<string | null>(null)
  const div = useRef<HTMLDivElement>(null)
  const mapa = useRef<L.Map | null>(null)
  const capa = useRef<L.LayerGroup | null>(null)
  const carrusel = useRef<HTMLDivElement>(null)
  const desdeCarrusel = useRef(false)
  const encuadrado = useRef(false)

  useEffect(() => { api.get<MapaRedDatos>('/mapa').then(setDatos).catch((e) => setError(e.message)) }, [])

  const items = useMemo<Item[]>(() => {
    if (!datos) return []
    const ts: Item[] = datos.tiendas.map((t) => ({ clave: 't' + t.id, tipo: t.tipo, lat: t.lat, lng: t.lng, nombre: t.nombre, t }))
    const ps: Item[] = datos.productores.map((p) => ({ clave: 'p' + p.id, tipo: 'productor', lat: p.lat, lng: p.lng, nombre: p.nombre, p }))
    return [...ts, ...ps]
  }, [datos])
  const visibles = useMemo(() => items.filter((i) => !filtro || i.tipo === filtro), [items, filtro])
  const conteo = useMemo(() => {
    const c: Partial<Record<Item['tipo'], number>> = {}
    for (const i of items) c[i.tipo] = (c[i.tipo] ?? 0) + 1
    return c
  }, [items])

  // Mapa (una vez).
  useEffect(() => {
    if (!div.current || mapa.current || !datos) return
    const centro: [number, number] = datos.yo ? [datos.yo.lat, datos.yo.lng] : [24.0277, -104.6532]
    const m = L.map(div.current, { zoomControl: !enApp, attributionControl: true }).setView(centro, 13)
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(m)
    if (datos.yo) {
      L.marker(centro, {
        icon: L.divIcon({ className: '', html: '<div class="yo-red"><i></i></div>', iconSize: [22, 22], iconAnchor: [11, 11] }),
        zIndexOffset: 1000,
      }).bindTooltip('Tu tienda', { direction: 'top', offset: [0, -10] }).addTo(m)
    }
    capa.current = L.layerGroup().addTo(m)
    m.on('click', () => setActivo(null))
    mapa.current = m
    return () => { m.remove(); mapa.current = null }
  }, [datos, enApp])

  // Marcadores según el filtro y el elegido.
  useEffect(() => {
    const c = capa.current, m = mapa.current
    if (!c || !m) return
    c.clearLayers()
    for (const it of visibles) {
      L.marker([it.lat, it.lng], { icon: pin(it, it.clave === activo), zIndexOffset: it.clave === activo ? 900 : 0, riseOnHover: true })
        .on('click', (e) => { L.DomEvent.stopPropagation(e); desdeCarrusel.current = false; setActivo(it.clave) })
        .addTo(c)
    }
    if (visibles.length && !activo) {
      // Se encuadran los 10 más cercanos (un productor del otro lado del estado no aleja todo).
      const yo = datos?.yo
      const cerca = yo ? [...visibles].sort((x, y) => (x.lat - yo.lat) ** 2 + (x.lng - yo.lng) ** 2 - (y.lat - yo.lat) ** 2 - (y.lng - yo.lng) ** 2).slice(0, 10) : visibles
      const b = L.latLngBounds(cerca.map((i) => [i.lat, i.lng] as [number, number]))
      if (datos?.yo) b.extend([datos.yo.lat, datos.yo.lng])
      // La primera vez sin animación (el mapa aún no tiene tamaño estable); luego, volando.
      if (encuadrado.current) m.flyToBounds(b.pad(0.15), { duration: 0.6, maxZoom: 15 })
      else { m.invalidateSize(); m.fitBounds(b.pad(0.15), { maxZoom: 15, paddingBottomRight: [0, 200] }); encuadrado.current = true }
    }
  }, [visibles, activo]) // eslint-disable-line react-hooks/exhaustive-deps

  // Al elegir: el mapa vuela al punto y la tarjeta se pone a la vista.
  useEffect(() => {
    const it = items.find((i) => i.clave === activo)
    if (!it || !mapa.current) return
    mapa.current.flyTo([it.lat, it.lng], Math.max(mapa.current.getZoom(), 15), { duration: 0.6 })
    if (!desdeCarrusel.current) document.getElementById('tarjeta-' + it.clave)?.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' })
  }, [activo, items])

  // Deslizar el carrusel elige la tarjeta que quedó al centro.
  useEffect(() => {
    const el = carrusel.current
    if (!el) return
    let t = 0
    const f = () => {
      clearTimeout(t)
      t = window.setTimeout(() => {
        const centro = el.scrollLeft + el.clientWidth / 2
        let mejor: string | null = null, dist = 1e9
        for (const h of Array.from(el.children) as HTMLElement[]) {
          const d = Math.abs(h.offsetLeft + h.offsetWidth / 2 - centro)
          if (d < dist) { dist = d; mejor = h.dataset.clave ?? null }
        }
        if (mejor && mejor !== activo) { desdeCarrusel.current = true; setActivo(mejor) }
      }, 120)
    }
    el.addEventListener('scroll', f, { passive: true })
    return () => { el.removeEventListener('scroll', f); clearTimeout(t) }
  }, [activo, visibles])

  // En la app los botones van a la app; en la web, a sus páginas.
  const abrir = (accion: 'chat' | 'pedir' | 'perfil' | 'llamar', t?: Punto, p?: Productor) => {
    if (accion === 'llamar') { location.href = 'tel:' + (t?.telefono || p?.telefono || ''); return }
    if (!t) return
    if (enApp) location.href = `anaquel://${accion}/${t.id}`
    else ir(`/t/${encodeURIComponent(t.slug)}`)
  }

  if (error) return <div className="vacio-lista">{error}</div>
  return (
    <div className={`mapa-red ${enApp ? 'app' : ''}`}>
      <style>{CSS}</style>
      <div ref={div} className="lienzo" />
      {!datos && <div className="cargando">Cargando el mapa de la red…</div>}
      <div className="chips">
        <button className={!filtro ? 'on' : ''} onClick={() => { setFiltro(null); setActivo(null) }}>Todos <b>{items.length}</b></button>
        {(['mayorista', 'tienda', 'servicio', 'productor'] as const).filter((k) => conteo[k]).map((k) => (
          <button key={k} className={filtro === k ? 'on' : ''} style={{ ['--c' as string]: COLOR[k] }}
            onClick={() => { setFiltro(filtro === k ? null : k); setActivo(null) }}>
            {ICONO[k]} {NOMBRE[k]} <b>{conteo[k]}</b>
          </button>
        ))}
      </div>
      <div className="carrusel" ref={carrusel}>
        {visibles.map((it, n) => {
          const t = it.t, p = it.p
          return (
            <article key={it.clave} id={'tarjeta-' + it.clave} data-clave={it.clave} style={{ ['--c' as string]: COLOR[it.tipo], animationDelay: `${Math.min(n, 8) * 40}ms` }}
              className={`tarjeta ${it.clave === activo ? 'activa' : ''}`} onClick={() => { desdeCarrusel.current = false; setActivo(it.clave) }}>
              <div className="cabeza">
                <div className="foto">{t?.foto ? <img src={media(t.foto)} alt="" loading="lazy" /> : <span>{ICONO[it.tipo]}</span>}</div>
                <div className="quien">
                  <div className="tipo">{it.tipo === 'productor' ? 'Productor local' : t?.giro || NOMBRE[it.tipo]}{t?.verificada ? ' · ✔ verificada' : ''}</div>
                  <h3>{esc(sinDemo(it.nombre))}</h3>
                  <div className="sub">
                    {[t ? km(t.distancia_km) : p?.lugar, t && t.calificacion > 0 ? `★ ${t.calificacion.toFixed(1)} (${t.resenas})` : '', t ? `${t.productos} productos` : '']
                      .filter(Boolean).join(' · ')}
                  </div>
                </div>
              </div>
              <p className="destacados">{t ? (t.destacados.length ? t.destacados.join(' · ') : t.descripcion || t.direccion) : p?.productos}</p>
              {(t?.horario || t?.entrega) && <p className="extra">{[t.horario && `🕒 ${t.horario}`, t.entrega && `🛵 ${t.entrega}`].filter(Boolean).join('   ')}</p>}
              <div className="botones">
                {t ? <>
                  <button className="primario" onClick={(e) => { e.stopPropagation(); abrir('chat', t) }}>{t.chat_id ? 'Abrir chat' : 'Chatear'}</button>
                  {t.productos > 0 && <button onClick={(e) => { e.stopPropagation(); abrir('pedir', t) }}>Pedir</button>}
                  <button onClick={(e) => { e.stopPropagation(); abrir('perfil', t) }}>{enApp ? 'Perfil' : 'Ver'}</button>
                </> : p?.telefono ? <button className="primario" onClick={(e) => { e.stopPropagation(); abrir('llamar', undefined, p) }}>Llamar</button> : null}
              </div>
            </article>
          )
        })}
      </div>
    </div>
  )
}

const CSS = `
.mapa-red{position:relative;height:calc(100vh - 140px);min-height:480px;border-radius:18px;overflow:hidden;border:1px solid var(--borde);background:var(--fondo)}
.mapa-red.app{height:100vh;border:0;border-radius:0;margin:-.6rem -1rem -2rem}
.mapa-red .lienzo{position:absolute;inset:0;z-index:0}
.mapa-red .cargando{position:absolute;inset:0;display:grid;place-items:center;color:var(--texto-2);z-index:1}
.mapa-red .chips{position:absolute;top:12px;left:0;right:0;z-index:500;display:flex;gap:8px;overflow-x:auto;padding:0 12px;scrollbar-width:none}
.mapa-red .chips::-webkit-scrollbar{display:none}
.mapa-red .chips button{flex:none;border:0;border-radius:999px;padding:9px 14px;font:600 14px system-ui,sans-serif;background:var(--superficie);color:var(--texto);box-shadow:0 4px 14px rgb(0 0 0/.14);transition:transform .15s,background .2s}
.mapa-red .chips button b{opacity:.55;margin-left:4px}
.mapa-red .chips button.on{background:var(--c,var(--marca));color:#fff}
.mapa-red .chips button:active{transform:scale(.94)}
.mapa-red .carrusel{position:absolute;left:0;right:0;bottom:14px;z-index:500;display:flex;gap:12px;overflow-x:auto;scroll-snap-type:x mandatory;padding:6px 7vw 10px;scrollbar-width:none}
.mapa-red.app .carrusel{bottom:22px}
.mapa-red .carrusel::-webkit-scrollbar{display:none}
.mapa-red .tarjeta{flex:none;width:min(86vw,360px);scroll-snap-align:center;background:var(--superficie);color:var(--texto);border-radius:22px;padding:14px;box-shadow:0 10px 30px rgb(0 0 0/.18);border:2px solid transparent;transition:transform .25s cubic-bezier(.2,.9,.3,1.3),border-color .2s;animation:sube .45s cubic-bezier(.2,.9,.3,1.2) both;cursor:pointer}
.mapa-red .tarjeta.activa{border-color:var(--c);transform:translateY(-6px)}
@keyframes sube{from{opacity:0;transform:translateY(40px)}}
.mapa-red .cabeza{display:flex;gap:12px;align-items:center}
.mapa-red .foto{width:56px;height:56px;border-radius:16px;background:color-mix(in srgb,var(--c) 14%,transparent);display:grid;place-items:center;font-size:28px;overflow:hidden;flex:none}
.mapa-red .foto img{width:100%;height:100%;object-fit:contain;padding:4px}
.mapa-red .quien{min-width:0}
.mapa-red .tipo{font:800 11px system-ui,sans-serif;letter-spacing:.06em;text-transform:uppercase;color:var(--c)}
.mapa-red h3{margin:2px 0;font-size:17px;line-height:1.2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mapa-red .sub{font-size:13px;color:var(--texto-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mapa-red .destacados{margin:10px 0 0;font-size:13px;color:var(--texto-2);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.mapa-red .extra{margin:6px 0 0;font-size:12px;color:var(--texto-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.mapa-red .botones{display:flex;gap:8px;margin-top:12px}
.mapa-red .botones button{flex:1;border:0;border-radius:14px;padding:11px 8px;font:700 14px system-ui,sans-serif;background:color-mix(in srgb,var(--c) 12%,var(--superficie));color:var(--c);transition:transform .12s}
.mapa-red .botones button.primario{background:var(--c);color:#fff}
.mapa-red .botones button:active{transform:scale(.95)}
.pin-red{position:relative;border-radius:50% 50% 50% 4px;transform:rotate(-45deg);background:var(--c);border:3px solid #fff;box-shadow:0 6px 14px rgb(0 0 0/.3);display:grid;place-items:center;transition:all .25s cubic-bezier(.2,1.4,.4,1);animation:cae .5s cubic-bezier(.2,1.4,.4,1) both}
.pin-red span{transform:rotate(45deg);font-size:15px;line-height:1}
.pin-red.activo span{font-size:21px}
.pin-red.activo{box-shadow:0 0 0 8px color-mix(in srgb,var(--c) 30%,transparent),0 8px 18px rgb(0 0 0/.35)}
@keyframes cae{from{opacity:0;transform:rotate(-45deg) translate(0,-30px) scale(.4)}}
.yo-red{width:22px;height:22px;border-radius:50%;background:#1a73e8;border:3px solid #fff;box-shadow:0 2px 8px rgb(0 0 0/.35);position:relative}
.yo-red i{position:absolute;inset:-12px;border-radius:50%;background:rgb(26 115 232/.25);animation:latido 1.8s ease-out infinite}
@keyframes latido{from{transform:scale(.4);opacity:1}to{transform:scale(1.6);opacity:0}}
`
