import { useState, type FormEvent } from 'react'
import type { SesionMercado } from '../Mercado'
import { Enlace, ir } from '../../router'
import { api, fecha, pesos, unidad, type Proveedor, type Publicacion, type Reel, type Registro, type Terminal } from '../api'
import { Icono } from '../iconos'
import { Alerta, Boton, BotonIcono, Campo, Cargando, EnlaceBoton, Esqueleto, EsqueletoLista, EstadoError, EstadoVacio, Insignia, MediaProducto, mensajeDe, Modal, useConsulta, useTitulo, avisar } from '../ui'

const PESTANAS: [string, string][] = [['publicaciones', 'Publicaciones'], ['reels', 'Reels'], ['terminales', 'Terminales'], ['ingresos', 'Cobros recibidos']]

export default function Vender({ pestana }: { sesion: SesionMercado; pestana: string }) {
  useTitulo('Vender')
  const actual = PESTANAS.some(([k]) => k === pestana) ? pestana : 'publicaciones'
  return (
    <div className="m-pila">
      <div className="m-fila m-entre">
        <h1>Vender</h1>
        {actual === 'publicaciones' && <EnlaceBoton href="/mercado/vender/nueva" icono="mas">Publicar producto</EnlaceBoton>}
      </div>
      <div className="m-tabs" role="tablist" aria-label="Secciones de venta">
        {PESTANAS.map(([k, t]) => (
          <button key={k} type="button" role="tab" id={`tab-${k}`} className="m-tab" aria-selected={actual === k} aria-controls="panel-vender"
            onClick={() => ir(`/mercado/vender?tab=${k}`, true)}>{t}</button>
        ))}
      </div>
      <div id="panel-vender" role="tabpanel" aria-labelledby={`tab-${actual}`} className="m-pila">
        {actual === 'publicaciones' ? <MisPublicaciones /> : actual === 'reels' ? <MisReels /> : actual === 'terminales' ? <MisTerminales /> : <Ingresos />}
      </div>
    </div>
  )
}

function MisPublicaciones() {
  const { datos, setDatos, error, cargando, recargar } = useConsulta((s) => api.get<Publicacion[]>('/mercado/mias', s), [])
  const [borrar, setBorrar] = useState<Publicacion | null>(null)
  const [ocupado, setOcupado] = useState<number | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const alternar = async (p: Publicacion) => {
    const estado = p.estado === 'activa' ? 'pausada' : 'activa'
    setOcupado(p.id)
    try {
      await api.post(`/mercado/publicaciones/${p.id}/estado`, { estado })
      setDatos((d) => d?.map((x) => (x.id === p.id ? { ...x, estado } : x)) ?? null)
      avisar(estado === 'pausada' ? 'Publicación pausada: ya no aparece en el mercado' : 'Publicación activa')
    } catch (e) { avisar(mensajeDe(e), 'error') } finally { setOcupado(null) }
  }
  const confirmarBorrar = async () => {
    if (!borrar) return
    setOcupado(borrar.id); setErr(null)
    try {
      await api.del(`/mercado/publicaciones/${borrar.id}`)
      setDatos((d) => d?.filter((x) => x.id !== borrar.id) ?? null); setBorrar(null); avisar('Publicación eliminada')
    } catch (e) { setErr(mensajeDe(e)) } finally { setOcupado(null) }
  }
  if (cargando) return <EsqueletoLista />
  if (error) return <EstadoError error={error} reintentar={recargar} />
  if (!datos?.length) return <EstadoVacio icono="etiqueta" titulo="Aún no publicas productos" texto="Publica lo que vendes y los negocios de Durango podrán apartarlo." accion={<EnlaceBoton href="/mercado/vender/nueva" icono="mas">Publicar mi primer producto</EnlaceBoton>} />
  return (
    <>
      <ul className="m-lista" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {datos.map((p) => (
          <li key={p.id} className="m-renglon">
            <div className="m-miniatura m-media"><MediaProducto foto={p.foto} categoria={p.categoria} conAlt={false} /></div>
            <div className="m-renglon-cuerpo">
              <Enlace href={`/mercado/p/${p.id}`}><b>{p.titulo}</b></Enlace>
              <span className="m-terciario">{pesos(p.precio_c)} / {unidad(p.unidad)}{p.existencias !== null && ` · ${p.existencias} disponibles`}</span>
              <span>{p.estado === 'activa' ? <Insignia tono="exito">Visible</Insignia> : <Insignia tono="aviso">Pausada</Insignia>}</span>
            </div>
            <div className="m-fila" style={{ gap: 0 }}>
              <BotonIcono etiqueta={p.estado === 'activa' ? `Pausar ${p.titulo}` : `Activar ${p.titulo}`} icono={p.estado === 'activa' ? 'pausa' : 'play'} disabled={ocupado === p.id} onClick={() => alternar(p)} />
              <BotonIcono etiqueta={`Editar ${p.titulo}`} icono="editar" onClick={() => ir(`/mercado/vender/editar/${p.id}`)} />
              <BotonIcono etiqueta={`Eliminar ${p.titulo}`} icono="basura" onClick={() => { setErr(null); setBorrar(p) }} />
            </div>
          </li>
        ))}
      </ul>
      <Modal abierto={!!borrar} onCerrar={() => setBorrar(null)} titulo="¿Eliminar publicación?">
        <p className="m-secundario">«{borrar?.titulo}» desaparecerá del mercado con sus fotos. Las órdenes que ya existen se conservan. Si solo quieres ocultarla un tiempo, mejor páusala.</p>
        {err && <Alerta tipo="error">{err}</Alerta>}
        <div className="m-fila" style={{ justifyContent: 'flex-end' }}>
          <Boton variante="secundario" onClick={() => setBorrar(null)}>Cancelar</Boton>
          <Boton variante="peligro" cargando={ocupado === borrar?.id} onClick={confirmarBorrar}>Eliminar</Boton>
        </div>
      </Modal>
    </>
  )
}

function MisReels() {
  const reels = useConsulta((s) => api.get<Reel[]>('/reels/mios', s), [])
  const pubs = useConsulta((s) => api.get<Publicacion[]>('/mercado/mias', s), [])
  const [archivo, setArchivo] = useState<File | null>(null)
  const [desc, setDesc] = useState('')
  const [pub, setPub] = useState('')
  const [errores, setErrores] = useState<{ archivo?: string; desc?: string; general?: string }>({})
  const [subiendo, setSubiendo] = useState(false)
  const [quitar, setQuitar] = useState<number | null>(null)

  const subir = async (e: FormEvent) => {
    e.preventDefault()
    const err: typeof errores = {}
    if (!archivo) err.archivo = 'Elige un video MP4'
    else if (archivo.size > 50 * 1024 * 1024) err.archivo = 'El video pesa más de 50 MB'
    if (desc.trim().length < 10) err.desc = 'Describe el video en al menos 10 letras'
    setErrores(err)
    if (Object.keys(err).length) return
    const fd = new FormData()
    fd.append('descripcion', desc.trim())
    if (pub) fd.append('publicacion_id', pub)
    fd.append('video', archivo!)
    setSubiendo(true)
    try {
      await api.post('/reels/subir', fd)
      avisar('Video publicado'); setArchivo(null); setDesc(''); setPub('')
      ;(e.target as HTMLFormElement).reset()
      reels.recargar()
    } catch (ex) { setErrores({ general: mensajeDe(ex) }) } finally { setSubiendo(false) }
  }
  const borrar = async (id: number) => {
    setQuitar(id)
    try { await api.del(`/reels/${id}`); reels.setDatos((d) => d?.filter((r) => r.id !== id) ?? null); avisar('Video eliminado') }
    catch (e) { avisar(mensajeDe(e), 'error') } finally { setQuitar(null) }
  }

  return (
    <div className="m-pila">
      <form className="m-card m-pila" onSubmit={subir} noValidate aria-labelledby="t-subir">
        <h2 id="t-subir">Subir un video</h2>
        <Campo etiqueta="Video" ayuda="MP4 vertical, de 1 a 60 segundos, máximo 50 MB." error={errores.archivo}>
          {(p) => <input {...p} type="file" accept="video/mp4" onChange={(e) => setArchivo(e.target.files?.[0] ?? null)} />}
        </Campo>
        <Campo etiqueta="¿Qué se ve en el video?" ayuda="Lo leen quienes usan lector de pantalla y aparece sobre el video." error={errores.desc}>
          {(p) => <textarea {...p} maxLength={300} value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="Ej. Así bordamos tu logo en gorras, desde 50 piezas." />}
        </Campo>
        <Campo etiqueta="Producto que se muestra" opcional>
          {(p) => (
            <select {...p} value={pub} onChange={(e) => setPub(e.target.value)} disabled={pubs.cargando}>
              <option value="">Ninguno</option>
              {pubs.datos?.map((x) => <option key={x.id} value={x.id}>{x.titulo}</option>)}
            </select>
          )}
        </Campo>
        {errores.general && <Alerta tipo="error">{errores.general}</Alerta>}
        <Boton type="submit" icono="video" cargando={subiendo}>{subiendo ? 'Subiendo…' : 'Publicar video'}</Boton>
      </form>
      <h2>Mis videos</h2>
      {reels.cargando ? <EsqueletoLista n={2} />
        : reels.error ? <EstadoError error={reels.error} reintentar={reels.recargar} />
        : !reels.datos?.length ? <EstadoVacio icono="video" titulo="Aún no subes videos" texto="Un video corto de tu producto ayuda a que lo descubran." />
        : <ul className="m-lista" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {reels.datos.map((r) => (
            <li key={r.id} className="m-renglon">
              <span className="m-miniatura" style={{ display: 'grid', placeItems: 'center', color: 'var(--color-texto-2)' }}><Icono nombre="video" /></span>
              <div className="m-renglon-cuerpo"><b>{r.descripcion}</b><span className="m-terciario">{Math.round(r.duracion_ms / 1000)} s · {r.publicacion?.titulo ?? 'Sin producto'} · {fecha(r.creado)}</span></div>
              <BotonIcono etiqueta="Eliminar video" icono="basura" disabled={quitar === r.id} onClick={() => borrar(r.id)} />
            </li>
          ))}
        </ul>}
    </div>
  )
}

function MisTerminales() {
  const terms = useConsulta((s) => api.get<Terminal[]>('/pagos/terminales', s), [])
  const provs = useConsulta((s) => api.get<Proveedor[]>('/pagos/proveedores', s), [])
  const [f, setF] = useState({ proveedor: '', nombre: '', dispositivo: '' })
  const [err, setErr] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const proveedor = f.proveedor || provs.datos?.[0]?.clave || ''

  const vincular = async (e: FormEvent) => {
    e.preventDefault(); setErr(null); setOcupado(true)
    try {
      const t = await api.post<Terminal>('/pagos/terminales', { ...f, proveedor })
      terms.setDatos((d) => [...(d ?? []), t]); setF({ proveedor, nombre: '', dispositivo: '' }); avisar('Terminal vinculada')
    } catch (ex) { setErr(mensajeDe(ex)) } finally { setOcupado(false) }
  }
  const quitar = async (t: Terminal) => {
    try { await api.del(`/pagos/terminales/${t.id}`); terms.setDatos((d) => d?.filter((x) => x.id !== t.id) ?? null); avisar('Terminal desvinculada') }
    catch (e) { avisar(mensajeDe(e), 'error') }
  }
  return (
    <div className="m-pila">
      <Alerta>Cuando cobras en tu terminal, la marca le avisa a Anaquel y el pago se registra solo, aunque no lo hayas iniciado desde la app.</Alerta>
      {terms.cargando ? <EsqueletoLista n={2} />
        : terms.error ? <EstadoError error={terms.error} reintentar={terms.recargar} />
        : !terms.datos?.length ? <EstadoVacio icono="terminal" titulo="Sin terminales vinculadas" texto="Vincula tu terminal para cobrar las órdenes con tarjeta." />
        : <ul className="m-lista" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {terms.datos.map((t) => (
            <li key={t.id} className="m-renglon">
              <span className="m-miniatura" style={{ display: 'grid', placeItems: 'center', color: 'var(--color-marca)' }}><Icono nombre="terminal" /></span>
              <div className="m-renglon-cuerpo"><b>{t.nombre}</b><span className="m-terciario">{t.marca} · {t.dispositivo}</span></div>
              {t.proveedor === 'simulador' && <EnlaceBoton href="/mercado/terminal" variante="fantasma" chico>Abrir</EnlaceBoton>}
              <BotonIcono etiqueta={`Desvincular ${t.nombre}`} icono="basura" onClick={() => quitar(t)} />
            </li>
          ))}
        </ul>}
      <form className="m-card m-pila" onSubmit={vincular} aria-labelledby="t-vincular">
        <h2 id="t-vincular">Vincular terminal</h2>
        {provs.cargando ? <Cargando><Esqueleto style={{ height: 44, borderRadius: 12 }} /></Cargando> : (
          <div className="m-rejilla-campos">
            <Campo etiqueta="Marca">
              {(p) => (
                <select {...p} value={proveedor} onChange={(e) => setF({ ...f, proveedor: e.target.value })}>
                  {provs.datos?.map((x) => <option key={x.clave} value={x.clave}>{x.nombre}</option>)}
                </select>
              )}
            </Campo>
            <Campo etiqueta="Nombre" ayuda="Para reconocerla, ej. «Mostrador».">
              {(p) => <input {...p} required maxLength={40} value={f.nombre} onChange={(e) => setF({ ...f, nombre: e.target.value })} />}
            </Campo>
            {proveedor !== 'simulador' && (
              <Campo etiqueta={proveedor === 'clip' ? 'Número de serie' : 'ID de la terminal'} className="m-completo"
                ayuda={proveedor === 'mercadopago' ? 'Lo ves en tu cuenta de Mercado Pago. La terminal debe estar en modo PDV.' : 'Viene en la etiqueta de la terminal.'}>
                {(p) => <input {...p} required maxLength={64} value={f.dispositivo} onChange={(e) => setF({ ...f, dispositivo: e.target.value })} autoCapitalize="characters" spellCheck={false} />}
              </Campo>
            )}
          </div>
        )}
        {err && <Alerta tipo="error">{err}</Alerta>}
        <Boton type="submit" variante="secundario" icono="mas" cargando={ocupado} disabled={!proveedor}>Vincular</Boton>
      </form>
    </div>
  )
}

function Ingresos() {
  const { datos, error, cargando, recargar } = useConsulta((s) => api.get<{ items: Registro[]; hoy_c: number; hoy_cobros: number }>('/pagos/registros', s), [])
  if (cargando) return <Cargando><div className="m-metricas">{[0, 1].map((i) => <Esqueleto key={i} style={{ height: 84, borderRadius: 16 }} />)}</div></Cargando>
  if (error || !datos) return <EstadoError error={error ?? 'Sin datos'} reintentar={recargar} />
  return (
    <div className="m-pila">
      <div className="m-metricas">
        <div className="m-metrica"><span className="m-terciario">Cobrado hoy</span><b>{pesos(datos.hoy_c)}</b></div>
        <div className="m-metrica"><span className="m-terciario">Cobros de hoy</span><b>{datos.hoy_cobros}</b></div>
      </div>
      <div className="m-fila m-entre"><h2>Registro de cobros</h2><Boton variante="fantasma" chico onClick={recargar}>Actualizar</Boton></div>
      {!datos.items.length ? <EstadoVacio icono="tarjeta" titulo="Aún no hay cobros" texto="Los pagos que notifique tu terminal aparecerán aquí automáticamente." />
        : <ul className="m-lista" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {datos.items.map((r) => (
            <li key={r.id} className="m-renglon">
              <span className="m-miniatura" style={{ display: 'grid', placeItems: 'center', color: 'var(--color-exito)', background: 'var(--color-exito-suave)' }}><Icono nombre="tarjeta" /></span>
              <div className="m-renglon-cuerpo">
                <b>{pesos(r.monto_c)}</b>
                <span className="m-terciario">{r.terminal || r.marca} · {fecha(r.pagado_en)}</span>
              </div>
              <div className="m-pila-chica" style={{ alignItems: 'flex-end' }}>
                {r.origen === 'app' ? <Insignia tono="info">Desde la app</Insignia> : <Insignia tono="acento">Directo en terminal</Insignia>}
                {r.orden_id ? <Enlace href={`/mercado/ordenes/${r.orden_id}`} className="m-chico">Orden #{r.orden_id}</Enlace> : <span className="m-terciario">Sin orden</span>}
              </div>
            </li>
          ))}
        </ul>}
    </div>
  )
}
