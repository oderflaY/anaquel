import { useState, type FormEvent } from 'react'
import { api, type Proveedor } from '../api'
import type { Sesion } from '../App'
import { Enlace } from '../router'
import { avisar, Dialogo, Estado, mensajeDe, useDatos } from '../ui'

export default function Proveedores({ sesion }: { sesion: Sesion }) {
  const { datos, error, recargar } = useDatos(() => api.get<Proveedor[]>('/proveedores'))
  const [editar, setEditar] = useState<Proveedor | null>(null)

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div className="renglon">
        <div>
          <h1 style={{ marginBottom: '.2rem' }}>Proveedores</h1>
          <p className="secundario" style={{ margin: 0 }}>
            Quien te surte. En «Surte» escribe palabras como «refresco, agua» para que Anaquel lo sugiera solo.
            Los productores locales están en <Enlace href="/productores">el directorio</Enlace>.
          </p>
        </div>
        <button className="boton" onClick={() => setEditar({ id: 0, nombre: '', telefono: '', surte: '' })}>+ Proveedor</button>
      </div>
      <Estado error={error} cargando={!datos} />
      <div className="rejilla">
        {datos?.map((p) => (
          <div key={p.id} className="tarjeta columna" style={{ gap: '.4rem' }}>
            <b>{p.nombre}</b>
            <span className="secundario chico">{p.telefono || 'Sin teléfono'}</span>
            {p.surte && <span className="chico">Surte: {p.surte}</span>}
            <button className="boton claro chico" style={{ alignSelf: 'flex-start' }} onClick={() => setEditar(p)}>Editar</button>
          </div>
        ))}
        {datos?.length === 0 && <div className="tarjeta vacio-lista">Aún no tienes proveedores.</div>}
      </div>
      <Dialogo abierto={!!editar} onCerrar={() => setEditar(null)} titulo={editar?.id ? 'Editar proveedor' : 'Nuevo proveedor'}>
        {editar && <Form p={editar} alTerminar={() => { setEditar(null); recargar(); sesion.refrescar() }} />}
      </Dialogo>
    </div>
  )
}

function Form({ p, alTerminar }: { p: Proveedor; alTerminar: () => void }) {
  const [f, setF] = useState(p)
  const guardar = async (e: FormEvent) => {
    e.preventDefault()
    try {
      if (p.id) await api.put(`/proveedores/${p.id}`, f); else await api.post('/proveedores', f)
      alTerminar()
    } catch (er) { avisar(mensajeDe(er)) }
  }
  const borrar = async () => {
    if (!confirm(`¿Borrar a «${p.nombre}»?`)) return
    try { await api.del(`/proveedores/${p.id}`); alTerminar() } catch (er) { avisar(mensajeDe(er)) }
  }
  return (
    <form className="columna" onSubmit={guardar}>
      <label className="campo">Nombre<input required value={f.nombre} onChange={(e) => setF({ ...f, nombre: e.target.value })} placeholder="Ruta de refrescos" /></label>
      <label className="campo">WhatsApp<input type="tel" value={f.telefono} onChange={(e) => setF({ ...f, telefono: e.target.value })} placeholder="618 000 0000" /></label>
      <label className="campo">Surte<input value={f.surte} onChange={(e) => setF({ ...f, surte: e.target.value })} placeholder="refresco, agua, jugo" /></label>
      <div className="fila">
        <button className="boton">Guardar</button>
        {p.id ? <button type="button" className="boton peligro" onClick={borrar}>Borrar</button> : null}
      </div>
    </form>
  )
}
