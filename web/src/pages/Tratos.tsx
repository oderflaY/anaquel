import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, hace, numero, pesos, type Trato } from '../api'
import type { Sesion } from '../App'
import { Enlace } from '../router'
import { avisar, Estado, mensajeDe, useDatos } from '../ui'

const textoEstado: Record<string, string> = {
  negociando: 'Negociando', aceptado: 'Aceptado', entregado: 'Entregado', cancelado: 'Cancelado',
}

export function EstadoTrato({ t }: { t: Trato }) {
  const cls = t.estado === 'entregado' ? 'llego' : t.estado === 'aceptado' ? 'confirmado' : t.estado === 'cancelado' ? 'cancelado' : 'enviado'
  return <span className={`chip ${cls}`}>{textoEstado[t.estado]}</span>
}

export default function Tratos() {
  const { datos, error } = useDatos(() => api.get<Trato[]>('/tratos'))
  const pendiente = (t: Trato) => t.te_toca || (t.estado === 'aceptado' && t.rol === 'vendo')
  const abiertos = (datos ?? []).filter((t) => t.estado === 'negociando' || t.estado === 'aceptado')
  const cerrados = (datos ?? []).filter((t) => !abiertos.includes(t))
  const fila = (t: Trato) => (
    <Enlace key={t.id} href={`/panel/tratos/${t.id}`} className="tarjeta renglon trato-fila">
      <div style={{ minWidth: 0 }}>
        <b>{t.rol === 'compro' ? 'Compras' : 'Vendes'} {numero(t.cantidad)} {t.titulo}</b>
        <div className="secundario chico">{t.rol === 'compro' ? 'a' : 'para'} {t.otra.nombre} · {pesos(t.precio_c)} c/u · {hace(t.actualizado)}</div>
        {t.ultimo && <div className="chico ultimo">{t.ultimo}</div>}
      </div>
      <div className="fila">
        {pendiente(t) && <span className="chip vacio">{t.te_toca ? 'Te toca responder' : 'Entrega pendiente'}</span>}
        <EstadoTrato t={t} />
      </div>
    </Enlace>
  )
  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div>
        <h1 style={{ marginBottom: '.2rem' }}>Tratos</h1>
        <p className="secundario" style={{ margin: 0 }}>Tus negociaciones con otras tiendas de la red. Empiezan con «Negociar» u «Ofrecer» en la Red o desde tu asesor.</p>
      </div>
      <Estado error={error} cargando={!datos} />
      {datos && abiertos.length === 0 && <div className="tarjeta vacio-lista">No tienes tratos abiertos. <Enlace href="/panel/red">Busca en la red</Enlace></div>}
      <div className="lista">{abiertos.map(fila)}</div>
      {cerrados.length > 0 && <><h2 style={{ margin: 0 }}>Terminados</h2><div className="lista">{cerrados.map(fila)}</div></>}
    </div>
  )
}

export function TratoDetalle({ id, sesion }: { id: number; sesion: Sesion }) {
  const { datos: t, error, setDatos, recargar } = useDatos(() => api.get<Trato>(`/tratos/${id}`), [id])
  const [texto, setTexto] = useState('')
  const [contra, setContra] = useState<{ cantidad: string; precio: string } | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const fin = useRef<HTMLDivElement>(null)

  useEffect(() => { const i = setInterval(recargar, 10000); return () => clearInterval(i) }, [id]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { fin.current?.scrollIntoView({ block: 'nearest' }) }, [t?.mensajes?.length])

  if (!t) return <Estado error={error} cargando />
  const hacer = async (f: () => Promise<Trato>, ok?: string) => {
    setOcupado(true)
    try { setDatos(await f()); if (ok) avisar(ok); sesion.refrescar() } catch (e) { avisar(mensajeDe(e)) } finally { setOcupado(false) }
  }
  const enviar = (e: FormEvent) => {
    e.preventDefault()
    if (!texto.trim()) return
    hacer(() => api.post<Trato>(`/tratos/${id}/mensajes`, { texto }))
    setTexto('')
  }
  const enviarContra = (e: FormEvent) => {
    e.preventDefault()
    if (!contra) return
    hacer(() => api.post<Trato>(`/tratos/${id}/mensajes`, {
      cantidad: Number(contra.cantidad) || t.cantidad, precio_c: Math.round(Number(contra.precio) * 100),
    }), 'Contraoferta enviada')
    setContra(null)
  }
  const abierto = t.estado === 'negociando'

  return (
    <div className="columna" style={{ gap: '1rem', maxWidth: 760 }}>
      <Enlace href="/panel/tratos">← Tratos</Enlace>
      <div className="tarjeta columna" style={{ gap: '.5rem' }}>
        <div className="renglon">
          <div>
            <h2 style={{ margin: 0 }}>{t.titulo}</h2>
            <span className="secundario">{t.rol === 'compro' ? 'Le compras a' : 'Le vendes a'} <b>{t.otra.nombre}</b></span>
          </div>
          <EstadoTrato t={t} />
        </div>
        <div className="terminos">
          <div><span className="secundario chico">Cantidad</span><b>{numero(t.cantidad)}</b></div>
          <div><span className="secundario chico">Precio c/u</span><b>{pesos(t.precio_c)}</b></div>
          <div><span className="secundario chico">Total</span><b>{pesos(t.precio_c * t.cantidad)}</b></div>
          {t.disponible != null && t.rol === 'compro' && <div><span className="secundario chico">Le quedan</span><b>{numero(t.disponible)}</b></div>}
        </div>
        {abierto && (t.te_toca
          ? <div className="aviso">Te toca: acepta esta propuesta o haz una contraoferta.</div>
          : <div className="aviso ok">Esperando que {t.otra.nombre} responda tu propuesta.</div>)}
        {t.estado === 'aceptado' && (t.rol === 'vendo'
          ? <div className="aviso">Trato cerrado. Cuando entregues la mercancía, márcalo como entregado: se descuenta de tu inventario y se suma al de {t.otra.nombre}.</div>
          : <div className="aviso ok">Trato cerrado. Cuando {t.otra.nombre} entregue, se sumará a tu inventario.</div>)}
        <div className="fila">
          {abierto && t.te_toca && <button className="boton" disabled={ocupado} onClick={() => hacer(() => api.post<Trato>(`/tratos/${id}/aceptar`), '¡Trato aceptado!')}>Aceptar trato</button>}
          {abierto && <button className="boton claro" onClick={() => setContra({ cantidad: String(t.cantidad), precio: String(t.precio_c / 100) })}>Contraoferta</button>}
          {t.estado === 'aceptado' && t.rol === 'vendo' && (
            <button className="boton" disabled={ocupado} onClick={() => confirm(`¿Ya entregaste ${numero(t.cantidad)} ${t.titulo}? Se descontarán de tu inventario.`) &&
              hacer(() => api.post<Trato>(`/tratos/${id}/entregar`), 'Entrega registrada en los dos inventarios')}>Marcar entregado</button>
          )}
          {t.whatsapp && <a className="boton wa" href={t.whatsapp} target="_blank" rel="noreferrer">WhatsApp</a>}
          {(abierto || t.estado === 'aceptado') && (
            <button className="boton peligro" disabled={ocupado} onClick={() => confirm('¿Cancelar este trato?') && hacer(() => api.post<Trato>(`/tratos/${id}/cancelar`))}>Cancelar</button>
          )}
        </div>
        {contra && (
          <form className="fila" onSubmit={enviarContra}>
            <label className="campo espacio">Cantidad<input type="number" min={1} value={contra.cantidad} onChange={(e) => setContra({ ...contra, cantidad: e.target.value })} /></label>
            <label className="campo espacio">Precio c/u ($)<input type="number" min={0} step="0.5" value={contra.precio} onChange={(e) => setContra({ ...contra, precio: e.target.value })} /></label>
            <button className="boton" style={{ alignSelf: 'flex-end' }}>Enviar</button>
          </form>
        )}
      </div>

      <div className="chat">
        {(t.mensajes ?? []).map((m) => (
          <div key={m.id} className={`burbuja-chat ${m.de}`}>
            <div>{m.texto}</div>
            {m.cantidad != null && m.de !== 'sistema' && (
              <div className="oferta">Propuesta: {numero(m.cantidad)} × {pesos(m.precio_c ?? 0)} = {pesos((m.precio_c ?? 0) * m.cantidad)}</div>
            )}
            <span className="hora">{hace(m.creado)}</span>
          </div>
        ))}
        <div ref={fin} />
      </div>
      {t.estado !== 'cancelado' && t.estado !== 'entregado' && (
        <form className="fila" onSubmit={enviar}>
          <input style={{ flex: 1 }} value={texto} onChange={(e) => setTexto(e.target.value)} placeholder="Escribe un mensaje…" aria-label="Mensaje" />
          <button className="boton" disabled={ocupado || !texto.trim()}>Enviar</button>
        </form>
      )}
    </div>
  )
}
