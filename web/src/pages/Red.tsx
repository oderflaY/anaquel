import { useEffect, useState, type FormEvent } from 'react'
import { api, hace, numero, type Resultado, type Solicitud, type TiendaCorta } from '../api'
import type { Sesion } from '../App'
import { DialogoNegociar, TarjetaRed } from '../red'
import { ir } from '../router'
import { avisar, Dialogo, Estado, mensajeDe, useDatos } from '../ui'

const TIPOS: [string, string][] = [
  ['todo', 'Todo'], ['producto', 'Productos'], ['servicio', 'Servicios'],
  ['solicitud', 'Lo que buscan otras tiendas'], ['productor', 'Productores'],
]

export default function Red({ sesion, consulta }: { sesion: Sesion; consulta: string }) {
  const params = new URLSearchParams(consulta)
  const [q, setQ] = useState(params.get('q') ?? '')
  const [texto, setTexto] = useState(params.get('q') ?? '')
  const [tipo, setTipo] = useState('todo')
  const solicitudId = params.get('solicitud')
  useEffect(() => { setQ(params.get('q') ?? ''); setTexto(params.get('q') ?? '') }, [consulta]) // eslint-disable-line react-hooks/exhaustive-deps

  const { datos, error } = useDatos(() => api.get<Resultado[]>(`/red?q=${encodeURIComponent(q)}&tipo=${tipo}`), [q, tipo])
  const { datos: mias, recargar } = useDatos(() => api.get<Solicitud[]>('/solicitudes'))
  const { datos: destacada } = useDatos(
    () => (solicitudId ? api.get<{ solicitud: Solicitud; tienda: TiendaCorta }>(`/solicitudes/${solicitudId}`) : Promise.resolve(null)),
    [solicitudId],
  )
  const [negociar, setNegociar] = useState<Resultado | null>(null)
  const [publicar, setPublicar] = useState(false)

  const buscar = (e: FormEvent) => { e.preventDefault(); ir(`/panel/red${texto ? `?q=${encodeURIComponent(texto)}` : ''}`) }
  const abiertas = (mias ?? []).filter((s) => s.estado === 'abierta')
  const ajena = destacada && destacada.tienda.id !== sesion.tienda.id ? destacada : null

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div className="renglon">
        <div>
          <h1 style={{ marginBottom: '.2rem' }}>Red de comercio local</h1>
          <p className="secundario" style={{ margin: 0 }}>Lo que venden, anuncian y buscan las tiendas, proveedores y productores de tu ciudad.</p>
        </div>
        <button className="boton ambar" onClick={() => setPublicar(true)}>📣 Publicar lo que busco</button>
      </div>

      {ajena && ajena.solicitud.estado === 'abierta' && (
        <div className="tarjeta columna" style={{ borderTop: '4px solid var(--ambar)' }}>
          <b>{ajena.tienda.nombre} busca {ajena.solicitud.cantidad ? numero(ajena.solicitud.cantidad) + ' ' : ''}{ajena.solicitud.que}</b>
          {ajena.solicitud.detalle && <span className="secundario">{ajena.solicitud.detalle}</span>}
          <div className="fila">
            <button className="boton" onClick={() => setNegociar({
              tipo: 'solicitud', id: ajena.solicitud.id, titulo: ajena.solicitud.que, detalle: ajena.solicitud.detalle,
              cantidad: ajena.solicitud.cantidad, tienda: ajena.tienda, creado: ajena.solicitud.creado,
            })}>Ofrecérselo</button>
          </div>
        </div>
      )}

      <form className="fila" onSubmit={buscar}>
        <input style={{ flex: 1, minWidth: 200 }} value={texto} onChange={(e) => setTexto(e.target.value)}
          placeholder="¿Qué necesitas? Gorras, chamarras, bordado, flete…" aria-label="Buscar en la red" />
        <button className="boton">Buscar</button>
      </form>
      <div className="fila" role="tablist" aria-label="Tipo de resultado">
        {TIPOS.map(([k, t]) => (
          <button key={k} role="tab" aria-selected={tipo === k} className={`boton chico ${tipo === k ? '' : 'claro'}`} onClick={() => setTipo(k)}>{t}</button>
        ))}
      </div>

      <Estado error={error} cargando={!datos} />
      {datos && (
        <div className="rejilla">
          {datos.map((r) => <TarjetaRed key={r.tipo + r.id} r={r} onNegociar={setNegociar} />)}
          {datos.length === 0 && (
            <div className="tarjeta vacio-lista" style={{ gridColumn: '1/-1' }}>
              Nadie lo tiene publicado todavía.{' '}
              <button className="boton chico ambar" onClick={() => setPublicar(true)}>Publicar que lo busco</button>
            </div>
          )}
        </div>
      )}

      <section className="columna">
        <h2 style={{ margin: 0 }}>Lo que yo busco</h2>
        {abiertas.length === 0 && <p className="secundario" style={{ margin: 0 }}>No tienes solicitudes abiertas. Publica lo que necesitas y te avisamos cuando alguien lo tenga.</p>}
        <div className="lista">
          {abiertas.map((s) => (
            <div key={s.id} className="tarjeta renglon">
              <div>
                <b>{s.cantidad ? numero(s.cantidad) + ' ' : ''}{s.que}</b>
                <div className="secundario chico">{s.detalle || 'Sin detalles'} · {hace(s.creado)}{s.por_asesor ? ' · la publicó tu asesor' : ''}{s.ofertas ? ` · ${s.ofertas} oferta(s)` : ''}</div>
              </div>
              <div className="fila">
                <button className="boton claro chico" onClick={() => ir(`/panel/red?q=${encodeURIComponent(s.que)}`)}>Ver quién lo tiene</button>
                <button className="boton peligro chico" onClick={async () => {
                  try { await api.post(`/solicitudes/${s.id}/cerrar`); recargar() } catch (e) { avisar(mensajeDe(e)) }
                }}>Ya lo conseguí</button>
              </div>
            </div>
          ))}
        </div>
      </section>

      <DialogoNegociar r={negociar} onCerrar={() => setNegociar(null)} />
      <Dialogo abierto={publicar} onCerrar={() => setPublicar(false)} titulo="¿Qué estás buscando?">
        <FormSolicitud inicial={texto} alTerminar={(enc) => {
          setPublicar(false); recargar()
          avisar(enc ? `¡${enc} tienda(s) ya lo tienen! Les avisamos.` : 'Publicado. Te avisamos cuando alguien lo tenga.')
        }} />
      </Dialogo>
    </div>
  )
}

function FormSolicitud({ inicial, alTerminar }: { inicial: string; alTerminar: (encontrados: number) => void }) {
  const [f, setF] = useState({ que: inicial, cantidad: '', detalle: '' })
  const [ocupado, setOcupado] = useState(false)
  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    setOcupado(true)
    try {
      const r = await api.post<{ encontrados: Resultado[] | null }>('/solicitudes', { ...f, cantidad: Number(f.cantidad) || 0 })
      alTerminar(new Set((r.encontrados ?? []).map((x) => x.tienda?.id)).size)
      ir(`/panel/red?q=${encodeURIComponent(f.que)}`)
    } catch (er) { avisar(mensajeDe(er)) } finally { setOcupado(false) }
  }
  return (
    <form className="columna" onSubmit={enviar}>
      <p className="secundario chico" style={{ margin: 0 }}>Todas las tiendas y proveedores que lo tengan reciben un aviso y te pueden hacer una oferta.</p>
      <label className="campo">Producto o servicio<input required value={f.que} onChange={(e) => setF({ ...f, que: e.target.value })} placeholder="gorras" /></label>
      <label className="campo">Cantidad<input type="number" min={0} value={f.cantidad} onChange={(e) => setF({ ...f, cantidad: e.target.value })} placeholder="1000" /></label>
      <label className="campo">Detalles<textarea value={f.detalle} onChange={(e) => setF({ ...f, detalle: e.target.value })} placeholder="Color, talla, para cuándo lo necesitas…" /></label>
      <button className="boton ancho" disabled={ocupado}>{ocupado ? 'Publicando…' : 'Publicar en la red'}</button>
    </form>
  )
}
