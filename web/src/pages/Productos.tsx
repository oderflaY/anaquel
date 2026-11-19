import { useState, type FormEvent } from 'react'
import { api, pesos, type Productor, type Producto, type Proveedor } from '../api'
import type { Sesion } from '../App'
import { avisar, Dialogo, Estado, mensajeDe, useDatos } from '../ui'

const vacio: Producto = { id: 0, codigo: '', nombre: '', categoria: '', precio_c: 0, stock: null, stock_min: 0, proveedor_id: null, productor_id: null }

export default function Productos({ sesion }: { sesion: Sesion }) {
  const { datos, error, recargar } = useDatos(() => api.get<Producto[]>('/productos'))
  const { datos: provs } = useDatos(() => api.get<Proveedor[]>('/proveedores'))
  const t = sesion.tienda
  const { datos: prods } = useDatos(() => api.get<Productor[]>(`/publico/productores${t.lat != null ? `?lat=${t.lat}&lng=${t.lng}` : ''}`))
  const [editar, setEditar] = useState<Producto | null>(null)
  const [buscar, setBuscar] = useState('')

  const lista = (datos ?? []).filter((p) => (p.nombre + ' ' + p.categoria + ' ' + p.codigo).toLowerCase().includes(buscar.toLowerCase()))

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div className="renglon">
        <div>
          <h1 style={{ marginBottom: '.2rem' }}>Productos</h1>
          <p className="secundario" style={{ margin: 0 }}>Tu inventario. La app de Android/iPhone lo sincroniza solo.</p>
        </div>
        <button className="boton" onClick={() => setEditar({ ...vacio })}>+ Producto</button>
      </div>
      <input placeholder="Buscar…" value={buscar} onChange={(e) => setBuscar(e.target.value)} aria-label="Buscar producto" />
      <Estado error={error} cargando={!datos} />
      {datos && (
        <div className="tarjeta tabla-scroll">
          <table>
            <thead><tr><th>Producto</th><th className="solo-ancho">Precio</th><th>Stock</th><th className="solo-ancho">Se pide a</th></tr></thead>
            <tbody>
              {lista.map((p) => (
                <tr key={p.id} onClick={() => setEditar(p)} style={{ cursor: 'pointer' }}>
                  <td><b>{p.nombre}</b><div className="secundario chico">{p.categoria}{p.productor ? ` · 🌱 ${p.productor}` : ''}</div></td>
                  <td className="solo-ancho">{pesos(p.precio_c)}</td>
                  <td>{p.stock ?? '—'}{p.stock != null && p.stock_min > 0 && p.stock <= p.stock_min ? <span className="chip bajo" style={{ marginLeft: 6 }}>bajo</span> : null}</td>
                  <td className="solo-ancho secundario chico">{provs?.find((v) => v.id === p.proveedor_id)?.nombre ?? p.productor ?? '—'}</td>
                </tr>
              ))}
              {lista.length === 0 && <tr><td colSpan={4} className="vacio-lista">Sin productos.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
      <Dialogo abierto={!!editar} onCerrar={() => setEditar(null)} titulo={editar?.id ? 'Editar producto' : 'Nuevo producto'}>
        {editar && <FormProducto p={editar} provs={provs ?? []} prods={prods ?? []}
          alTerminar={() => { setEditar(null); recargar(); sesion.refrescar() }} />}
      </Dialogo>
    </div>
  )
}

function FormProducto({ p, provs, prods, alTerminar }: { p: Producto; provs: Proveedor[]; prods: Productor[]; alTerminar: () => void }) {
  const [f, setF] = useState({ ...p, precio: p.precio_c ? String(p.precio_c / 100) : '', stockTxt: p.stock == null ? '' : String(p.stock) })
  const [ocupado, setOcupado] = useState(false)
  const guardar = async (e: FormEvent) => {
    e.preventDefault()
    setOcupado(true)
    const cuerpo = {
      ...f, precio_c: Math.round(Number(f.precio || 0) * 100),
      stock: f.stockTxt === '' ? null : Math.max(0, Number(f.stockTxt)), stock_min: Number(f.stock_min) || 0,
    }
    try {
      if (p.id) await api.put(`/productos/${p.id}`, cuerpo); else await api.post('/productos', cuerpo)
      avisar('Producto guardado'); alTerminar()
    } catch (er) { avisar(mensajeDe(er)) } finally { setOcupado(false) }
  }
  const borrar = async () => {
    if (!confirm(`¿Borrar «${p.nombre}»?`)) return
    try { await api.del(`/productos/${p.id}`); alTerminar() } catch (er) { avisar(mensajeDe(er)) }
  }
  return (
    <form className="columna" onSubmit={guardar}>
      <label className="campo">Nombre<input required value={f.nombre} onChange={(e) => setF({ ...f, nombre: e.target.value })} /></label>
      <div className="fila">
        <label className="campo espacio">Categoría<input value={f.categoria} onChange={(e) => setF({ ...f, categoria: e.target.value })} /></label>
        <label className="campo espacio">Código de barras<input value={f.codigo} onChange={(e) => setF({ ...f, codigo: e.target.value })} /></label>
      </div>
      <div className="fila">
        <label className="campo espacio">Precio ($)<input type="number" min={0} step="0.5" value={f.precio} onChange={(e) => setF({ ...f, precio: e.target.value })} /></label>
        <label className="campo espacio">Stock<input type="number" min={0} value={f.stockTxt} placeholder="sin contar" onChange={(e) => setF({ ...f, stockTxt: e.target.value })} /></label>
        <label className="campo espacio">Mínimo<input type="number" min={0} value={f.stock_min} onChange={(e) => setF({ ...f, stock_min: Number(e.target.value) })} /></label>
      </div>
      <label className="campo">Proveedor habitual
        <select value={f.proveedor_id ?? ''} onChange={(e) => setF({ ...f, proveedor_id: e.target.value ? Number(e.target.value) : null })}>
          <option value="">— Ninguno —</option>
          {provs.map((v) => <option key={v.id} value={v.id}>{v.nombre}</option>)}
        </select>
      </label>
      <label className="campo">🌱 Lo produce (productor local)
        <select value={f.productor_id ?? ''} onChange={(e) => setF({ ...f, productor_id: e.target.value ? Number(e.target.value) : null })}>
          <option value="">— No es producto local —</option>
          {prods.map((v) => <option key={v.id} value={v.id}>{v.nombre} · {v.productos}{v.distancia_km != null ? ` (${v.distancia_km} km)` : ''}</option>)}
        </select>
      </label>
      <div className="fila">
        <button className="boton" disabled={ocupado}>Guardar</button>
        {p.id ? <button type="button" className="boton peligro" onClick={borrar}>Borrar</button> : null}
      </div>
    </form>
  )
}
