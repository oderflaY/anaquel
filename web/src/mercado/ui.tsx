// Componentes base del mercado: todos comparten tokens y estados de
// interacción (hover, focus-visible, active, disabled, cargando).
import { useCallback, useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Enlace } from '../router'
import { ErrorMercado, iniciales, pesos, unidad, urlMedia, type Foto, type Publicacion, type Unidad } from './api'
import { Icono, ICONO_CATEGORIA, TONOS } from './iconos'

// ---------- Botones ----------
type Variante = 'primario' | 'secundario' | 'fantasma' | 'peligro'
type PropsBoton = ButtonHTMLAttributes<HTMLButtonElement> & {
  variante?: Variante; chico?: boolean; ancho?: boolean; cargando?: boolean; icono?: string
}
export function Boton({ variante = 'primario', chico, ancho, cargando, icono, children, className = '', disabled, ...resto }: PropsBoton) {
  return (
    <button type="button" {...resto} disabled={disabled || cargando} aria-busy={cargando || undefined}
      className={`m-boton m-boton--${variante} ${chico ? 'm-boton--chico' : ''} ${ancho ? 'm-boton--ancho' : ''} ${className}`}>
      {cargando ? <span className="m-puntos" aria-hidden="true"><i /><i /><i /></span> : icono ? <Icono nombre={icono} /> : null}
      <span>{children}</span>
    </button>
  )
}

export function EnlaceBoton({ href, variante = 'primario', chico, ancho, icono, children }: {
  href: string; variante?: Variante; chico?: boolean; ancho?: boolean; icono?: string; children: ReactNode
}) {
  return (
    <Enlace href={href} className={`m-boton m-boton--${variante} ${chico ? 'm-boton--chico' : ''} ${ancho ? 'm-boton--ancho' : ''}`}>
      {icono && <Icono nombre={icono} />}<span>{children}</span>
    </Enlace>
  )
}

export function BotonIcono({ etiqueta, icono, className = '', ...resto }: ButtonHTMLAttributes<HTMLButtonElement> & { etiqueta: string; icono: string }) {
  return (
    <button type="button" aria-label={etiqueta} title={etiqueta} {...resto} className={`m-boton-icono ${className}`}>
      <Icono nombre={icono} />
    </button>
  )
}

// ---------- Campos ----------
type PropsControl = { id: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }
export function Campo({ etiqueta, ayuda, error, opcional, className = '', children }: {
  etiqueta: string; ayuda?: ReactNode; error?: string | null; opcional?: boolean; className?: string
  children: (p: PropsControl) => ReactNode
}) {
  const id = useId()
  const desc = [ayuda ? `${id}-ayuda` : '', error ? `${id}-error` : ''].filter(Boolean).join(' ') || undefined
  return (
    <div className={`m-campo ${className}`}>
      <label htmlFor={id}>{etiqueta}{opcional && <span className="m-terciario"> (opcional)</span>}</label>
      {children({ id, 'aria-describedby': desc, 'aria-invalid': error ? true : undefined })}
      {ayuda && !error && <span id={`${id}-ayuda`} className="m-campo-ayuda">{ayuda}</span>}
      {error && <span id={`${id}-error`} className="m-campo-error"><Icono nombre="alerta" />{error}</span>}
    </div>
  )
}

// ---------- Modal ----------
export function Modal({ abierto, onCerrar, titulo, children }: { abierto: boolean; onCerrar: () => void; titulo: string; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)
  const idTitulo = useId()
  useEffect(() => {
    const d = ref.current
    if (!d) return
    if (abierto && !d.open) d.showModal()
    if (!abierto && d.open) d.close()
  }, [abierto])
  return (
    <dialog ref={ref} className="m-modal" aria-labelledby={idTitulo}
      onCancel={(e) => { e.preventDefault(); onCerrar() }}
      onClick={(e) => { if (e.target === ref.current) onCerrar() }}>
      {abierto && <>
        <div className="m-modal-cabeza">
          <h2 id={idTitulo} style={{ flex: 1 }}>{titulo}</h2>
          <BotonIcono etiqueta="Cerrar" icono="cerrar" onClick={onCerrar} />
        </div>
        <div className="m-modal-cuerpo">{children}</div>
      </>}
    </dialog>
  )
}

// ---------- Carga con esqueleto ----------
export function Esqueleto({ className = '', style }: { className?: string; style?: React.CSSProperties }) {
  return <span className={`m-esqueleto ${className}`} style={style} aria-hidden="true" />
}

/** Contenedor que anuncia "Cargando…" a lectores de pantalla mientras se ve el esqueleto. */
export function Cargando({ texto = 'Cargando…', children }: { texto?: string; children: ReactNode }) {
  return <div aria-busy="true"><span className="sr-only" role="status">{texto}</span>{children}</div>
}

export function EsqueletoProducto() {
  return (
    <div className="m-producto" aria-hidden="true">
      <Esqueleto className="m-media" style={{ borderRadius: 0 }} />
      <div className="m-producto-cuerpo m-pila-chica">
        <Esqueleto className="m-esqueleto--titulo" style={{ width: '45%' }} />
        <Esqueleto className="m-esqueleto--texto" style={{ width: '90%' }} />
        <Esqueleto className="m-esqueleto--texto" style={{ width: '60%' }} />
      </div>
    </div>
  )
}

export function EsqueletoRejilla({ n = 8 }: { n?: number }) {
  return <Cargando texto="Cargando productos…"><div className="m-rejilla">{Array.from({ length: n }, (_, i) => <EsqueletoProducto key={i} />)}</div></Cargando>
}

export function EsqueletoLista({ n = 4 }: { n?: number }) {
  return (
    <Cargando>
      <div className="m-lista">
        {Array.from({ length: n }, (_, i) => (
          <div className="m-renglon" key={i}>
            <Esqueleto className="m-miniatura" />
            <div className="m-renglon-cuerpo"><Esqueleto className="m-esqueleto--texto" style={{ width: '55%' }} /><Esqueleto className="m-esqueleto--texto" style={{ width: '35%' }} /></div>
            <Esqueleto style={{ width: 72, height: 24, borderRadius: 999 }} />
          </div>
        ))}
      </div>
    </Cargando>
  )
}

// ---------- Estados ----------
export function EstadoVacio({ icono = 'info', titulo, texto, accion }: { icono?: string; titulo: string; texto?: ReactNode; accion?: ReactNode }) {
  return (
    <div className="m-estado">
      <span className="m-estado-icono"><Icono nombre={icono} /></span>
      <h2>{titulo}</h2>
      {texto && <p>{texto}</p>}
      {accion}
    </div>
  )
}

export function EstadoError({ error, reintentar }: { error: string; reintentar?: () => void }) {
  return (
    <div className="m-estado m-estado--error" role="alert">
      <span className="m-estado-icono"><Icono nombre="alerta" /></span>
      <h2>No pudimos cargar esto</h2>
      <p>{error}</p>
      {reintentar && <Boton variante="secundario" icono="adelante" onClick={reintentar}>Reintentar</Boton>}
    </div>
  )
}

export function Alerta({ tipo = 'info', children }: { tipo?: 'info' | 'error' | 'exito' | 'aviso'; children: ReactNode }) {
  const icono = { info: 'info', error: 'alerta', exito: 'check', aviso: 'alerta' }[tipo]
  return <div className={`m-alerta m-alerta--${tipo}`} role={tipo === 'error' ? 'alert' : undefined}><Icono nombre={icono} /><div>{children}</div></div>
}

export function Insignia({ tono = 'neutro', children, sinPunto }: { tono?: string; children: ReactNode; sinPunto?: boolean }) {
  return <span className={`m-insignia ${tono !== 'neutro' ? `m-insignia--${tono}` : ''} ${sinPunto ? 'm-insignia--sin-punto' : ''}`}>{children}</span>
}

// ---------- Avisos (toasts) ----------
type Toast = { id: number; texto: string; tipo: 'exito' | 'error' }
let oyente: ((t: Toast) => void) | null = null
let siguiente = 1
export const avisar = (texto: string, tipo: Toast['tipo'] = 'exito') => oyente?.({ id: siguiente++, texto, tipo })

export function Toasts() {
  const [lista, setLista] = useState<Toast[]>([])
  useEffect(() => {
    oyente = (t) => {
      setLista((l) => [...l.slice(-2), t])
      setTimeout(() => setLista((l) => l.filter((x) => x.id !== t.id)), 4500)
    }
    return () => { oyente = null }
  }, [])
  return (
    <div className="m-toasts" role="status" aria-live="polite">
      {lista.map((t) => (
        <div key={t.id} className={`m-toast m-toast--${t.tipo}`}><Icono nombre={t.tipo === 'exito' ? 'check' : 'alerta'} />{t.texto}</div>
      ))}
    </div>
  )
}

// ---------- Datos ----------
export const mensajeDe = (e: unknown) => (e instanceof ErrorMercado || e instanceof Error ? e.message : 'Algo salió mal. Intenta de nuevo.')

export function useConsulta<T>(cargar: (s: AbortSignal) => Promise<T>, deps: unknown[]) {
  const [datos, setDatos] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [cargando, setCargando] = useState(true)
  const [vuelta, setVuelta] = useState(0)
  useEffect(() => {
    const c = new AbortController()
    setCargando(true); setError(null)
    cargar(c.signal)
      .then((d) => { if (!c.signal.aborted) setDatos(d) })
      .catch((e) => { if (!c.signal.aborted && e?.name !== 'AbortError') setError(mensajeDe(e)) })
      .finally(() => { if (!c.signal.aborted) setCargando(false) })
    return () => c.abort()
  }, [...deps, vuelta]) // eslint-disable-line react-hooks/exhaustive-deps
  const recargar = useCallback(() => setVuelta((v) => v + 1), [])
  return { datos, setDatos, error, cargando, recargar }
}

// ---------- Producto ----------
export function MediaProducto({ foto, categoria, conAlt = true }: { foto: Foto | null; categoria: string; conAlt?: boolean }) {
  const [fallo, setFallo] = useState(false)
  const [lista, setLista] = useState(false)
  if (foto && !fallo) {
    return (
      <>
        {!lista && <Esqueleto className="" style={{ position: 'absolute', inset: 0, borderRadius: 0 }} />}
        <img src={urlMedia(foto.url)} alt={conAlt ? foto.alt : ''} loading="lazy" decoding="async"
          width={foto.ancho} height={foto.alto} onLoad={() => setLista(true)} onError={() => setFallo(true)} />
      </>
    )
  }
  return (
    <div className="m-sin-foto" style={{ ['--tono' as string]: TONOS[categoria] ?? TONOS.otros }}>
      <Icono nombre={ICONO_CATEGORIA[categoria] ?? 'etiqueta'} />
    </div>
  )
}

export function Existencias({ p }: { p: Pick<Publicacion, 'existencias' | 'unidad'> }) {
  if (p.existencias === null) return null
  if (p.existencias === 0) return <Insignia tono="error">Agotado</Insignia>
  if (p.existencias <= 5) return <Insignia tono="aviso">Quedan {p.existencias}</Insignia>
  return null
}

export function TarjetaProducto({ p }: { p: Publicacion }) {
  const lugar = p.distancia_km != null ? `${p.vendedor.colonia} · ${p.distancia_km < 1 ? 'a menos de 1 km' : `a ${p.distancia_km.toLocaleString('es-MX')} km`}` : p.vendedor.colonia
  return (
    <Enlace href={`/mercado/p/${p.id}`} className="m-producto">
      <div className="m-media">
        <MediaProducto foto={p.foto} categoria={p.categoria} />
        <Existencias p={p} />
      </div>
      <div className="m-producto-cuerpo">
        <span className="m-precio">{pesos(p.precio_c)} <small>/ {unidad(p.unidad as Unidad)}</small></span>
        <h3 className="m-titulo-producto">{p.titulo}</h3>
        <span className="m-meta">{p.vendedor.nombre}</span>
        <span className="m-meta">{lugar}</span>
      </div>
    </Enlace>
  )
}

export function Avatar({ nombre, grande }: { nombre: string; grande?: boolean }) {
  return <span className={`m-avatar ${grande ? 'm-avatar--grande' : ''}`} aria-hidden="true">{iniciales(nombre) || '·'}</span>
}

/** Título del documento (se lee al cambiar de página). */
export function useTitulo(t: string) {
  useEffect(() => { document.title = `${t} · Anaquel Mercado` }, [t])
}
