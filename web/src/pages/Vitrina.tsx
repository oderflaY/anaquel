import { useState, type FormEvent } from 'react'
import { api, hace, numero, pesos, type Anuncio, type Producto } from '../api'
import type { Sesion } from '../App'
import { Enlace } from '../router'
import { avisar, Dialogo, Estado, mensajeDe, useDatos } from '../ui'

const tipos: [Anuncio['tipo'], string][] = [['producto', 'Producto'], ['servicio', 'Servicio'], ['promocion', 'Promoción']]
const nuevo: Anuncio = { id: 0, tipo: 'producto', titulo: '', texto: '', precio: '', activo: true, creado: 0 }

export default function Vitrina({ sesion }: { sesion: Sesion }) {
  const { datos: prods, error, recargar: recargarP } = useDatos(() => api.get<Producto[]>('/productos'))
  const { datos: anuncios, recargar } = useDatos(() => api.get<Anuncio[]>('/anuncios'))
  const [editar, setEditar] = useState<Anuncio | null>(null)
  const publicados = (prods ?? []).filter((p) => p.publicado)
  const sinPublicar = (prods ?? []).filter((p) => !p.publicado && (p.stock ?? 0) > 0)

  const alternar = async (p: Producto) => {
    try {
      await api.put(`/productos/${p.id}`, { ...p, publicado: !p.publicado })
      avisar(p.publicado ? 'Quitado de tu vitrina' : 'Publicado: las tiendas que lo buscan reciben un aviso')
      recargarP()
    } catch (e) { avisar(mensajeDe(e)) }
  }

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div className="renglon">
        <div>
          <h1 style={{ marginBottom: '.2rem' }}>Mi vitrina en la red</h1>
          <p className="secundario" style={{ margin: 0 }}>Lo que otras tiendas ven de ti: tu inventario a la venta (al mayoreo o suelto) y tus anuncios.</p>
        </div>
        <Enlace href={`/t/${sesion.tienda.slug}`} className="boton claro">Ver como la ven los demás</Enlace>
      </div>

      <section className="columna">
        <div className="renglon">
          <h2 style={{ margin: 0 }}>Inventario a la venta ({publicados.length})</h2>
          <Enlace href="/panel/productos" className="boton claro chico">Precios de mayoreo en Inventario</Enlace>
        </div>
        <Estado error={error} cargando={!prods} />
        <div className="rejilla">
          {publicados.map((p) => (
            <div key={p.id} className="tarjeta columna" style={{ gap: '.3rem' }}>
              <b>{p.nombre}</b>
              <span className="secundario chico">{numero(p.stock ?? 0)} {p.unidad} disponibles</span>
              <span className="chico">{p.precio_mayoreo_c ? `${pesos(p.precio_mayoreo_c)} mayoreo desde ${numero(p.minimo_mayoreo)} · ` : ''}{pesos(p.precio_c)} suelto</span>
              <button className="boton peligro chico" style={{ alignSelf: 'flex-start' }} onClick={() => alternar(p)}>Quitar de la vitrina</button>
            </div>
          ))}
          {prods && publicados.length === 0 && <div className="tarjeta vacio-lista" style={{ gridColumn: '1/-1' }}>Aún no ofreces nada a otras tiendas.</div>}
        </div>
        {sinPublicar.length > 0 && (
          <details className="tarjeta">
            <summary><b>Publicar más de mi inventario ({sinPublicar.length})</b></summary>
            <div className="lista" style={{ marginTop: '.75rem' }}>
              {sinPublicar.map((p) => (
                <div key={p.id} className="renglon">
                  <span>{p.nombre} <span className="secundario chico">· hay {numero(p.stock ?? 0)}</span></span>
                  <button className="boton chico" onClick={() => alternar(p)}>Publicar</button>
                </div>
              ))}
            </div>
          </details>
        )}
      </section>

      <section className="columna">
        <div className="renglon">
          <h2 style={{ margin: 0 }}>Mis anuncios</h2>
          <button className="boton" onClick={() => setEditar({ ...nuevo })}>+ Anuncio</button>
        </div>
        <p className="secundario chico" style={{ margin: 0 }}>Para servicios (bordado, fletes, impresión), promociones o productos que fabricas sobre pedido.</p>
        <div className="lista">
          {(anuncios ?? []).map((a) => (
            <div key={a.id} className="tarjeta renglon">
              <div>
                <b>{a.titulo}</b> <span className="chip enviado">{tipos.find(([k]) => k === a.tipo)?.[1]}</span>
                {!a.activo && <span className="chip cancelado" style={{ marginLeft: 6 }}>pausado</span>}
                <div className="secundario chico">{a.precio ? `${a.precio} · ` : ''}{hace(a.creado)}</div>
              </div>
              <button className="boton claro chico" onClick={() => setEditar(a)}>Editar</button>
            </div>
          ))}
          {anuncios && anuncios.length === 0 && <div className="tarjeta vacio-lista">Sin anuncios.</div>}
        </div>
      </section>

      <Dialogo abierto={!!editar} onCerrar={() => setEditar(null)} titulo={editar?.id ? 'Editar anuncio' : 'Nuevo anuncio'}>
        {editar && <FormAnuncio a={editar} alTerminar={() => { setEditar(null); recargar() }} />}
      </Dialogo>
    </div>
  )
}

function FormAnuncio({ a, alTerminar }: { a: Anuncio; alTerminar: () => void }) {
  const [f, setF] = useState(a)
  const [ocupado, setOcupado] = useState(false)
  const guardar = async (e: FormEvent) => {
    e.preventDefault()
    setOcupado(true)
    try {
      if (a.id) await api.put(`/anuncios/${a.id}`, f); else await api.post('/anuncios', f)
      avisar('Anuncio guardado'); alTerminar()
    } catch (er) { avisar(mensajeDe(er)) } finally { setOcupado(false) }
  }
  const borrar = async () => {
    if (!confirm('¿Borrar este anuncio?')) return
    try { await api.del(`/anuncios/${a.id}`); alTerminar() } catch (er) { avisar(mensajeDe(er)) }
  }
  return (
    <form className="columna" onSubmit={guardar}>
      <div className="fila">
        {tipos.map(([k, t]) => <button type="button" key={k} className={`boton chico ${f.tipo === k ? '' : 'claro'}`} onClick={() => setF({ ...f, tipo: k })}>{t}</button>)}
      </div>
      <label className="campo">Título<input required maxLength={120} value={f.titulo} onChange={(e) => setF({ ...f, titulo: e.target.value })} placeholder="Bordado de logos en gorras y playeras" /></label>
      <label className="campo">Descripción<textarea maxLength={1000} value={f.texto} onChange={(e) => setF({ ...f, texto: e.target.value })} placeholder="Qué ofreces, tiempos de entrega, mínimos…" /></label>
      <label className="campo">Precio (texto libre)<input maxLength={80} value={f.precio} onChange={(e) => setF({ ...f, precio: e.target.value })} placeholder="Desde $15 por pieza" /></label>
      <label className="fila" style={{ fontWeight: 600 }}>
        <input type="checkbox" style={{ width: 22, minHeight: 22 }} checked={f.activo} onChange={(e) => setF({ ...f, activo: e.target.checked })} /> Visible en la red
      </label>
      <div className="fila">
        <button className="boton" disabled={ocupado}>Guardar</button>
        {a.id ? <button type="button" className="boton peligro" onClick={borrar}>Borrar</button> : null}
      </div>
    </form>
  )
}
