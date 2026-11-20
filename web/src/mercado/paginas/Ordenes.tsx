import { useEffect, useRef, useState } from 'react'
import type { SesionMercado } from '../Mercado'
import { Enlace, ir } from '../../router'
import { api, cantidadTxt, ESTADOS, fecha, pesos, type Cobro, type Orden, type Registro, type Terminal } from '../api'
import { Icono } from '../iconos'
import { Alerta, Avatar, Boton, Cargando, EnlaceBoton, Esqueleto, EsqueletoLista, EstadoError, EstadoVacio, Insignia, MediaProducto, mensajeDe, Modal, useConsulta, useTitulo, avisar } from '../ui'

export function InsigniaEstado({ estado }: { estado: Orden['estado'] }) {
  const e = ESTADOS[estado]
  return <Insignia tono={e.tono}>{e.txt}</Insignia>
}

export default function Ordenes({ rol, sesion }: { rol: 'compras' | 'ventas'; sesion: SesionMercado }) {
  useTitulo(rol === 'ventas' ? 'Mis ventas' : 'Mis compras')
  const { datos, error, cargando, recargar } = useConsulta((s) => api.get<Orden[]>(`/mercado/ordenes?rol=${rol}`, s), [rol])
  const pestanas: ['compras' | 'ventas', string][] = [['compras', 'Compras']]
  if (sesion.cuenta.vende) pestanas.push(['ventas', 'Ventas'])
  return (
    <div className="m-pila">
      <h1>Mis órdenes</h1>
      {pestanas.length > 1 && (
        <div className="m-tabs" role="tablist" aria-label="Tipo de orden">
          {pestanas.map(([k, t]) => (
            <button key={k} type="button" role="tab" className="m-tab" aria-selected={rol === k} aria-controls="lista-ordenes"
              onClick={() => ir(`/mercado/ordenes${k === 'ventas' ? '?rol=ventas' : ''}`, true)}>{t}</button>
          ))}
        </div>
      )}
      <div id="lista-ordenes" role="tabpanel">
        {cargando ? <EsqueletoLista />
          : error ? <EstadoError error={error} reintentar={recargar} />
          : !datos?.length ? (
            <EstadoVacio icono="recibo" titulo={rol === 'ventas' ? 'Aún no tienes ventas' : 'Aún no has apartado nada'}
              texto={rol === 'ventas' ? 'Cuando alguien aparte uno de tus productos aparecerá aquí.' : 'Explora lo que venden los negocios de Durango.'}
              accion={rol === 'ventas' ? <EnlaceBoton href="/mercado/vender" variante="secundario">Ir a mis publicaciones</EnlaceBoton> : <EnlaceBoton href="/mercado">Explorar</EnlaceBoton>} />
          ) : (
            <ul className="m-lista" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {datos.map((o) => (
                <li key={o.id}>
                  <Enlace href={`/mercado/ordenes/${o.id}`} className="m-renglon">
                    <div className="m-miniatura m-media"><MediaProducto foto={o.foto} categoria="otros" conAlt={false} /></div>
                    <div className="m-renglon-cuerpo">
                      <b>{o.titulo}</b>
                      <span className="m-terciario">{cantidadTxt(o.cantidad, o.unidad)} · {o.rol === 'compra' ? 'a' : 'para'} {o.contraparte}</span>
                      <span className="m-terciario">{fecha(o.creada)}</span>
                    </div>
                    <div className="m-pila-chica" style={{ alignItems: 'flex-end' }}>
                      <b>{pesos(o.total_c)}</b>
                      <InsigniaEstado estado={o.estado} />
                    </div>
                  </Enlace>
                </li>
              ))}
            </ul>
          )}
      </div>
    </div>
  )
}

const PASOS: [Orden['estado'], string, string][] = [
  ['pendiente', 'Apartado', 'El vendedor ya lo tiene reservado para ti.'],
  ['por_cobrar', 'Cobro en la terminal', 'Se envió el monto a la terminal del vendedor.'],
  ['pagada', 'Pagado', 'El pago se registró automáticamente.'],
  ['entregada', 'Entregado', 'Pedido completado.'],
]

export function OrdenDetalle({ id }: { id: number; sesion: SesionMercado }) {
  const { datos: o, setDatos, error, cargando, recargar } = useConsulta((s) => api.get<Orden>(`/mercado/ordenes/${id}`, s), [id])
  useTitulo(o ? `Orden #${o.id}` : 'Orden')
  const [cobrar, setCobrar] = useState(false)
  const [vincular, setVincular] = useState(false)
  const [confirmarCancelar, setConfirmarCancelar] = useState(false)
  const [ocupado, setOcupado] = useState<string | null>(null)
  const [errorAccion, setErrorAccion] = useState<string | null>(null)

  // Mientras está en la terminal, la orden se actualiza sola.
  useEffect(() => {
    if (o?.estado !== 'por_cobrar') return
    const t = setInterval(recargar, 3000)
    return () => clearInterval(t)
  }, [o?.estado, recargar])

  if (cargando && !o) {
    return <Cargando texto="Cargando orden…"><div className="m-pila"><Esqueleto className="m-esqueleto--titulo" style={{ width: 200 }} /><Esqueleto style={{ height: 140, borderRadius: 16 }} /><Esqueleto style={{ height: 200, borderRadius: 16 }} /></div></Cargando>
  }
  if (error && !o) return <EstadoError error={error} reintentar={recargar} />
  if (!o) return null

  const accion = async (a: 'cancelar' | 'entregar') => {
    setOcupado(a); setErrorAccion(null)
    try {
      setDatos(await api.post<Orden>(`/mercado/ordenes/${o.id}/${a}`))
      avisar(a === 'cancelar' ? 'Orden cancelada' : 'Marcada como entregada')
      setConfirmarCancelar(false)
    } catch (e) { setErrorAccion(mensajeDe(e)) } finally { setOcupado(null) }
  }
  const vende = o.rol === 'venta'
  const cancelada = o.estado === 'cancelada'
  const indice = PASOS.findIndex(([e]) => e === o.estado)

  return (
    <div className="m-pila" style={{ maxWidth: 720, margin: '0 auto', gap: 'var(--e-6)' }}>
      <nav className="m-migas" aria-label="Ruta">
        <Enlace href={`/mercado/ordenes${vende ? '?rol=ventas' : ''}`}>{vende ? 'Mis ventas' : 'Mis compras'}</Enlace>
        <Icono nombre="adelante" /><span aria-current="page">Orden #{o.id}</span>
      </nav>
      <div className="m-fila m-entre">
        <h1>Orden #{o.id}</h1>
        <InsigniaEstado estado={o.estado} />
      </div>

      <section className="m-card m-pila" aria-label="Resumen">
        <div className="m-renglon" style={{ padding: 0, minHeight: 0 }}>
          <div className="m-miniatura m-media"><MediaProducto foto={o.foto} categoria="otros" conAlt={false} /></div>
          <div className="m-renglon-cuerpo">
            {o.publicacion_id ? <Enlace href={`/mercado/p/${o.publicacion_id}`}><b>{o.titulo}</b></Enlace> : <b>{o.titulo}</b>}
            <span className="m-terciario">{cantidadTxt(o.cantidad, o.unidad)} × {pesos(o.precio_c)}</span>
          </div>
          <b style={{ fontSize: 'var(--texto-20)' }}>{pesos(o.total_c)}</b>
        </div>
        <hr className="m-separador" />
        <div className="m-vendedor">
          <Avatar nombre={o.contraparte} />
          <div className="m-renglon-cuerpo"><span className="m-terciario">{vende ? 'Comprador' : 'Vendedor'}</span><Enlace href={`/mercado/v/${o.contraparte_slug}`}><b>{o.contraparte}</b></Enlace></div>
        </div>
        {o.nota && <Alerta><b>Nota:</b> {o.nota}</Alerta>}
      </section>

      {/* Acción principal según el rol y el estado */}
      {!cancelada && (
        <section className="m-pila" aria-label="Qué sigue">
          {vende && o.estado === 'pendiente' && <>
            <Boton icono="tarjeta" ancho onClick={() => setCobrar(true)}>Cobrar con terminal</Boton>
            <Boton variante="fantasma" ancho onClick={() => setVincular(true)}>Ya me pagó directo en la terminal</Boton>
          </>}
          {vende && o.estado === 'por_cobrar' && <Boton icono="terminal" variante="secundario" ancho onClick={() => setCobrar(true)}>Ver cobro en curso</Boton>}
          {vende && o.estado === 'pagada' && <Boton icono="check" ancho cargando={ocupado === 'entregar'} onClick={() => accion('entregar')}>Marcar como entregado</Boton>}
          {!vende && o.estado === 'pendiente' && <Alerta>Pasa con <b>{o.contraparte}</b> y paga con tarjeta en su terminal. El pago se registra solo.</Alerta>}
          {!vende && o.estado === 'por_cobrar' && <Alerta tipo="aviso">Pasa o acerca tu tarjeta a la terminal del vendedor.</Alerta>}
          {o.estado === 'pagada' && !vende && <Alerta tipo="exito">Pago registrado. Recoge tu pedido con {o.contraparte}.</Alerta>}
          {o.estado === 'pendiente' && <Boton variante="peligro" ancho onClick={() => setConfirmarCancelar(true)}>Cancelar orden</Boton>}
          {errorAccion && <Alerta tipo="error">{errorAccion}</Alerta>}
        </section>
      )}

      <section className="m-card m-pila" aria-labelledby="t-avance">
        <h2 id="t-avance">Avance</h2>
        {cancelada ? (
          <ol className="m-linea"><li data-estado="cancelado"><span className="m-paso"><Icono nombre="cerrar" /></span><span><b>Cancelada</b><br /><span className="m-terciario">{fecha(o.actualizada)}</span></span></li></ol>
        ) : (
          <ol className="m-linea">
            {PASOS.map(([e, t, d], i) => {
              const ev = o.eventos?.filter((x) => x.estado === e).at(-1)
              const estado = i < indice || o.estado === 'entregada' ? 'hecho' : i === indice ? (e === 'pagada' ? 'hecho' : 'actual') : ''
              return (
                <li key={e} data-estado={estado} aria-current={i === indice ? 'step' : undefined}>
                  <span className="m-paso">{estado === 'hecho' ? <Icono nombre="check" /> : <span aria-hidden="true" style={{ fontSize: 12 }}>{i + 1}</span>}</span>
                  <span className="m-pila-chica" style={{ gap: 0 }}>
                    <b style={{ color: estado ? undefined : 'var(--color-texto-3)' }}>{t}</b>
                    {(estado || i === indice) && <span className="m-terciario">{ev?.nota || d}{ev && ` · ${fecha(ev.creado)}`}</span>}
                  </span>
                </li>
              )
            })}
          </ol>
        )}
      </section>

      <Modal abierto={confirmarCancelar} onCerrar={() => setConfirmarCancelar(false)} titulo="¿Cancelar esta orden?">
        <p className="m-secundario">El producto vuelve a estar disponible para otros negocios. No se puede deshacer.</p>
        {errorAccion && <Alerta tipo="error">{errorAccion}</Alerta>}
        <div className="m-fila" style={{ justifyContent: 'flex-end' }}>
          <Boton variante="secundario" onClick={() => setConfirmarCancelar(false)}>No, mantener</Boton>
          <Boton variante="peligro" cargando={ocupado === 'cancelar'} onClick={() => accion('cancelar')}>Sí, cancelar</Boton>
        </div>
      </Modal>
      {cobrar && <CobroTerminal orden={o} cerrar={() => { setCobrar(false); recargar() }} />}
      {vincular && <VincularPago orden={o} cerrar={(ok) => { setVincular(false); if (ok) recargar() }} />}
    </div>
  )
}

/** Envía el total a la terminal y espera el resultado (que llega por la notificación de la terminal). */
function CobroTerminal({ orden, cerrar }: { orden: Orden; cerrar: () => void }) {
  const terminales = useConsulta((s) => api.get<Terminal[]>('/pagos/terminales', s), [])
  const [elegida, setElegida] = useState<number | null>(null)
  const [cobro, setCobro] = useState<Cobro | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const anuncio = useRef<HTMLParagraphElement>(null)

  useEffect(() => { if (terminales.datos?.length && elegida === null) setElegida(terminales.datos[0].id) }, [terminales.datos, elegida])
  // Si la orden ya está en la terminal, se recupera el cobro en curso.
  useEffect(() => {
    if (orden.estado === 'por_cobrar' && orden.cobro_id) api.get<Cobro>(`/pagos/cobros/${orden.cobro_id}`).then(setCobro).catch(() => {})
  }, [orden])
  useEffect(() => {
    if (cobro?.estado !== 'enviado') return
    const t = setInterval(() => api.get<Cobro>(`/pagos/cobros/${cobro.id}`).then(setCobro).catch(() => {}), 2000)
    return () => clearInterval(t)
  }, [cobro?.id, cobro?.estado])
  useEffect(() => { if (cobro?.estado === 'aprobado') avisar(`Pago de ${pesos(cobro.monto_c)} recibido`) }, [cobro?.estado]) // eslint-disable-line react-hooks/exhaustive-deps

  const enviar = async () => {
    if (!elegida) return
    setOcupado(true); setError(null)
    try { setCobro(await api.post<Cobro>('/pagos/cobros', { orden_id: orden.id, terminal_id: elegida })) }
    catch (e) { setError(mensajeDe(e)) } finally { setOcupado(false) }
  }
  const cancelar = async () => {
    if (!cobro) return
    setOcupado(true); setError(null)
    try { setCobro(await api.post<Cobro>(`/pagos/cobros/${cobro.id}/cancelar`)) }
    catch (e) { setError(mensajeDe(e)) } finally { setOcupado(false) }
  }
  const terminal = terminales.datos?.find((t) => t.id === elegida)

  return (
    <Modal abierto onCerrar={cerrar} titulo="Cobrar con terminal">
      <p ref={anuncio} className="sr-only" aria-live="assertive">
        {cobro?.estado === 'enviado' ? 'Esperando el pago en la terminal' : cobro?.estado === 'aprobado' ? 'Pago aprobado' : cobro ? `Cobro ${cobro.estado}` : ''}
      </p>
      {!cobro ? <>
        <div className="m-fila m-entre"><span className="m-secundario">Total</span><b style={{ fontSize: 'var(--texto-24)' }}>{pesos(orden.total_c)}</b></div>
        {terminales.cargando ? <Cargando><div className="m-pila-chica">{[0, 1].map((i) => <Esqueleto key={i} style={{ height: 60, borderRadius: 12 }} />)}</div></Cargando>
          : terminales.error ? <Alerta tipo="error">{terminales.error}</Alerta>
          : !terminales.datos?.length ? (
            <EstadoVacio icono="terminal" titulo="No tienes terminales vinculadas" texto="Vincula tu terminal de Mercado Pago o Clip (o la terminal virtual de prueba)."
              accion={<EnlaceBoton href="/mercado/vender?tab=terminales">Vincular terminal</EnlaceBoton>} />
          ) : (
            <fieldset className="m-pila-chica" style={{ border: 0, padding: 0, margin: 0 }}>
              <legend className="m-chico" style={{ fontWeight: 600, marginBottom: 8 }}>¿En qué terminal?</legend>
              {terminales.datos.map((t) => (
                <label key={t.id} className="m-opcion">
                  <input type="radio" name="terminal" checked={elegida === t.id} onChange={() => setElegida(t.id)} />
                  <Icono nombre="terminal" />
                  <span className="m-renglon-cuerpo"><b>{t.nombre}</b><span className="m-terciario">{t.marca}</span></span>
                </label>
              ))}
            </fieldset>
          )}
        {error && <Alerta tipo="error">{error}</Alerta>}
        <Boton ancho icono="tarjeta" cargando={ocupado} disabled={!elegida} onClick={enviar}>Enviar {pesos(orden.total_c)} a la terminal</Boton>
      </> : cobro.estado === 'enviado' || cobro.estado === 'creado' ? (
        <div className="m-esperando">
          <span className="m-pulso"><Icono nombre="tarjeta" /></span>
          <h3>Esperando el pago en «{cobro.terminal}»</h3>
          <p className="m-secundario">Pide al cliente que pase o acerque su tarjeta. Esta pantalla se actualiza sola.</p>
          <b style={{ fontSize: 'var(--texto-24)' }}>{pesos(cobro.monto_c)}</b>
          {(cobro.proveedor === 'simulador' || terminal?.proveedor === 'simulador') && (
            <a className="m-boton m-boton--secundario" href={`${import.meta.env.BASE_URL}mercado/terminal`} target="_blank" rel="noopener">
              <Icono nombre="terminal" /><span>Abrir terminal virtual</span>
            </a>
          )}
          {error && <Alerta tipo="error">{error}</Alerta>}
          <Boton variante="peligro" cargando={ocupado} onClick={cancelar}>Cancelar cobro</Boton>
        </div>
      ) : cobro.estado === 'aprobado' ? (
        <div className="m-estado m-estado--exito" style={{ padding: '8px 0' }}>
          <span className="m-estado-icono"><Icono nombre="check" /></span>
          <h3>Pago aprobado</h3>
          <p>{pesos(cobro.monto_c)} en {cobro.terminal}. Quedó en tu registro de cobros.</p>
          <Boton onClick={cerrar}>Listo</Boton>
        </div>
      ) : (
        <div className="m-estado m-estado--error" style={{ padding: '8px 0' }}>
          <span className="m-estado-icono"><Icono nombre="alerta" /></span>
          <h3>{cobro.estado === 'cancelado' ? 'Cobro cancelado' : 'No se completó el pago'}</h3>
          <p>{cobro.detalle || 'La terminal no aprobó el cobro.'} La orden sigue apartada.</p>
          <div className="m-fila"><Boton variante="secundario" onClick={cerrar}>Cerrar</Boton><Boton onClick={() => { setCobro(null); setError(null) }}>Intentar de nuevo</Boton></div>
        </div>
      )}
    </Modal>
  )
}

/** Liga un pago que se hizo directo en la terminal (registrado por notificación) con esta orden. */
function VincularPago({ orden, cerrar }: { orden: Orden; cerrar: (ok: boolean) => void }) {
  const { datos, error, cargando } = useConsulta((s) => api.get<{ items: Registro[] }>('/pagos/registros', s), [])
  const [ocupado, setOcupado] = useState<number | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const libres = datos?.items.filter((r) => r.origen === 'terminal' && r.orden_id === null) ?? []
  const iguales = libres.filter((r) => r.monto_c === orden.total_c)
  const ligar = async (id: number) => {
    setOcupado(id); setErr(null)
    try { await api.post(`/pagos/registros/${id}/vincular`, { orden_id: orden.id }); avisar('Pago ligado a la orden'); cerrar(true) }
    catch (e) { setErr(mensajeDe(e)) } finally { setOcupado(null) }
  }
  return (
    <Modal abierto onCerrar={() => cerrar(false)} titulo="Ligar un pago de la terminal">
      <p className="m-secundario">Pagos de {pesos(orden.total_c)} que tu terminal notificó y aún no están ligados a una orden.</p>
      {cargando ? <EsqueletoLista n={2} /> : error ? <Alerta tipo="error">{error}</Alerta>
        : !iguales.length ? <EstadoVacio icono="tarjeta" titulo="No hay pagos por ese monto" texto="Cuando cobres en la terminal sin usar la app, el pago aparecerá aquí en segundos." />
        : <div className="m-lista">{iguales.map((r) => (
          <div key={r.id} className="m-renglon">
            <Icono nombre="tarjeta" />
            <div className="m-renglon-cuerpo"><b>{pesos(r.monto_c)}</b><span className="m-terciario">{r.terminal} · {fecha(r.pagado_en)}</span></div>
            <Boton chico cargando={ocupado === r.id} disabled={ocupado !== null} onClick={() => ligar(r.id)}>Ligar</Boton>
          </div>
        ))}</div>}
      {err && <Alerta tipo="error">{err}</Alerta>}
    </Modal>
  )
}
