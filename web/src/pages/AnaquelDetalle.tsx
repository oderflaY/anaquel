import { useEffect, useRef, useState, type PointerEvent } from 'react'
import { api, formFoto, hace, media, type Anaquel, type Pedido, type Producto, type ResultadoFoto, type Zona } from '../api'
import type { Sesion } from '../App'
import { Enlace, ir } from '../router'
import { avisar, Estado, mensajeDe, Nivel, useDatos } from '../ui'

type Rect = { x: number; y: number; w: number; h: number }
type ZonaEd = Rect & { id: number; producto_id: number | null; nivel?: Zona['nivel'] }

export default function AnaquelDetalle({ id, sesion }: { id: number; sesion: Sesion }) {
  const { datos: an, error, setDatos } = useDatos(() => api.get<Anaquel>(`/anaqueles/${id}`), [id])
  const { datos: productos } = useDatos(() => api.get<Producto[]>('/productos'))
  const [editando, setEditando] = useState(() => new URLSearchParams(location.search).get('editar') === '1')
  const [resultado, setResultado] = useState<ResultadoFoto | null>(null)
  const [subiendo, setSubiendo] = useState<'hoy' | 'ref' | null>(null)
  const [verHoy, setVerHoy] = useState(true)
  const fotoHoy = useRef<HTMLInputElement>(null)
  const fotoRef = useRef<HTMLInputElement>(null)

  const subir = async (f: File, tipo: 'hoy' | 'ref') => {
    setSubiendo(tipo)
    try {
      if (tipo === 'hoy') {
        const r = await api.post<ResultadoFoto>(`/anaqueles/${id}/fotos`, await formFoto(f))
        setDatos(r.anaquel); setResultado(r); setVerHoy(true)
      } else {
        setDatos(await api.post<Anaquel>(`/anaqueles/${id}/referencia`, await formFoto(f)))
        avisar('Foto de referencia actualizada. Revisa que las zonas sigan en su lugar.')
        setEditando(true)
      }
      sesion.refrescar()
    } catch (e) { avisar(mensajeDe(e)) } finally { setSubiendo(null) }
  }

  if (!an) return <Estado error={error} cargando />

  const renombrar = async () => {
    const n = prompt('Nombre del anaquel', an.nombre)
    if (!n) return
    try { setDatos(await api.put<Anaquel>(`/anaqueles/${id}`, { nombre: n })) } catch (e) { avisar(mensajeDe(e)) }
  }
  const borrar = async () => {
    if (!confirm(`¿Borrar «${an.nombre}» y sus zonas?`)) return
    try { await api.del(`/anaqueles/${id}`); sesion.refrescar(); ir('/panel/anaqueles') } catch (e) { avisar(mensajeDe(e)) }
  }

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div className="renglon">
        <div>
          <Enlace href="/panel/anaqueles" className="chico">← Anaqueles</Enlace>
          <h1 style={{ margin: 0 }}>{an.nombre}</h1>
          <span className="secundario chico">Última foto {hace(an.ultima_en)} · {an.zonas.length} zonas</span>
        </div>
        {!editando && (
          <div className="fila">
            <input ref={fotoHoy} type="file" accept="image/*" capture="environment" hidden
              onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) subir(f, 'hoy') }} />
            <button className="boton" disabled={!!subiendo || an.zonas.length === 0} onClick={() => fotoHoy.current?.click()}>
              {subiendo === 'hoy' ? 'Revisando…' : '📷 Foto de hoy'}
            </button>
            <button className="boton claro" onClick={() => setEditando(true)}>Editar zonas</button>
          </div>
        )}
      </div>

      {editando ? (
        <EditorZonas anaquel={an} productos={productos ?? []}
          alGuardar={(a) => { setDatos(a); setEditando(false); history.replaceState(null, '', `/panel/anaqueles/${id}`); avisar('Zonas guardadas') }}
          alCancelar={() => setEditando(false)} />
      ) : (
        <>
          {an.zonas.length === 0 && <div className="aviso">Este anaquel no tiene zonas. Toca «Editar zonas» y dibuja un recuadro sobre cada producto.</div>}
          {resultado && <Resultado r={resultado} />}
          <div className="tarjeta columna">
            {an.ultima_foto && (
              <div className="fila">
                <button className={`boton chico ${verHoy ? '' : 'claro'}`} onClick={() => setVerHoy(true)}>Última foto</button>
                <button className={`boton chico ${verHoy ? 'claro' : ''}`} onClick={() => setVerHoy(false)}>Referencia (lleno)</button>
              </div>
            )}
            <div className="foto-anaquel">
              <img src={media(verHoy && an.ultima_foto ? an.ultima_foto : an.foto_ref)} alt={`Foto de ${an.nombre}`} />
              {an.zonas.map((z) => (
                <div key={z.id} className={`zona ${z.nivel} ${z.producto_id ? '' : 'sin-producto'}`}
                  style={{ left: `${z.x * 100}%`, top: `${z.y * 100}%`, width: `${z.w * 100}%`, height: `${z.h * 100}%` }}>
                  <span>{z.producto || 'Sin producto'}</span>
                </div>
              ))}
            </div>
            <p className="secundario chico" style={{ margin: 0 }}>
              Verde = lleno · Amarillo = queda poco · Rojo = vacío. Toma la foto desde el mismo lugar que la de referencia.
            </p>
          </div>
          <div className="tarjeta lista">
            {an.zonas.map((z) => (
              <div key={z.id} className="renglon">
                <span>{z.producto || <i className="secundario">Zona sin producto</i>}</span>
                <Nivel nivel={z.nivel} />
              </div>
            ))}
          </div>
          <div className="fila">
            <input ref={fotoRef} type="file" accept="image/*" capture="environment" hidden
              onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) subir(f, 'ref') }} />
            <button className="boton claro chico" onClick={() => fotoRef.current?.click()} disabled={!!subiendo}>Cambiar foto de referencia</button>
            <button className="boton claro chico" onClick={renombrar}>Renombrar</button>
            <button className="boton peligro chico" onClick={borrar}>Borrar anaquel</button>
          </div>
        </>
      )}
    </div>
  )
}

function Resultado({ r }: { r: ResultadoFoto }) {
  return (
    <div className={`aviso ${r.faltantes.length ? '' : 'ok'} columna`} style={{ gap: '.4rem' }}>
      <b>{r.faltantes.length === 0 ? '¡Todo lleno! No falta nada en este anaquel.' : `Se están acabando ${r.faltantes.length} producto(s):`}</b>
      {r.faltantes.length > 0 && <span>{r.faltantes.map((z) => z.producto || 'zona sin producto').join(', ')}</span>}
      {r.llegaron.map((p: Pedido) => (
        <span key={p.id}>✅ El pedido #{p.id} de {p.destino} ya llegó. Lo marqué como recibido.</span>
      ))}
      {r.faltantes.length > 0 && <Enlace href="/panel" className="boton chico" style={{ alignSelf: 'flex-start' }}>Ver a quién pedirlo</Enlace>}
      <span className="chico secundario">Revisado por {r.metodo === 'ia' ? 'inteligencia artificial' : 'comparación con la foto de referencia'}.</span>
    </div>
  )
}

// ---------------- Editor de zonas ----------------

function EditorZonas({ anaquel, productos, alGuardar, alCancelar }: {
  anaquel: Anaquel; productos: Producto[]; alGuardar: (a: Anaquel) => void; alCancelar: () => void
}) {
  const [zonas, setZonas] = useState<ZonaEd[]>(() => anaquel.zonas.map((z) => ({ ...z })))
  const [sel, setSel] = useState<number | null>(null)
  const [nuevo, setNuevo] = useState<Rect | null>(null)
  const [guardando, setGuardando] = useState(false)
  const caja = useRef<HTMLDivElement>(null)
  const arrastre = useRef<{ modo: 'nuevo' | 'mover' | 'tamano'; i: number; x0: number; y0: number; orig: Rect } | null>(null)
  const sigId = useRef(-1)

  useEffect(() => {
    const borrar = (e: KeyboardEvent) => {
      if ((e.key === 'Delete' || e.key === 'Backspace') && sel !== null && !(e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement)) {
        setZonas((z) => z.filter((_, i) => i !== sel)); setSel(null)
      }
    }
    window.addEventListener('keydown', borrar)
    return () => window.removeEventListener('keydown', borrar)
  }, [sel])

  const punto = (e: PointerEvent) => {
    const r = caja.current!.getBoundingClientRect()
    return { x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)) }
  }
  const abajo = (e: PointerEvent, modo: 'nuevo' | 'mover' | 'tamano', i = -1) => {
    e.stopPropagation()
    caja.current!.setPointerCapture(e.pointerId)
    const p = punto(e)
    const orig = i >= 0 ? { ...zonas[i] } : { x: p.x, y: p.y, w: 0, h: 0 }
    arrastre.current = { modo, i, x0: p.x, y0: p.y, orig }
    if (i >= 0) setSel(i)
    if (modo === 'nuevo') { setSel(null); setNuevo(orig) }
  }
  const mover = (e: PointerEvent) => {
    const a = arrastre.current
    if (!a) return
    const p = punto(e)
    const dx = p.x - a.x0, dy = p.y - a.y0
    if (a.modo === 'nuevo') {
      setNuevo({ x: Math.min(a.x0, p.x), y: Math.min(a.y0, p.y), w: Math.abs(dx), h: Math.abs(dy) })
    } else {
      setZonas((zs) => zs.map((z, i) => {
        if (i !== a.i) return z
        if (a.modo === 'mover') return { ...z, x: Math.min(1 - z.w, Math.max(0, a.orig.x + dx)), y: Math.min(1 - z.h, Math.max(0, a.orig.y + dy)) }
        return { ...z, w: Math.max(0.02, Math.min(1 - z.x, a.orig.w + dx)), h: Math.max(0.02, Math.min(1 - z.y, a.orig.h + dy)) }
      }))
    }
  }
  const arriba = () => {
    const a = arrastre.current
    arrastre.current = null
    if (a?.modo === 'nuevo' && nuevo) {
      setNuevo(null)
      if (nuevo.w > 0.02 && nuevo.h > 0.02) {
        setZonas((z) => [...z, { ...nuevo, id: sigId.current--, producto_id: null }])
        setSel(zonas.length)
      }
    }
  }

  const guardar = async () => {
    setGuardando(true)
    try {
      alGuardar(await api.put<Anaquel>(`/anaqueles/${anaquel.id}/zonas`, zonas.map((z) => ({ ...z, id: z.id > 0 ? z.id : 0 }))))
    } catch (e) { avisar(mensajeDe(e)) } finally { setGuardando(false) }
  }
  const nombreDe = (pid: number | null) => productos.find((p) => p.id === pid)?.nombre
  const z = sel !== null ? zonas[sel] : null

  return (
    <div className="columna">
      <div className="aviso chico">
        <b>Arrastra sobre la foto</b> para dibujar un recuadro sobre cada producto y elige cuál es.
        Toca un recuadro para seleccionarlo, arrástralo para moverlo y usa la bolita blanca para cambiar su tamaño.
      </div>
      <div className="tarjeta columna">
        <div ref={caja} className="foto-anaquel" style={{ cursor: 'crosshair' }}
          onPointerDown={(e) => abajo(e, 'nuevo')} onPointerMove={mover} onPointerUp={arriba} onPointerCancel={arriba}>
          <img src={media(anaquel.foto_ref)} alt="Foto de referencia" />
          {zonas.map((zz, i) => (
            <div key={zz.id} className={`zona ${zz.producto_id ? '' : 'sin-producto'} ${sel === i ? 'sel' : ''}`}
              style={{ left: `${zz.x * 100}%`, top: `${zz.y * 100}%`, width: `${zz.w * 100}%`, height: `${zz.h * 100}%`, cursor: 'move' }}
              onPointerDown={(e) => abajo(e, 'mover', i)}>
              <span>{nombreDe(zz.producto_id) ?? 'Elige producto'}</span>
              {sel === i && <div className="asa" onPointerDown={(e) => abajo(e, 'tamano', i)} />}
            </div>
          ))}
          {nuevo && <div className="dibujando" style={{ left: `${nuevo.x * 100}%`, top: `${nuevo.y * 100}%`, width: `${nuevo.w * 100}%`, height: `${nuevo.h * 100}%` }} />}
        </div>
        {z && sel !== null && (
          <div className="fila">
            <select style={{ flex: 1, minWidth: 200 }} value={z.producto_id ?? ''} aria-label="Producto de la zona"
              onChange={(e) => setZonas(zonas.map((x, i) => (i === sel ? { ...x, producto_id: e.target.value ? Number(e.target.value) : null } : x)))}>
              <option value="">— ¿Qué producto va aquí? —</option>
              {productos.map((p) => <option key={p.id} value={p.id}>{p.nombre}</option>)}
            </select>
            <button className="boton peligro chico" onClick={() => { setZonas(zonas.filter((_, i) => i !== sel)); setSel(null) }}>Quitar zona</button>
          </div>
        )}
        {productos.length === 0 && (
          <div className="aviso chico">Aún no tienes productos. Agrégalos en <Enlace href="/panel/productos">Productos</Enlace> o sincroniza tu inventario desde la app.</div>
        )}
      </div>
      <div className="fila">
        <button className="boton" onClick={guardar} disabled={guardando}>{guardando ? 'Guardando…' : `Guardar ${zonas.length} zonas`}</button>
        <button className="boton claro" onClick={alCancelar}>Cancelar</button>
        <span className="secundario chico">{zonas.filter((x) => !x.producto_id).length} sin producto</span>
      </div>
    </div>
  )
}
