import { useState } from 'react'
import { api, hace, type Pedido } from '../api'
import type { Sesion } from '../App'
import { avisar, Estado, mensajeDe, Nivel, useDatos } from '../ui'

export default function Pedidos({ sesion }: { sesion: Sesion }) {
  const { datos, error, recargar } = useDatos(() => api.get<Pedido[]>('/pedidos'))
  const [filtro, setFiltro] = useState<'abiertos' | 'todos'>('abiertos')

  const cambiar = async (p: Pedido, estado: string) => {
    if (estado === 'cancelado' && !confirm(`¿Cancelar el pedido #${p.id} a ${p.destino}?`)) return
    try {
      await api.post(`/pedidos/${p.id}/estado`, { estado })
      recargar(); sesion.refrescar()
    } catch (e) { avisar(mensajeDe(e)) }
  }
  const lista = (datos ?? []).filter((p) => filtro === 'todos' || p.estado === 'enviado' || p.estado === 'confirmado')

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div className="renglon">
        <div>
          <h1 style={{ marginBottom: '.2rem' }}>Pedidos a proveedores</h1>
          <p className="secundario" style={{ margin: 0 }}>Un pedido se marca como «Llegó» solo cuando la foto de tu anaquel muestra el hueco lleno.</p>
        </div>
        <div className="fila">
          <button className={`boton chico ${filtro === 'abiertos' ? '' : 'claro'}`} onClick={() => setFiltro('abiertos')}>En camino</button>
          <button className={`boton chico ${filtro === 'todos' ? '' : 'claro'}`} onClick={() => setFiltro('todos')}>Todos</button>
        </div>
      </div>
      <Estado error={error} cargando={!datos} />
      {datos && lista.length === 0 && <div className="tarjeta vacio-lista">No hay pedidos {filtro === 'abiertos' ? 'en camino' : ''}. Se crean desde «Hoy».</div>}
      <div className="lista">
        {lista.map((p) => (
          <div key={p.id} className="tarjeta columna" style={{ gap: '.6rem' }}>
            <div className="renglon">
              <div>
                <b>#{p.id} · {p.destino}</b> {p.tipo === 'productor' && <span className="chip lleno">🌱 Local</span>}
                <div className="secundario chico">Pedido {hace(p.creado)}{p.estado === 'llego' && p.llego_auto ? ' · confirmado por foto' : ''}</div>
              </div>
              <div className="fila">
                {p.atrasado && <Nivel nivel="atrasado" />}
                <Nivel nivel={p.estado} />
              </div>
            </div>
            <ul style={{ margin: 0, paddingLeft: '1.2rem' }}>
              {p.items.map((it, i) => (
                <li key={i} style={{ textDecoration: it.recibido ? 'line-through' : undefined }}>
                  {it.cantidad} × {it.nombre}{it.recibido ? ' ✓' : ''}
                </li>
              ))}
            </ul>
            {(p.estado === 'enviado' || p.estado === 'confirmado') && (
              <div className="fila">
                <a className="boton wa chico" href={p.whatsapp} target="_blank" rel="noreferrer">{p.atrasado ? 'Preguntar por WhatsApp' : 'Abrir WhatsApp'}</a>
                {p.estado === 'enviado' && <button className="boton claro chico" onClick={() => cambiar(p, 'confirmado')}>Me confirmó</button>}
                <button className="boton claro chico" onClick={() => cambiar(p, 'llego')}>Ya llegó</button>
                <button className="boton peligro chico" onClick={() => cambiar(p, 'cancelado')}>Cancelar</button>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
