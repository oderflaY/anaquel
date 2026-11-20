import { useEffect, useRef, useState } from 'react'
import { Enlace } from '../../router'
import { api, pesos, unidad, urlMedia, type Pagina, type Reel } from '../api'
import { Icono } from '../iconos'
import { BotonIcono, EnlaceBoton, Esqueleto, EstadoError, EstadoVacio, mensajeDe, useTitulo } from '../ui'

const sinMovimiento = () => matchMedia('(prefers-reduced-motion: reduce)').matches

export default function Reels() {
  useTitulo('Reels')
  const [items, setItems] = useState<Reel[] | null>(null)
  const [siguiente, setSiguiente] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [sonido, setSonido] = useState(false)
  const cargando = useRef(false)

  const cargar = async (antes?: number) => {
    if (cargando.current) return
    cargando.current = true
    try {
      const r = await api.get<Pagina<Reel>>(`/reels/${antes ? `?antes=${antes}` : ''}`)
      setItems((x) => (antes && x ? [...x, ...r.items] : r.items)); setSiguiente(r.siguiente); setError(null)
    } catch (e) { setError(mensajeDe(e)) } finally { cargando.current = false }
  }
  useEffect(() => { cargar() }, [])

  if (error && !items) return <div className="m-contenedor m-principal"><EstadoError error={error} reintentar={() => cargar()} /></div>
  if (!items) {
    return (
      <div className="m-reels" aria-busy="true">
        <span className="sr-only" role="status">Cargando videos…</span>
        <div className="m-reel"><Esqueleto style={{ position: 'absolute', inset: 0, borderRadius: 0 }} />
          <div className="m-reel-info"><Esqueleto className="m-esqueleto--texto" style={{ width: '40%' }} /><Esqueleto className="m-esqueleto--texto" style={{ width: '80%' }} /><Esqueleto style={{ height: 52, borderRadius: 12 }} /></div>
        </div>
      </div>
    )
  }
  if (!items.length) {
    return <div className="m-contenedor m-principal"><EstadoVacio icono="video" titulo="Todavía no hay videos" texto="Cuando los negocios suban videos de sus productos aparecerán aquí." accion={<EnlaceBoton href="/mercado" variante="secundario">Explorar productos</EnlaceBoton>} /></div>
  }
  return (
    <section className="m-reels" aria-label="Videos de los negocios" tabIndex={0}
      onKeyDown={(e) => {
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
        e.preventDefault()
        e.currentTarget.scrollBy({ top: (e.key === 'ArrowDown' ? 1 : -1) * e.currentTarget.clientHeight, behavior: sinMovimiento() ? 'auto' : 'smooth' })
      }}>
      <h1 className="sr-only">Videos de los negocios de Durango</h1>
      {items.map((r, i) => (
        <ReelItem key={r.id} r={r} n={i + 1} total={items.length} sonido={sonido} setSonido={setSonido}
          alVer={() => { if (i >= items.length - 2 && siguiente) cargar(siguiente) }} />
      ))}
    </section>
  )
}

function ReelItem({ r, n, total, sonido, setSonido, alVer }: {
  r: Reel; n: number; total: number; sonido: boolean; setSonido: (b: boolean) => void; alVer: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const video = useRef<HTMLVideoElement>(null)
  const [pausado, setPausado] = useState(true)
  const [listo, setListo] = useState(false)
  const [fallo, setFallo] = useState(false)

  // Se reproduce solo el video visible (y nunca solo si el usuario pidió menos movimiento).
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const o = new IntersectionObserver(([e]) => {
      const v = video.current
      if (!v) return
      if (e.isIntersecting && e.intersectionRatio > 0.6) {
        alVer()
        if (!sinMovimiento()) v.play().then(() => setPausado(false)).catch(() => setPausado(true))
      } else { v.pause(); setPausado(true) }
    }, { threshold: [0, 0.6, 1] })
    o.observe(el)
    return () => o.disconnect()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const alternar = () => {
    const v = video.current
    if (!v) return
    if (v.paused) v.play().then(() => setPausado(false)).catch(() => {})
    else { v.pause(); setPausado(true) }
  }

  return (
    <article ref={ref} className="m-reel" aria-roledescription="video" aria-label={`Video ${n} de ${total}: ${r.vendedor.nombre}`}>
      {!listo && !fallo && <Esqueleto style={{ position: 'absolute', inset: 0, borderRadius: 0 }} />}
      {fallo ? <p style={{ color: '#fff' }}>No se pudo cargar este video.</p> : (
        <video ref={video} src={urlMedia(r.url)} muted={!sonido} loop playsInline preload="metadata"
          aria-label={r.descripcion} onLoadedData={() => setListo(true)} onError={() => setFallo(true)} onClick={alternar} />
      )}
      {pausado && listo && (
        <button type="button" className="m-reel-play" onClick={alternar} aria-label="Reproducir video"><span><Icono nombre="play" /></span></button>
      )}
      <div className="m-reel-controles">
        <BotonIcono etiqueta={pausado ? 'Reproducir' : 'Pausar'} icono={pausado ? 'play' : 'pausa'} onClick={alternar} />
        <BotonIcono etiqueta={sonido ? 'Silenciar' : 'Activar sonido'} icono={sonido ? 'volumen' : 'silencio'} aria-pressed={sonido} onClick={() => setSonido(!sonido)} />
      </div>
      <div className="m-reel-info">
        <Enlace href={`/mercado/v/${r.vendedor.slug}`}><b>{r.vendedor.nombre}</b></Enlace>
        <p>{r.descripcion}</p>
        {r.publicacion && (
          <div className="m-reel-producto">
            <span className="m-renglon-cuerpo"><b>{r.publicacion.titulo}</b><span>{pesos(r.publicacion.precio_c)} / {unidad(r.publicacion.unidad)}</span></span>
            <EnlaceBoton href={`/mercado/p/${r.publicacion.id}`} chico>Ver producto</EnlaceBoton>
          </div>
        )}
      </div>
    </article>
  )
}
