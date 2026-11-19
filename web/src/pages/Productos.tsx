import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, hace, numero, pesos, type Movimiento, type Productor, type Producto, type Proveedor } from '../api'
import type { Sesion } from '../App'
import { Enlace } from '../router'
import { avisar, Dialogo, Estado, mensajeDe, useDatos } from '../ui'

const vacio: Producto = {
  id: 0, codigo: '', nombre: '', categoria: '', precio_c: 0, stock: null, stock_min: 0, proveedor_id: null, productor_id: null,
  publicado: false, precio_mayoreo_c: 0, minimo_mayoreo: 0, unidad: 'pza', descripcion: '',
}
const tipoMov: Record<string, string> = {
  entrada: 'Entrada', salida: 'Salida', venta: 'Venta', ajuste: 'Ajuste (conteo)', trato_entrada: 'Compra en la red', trato_salida: 'Venta en la red',
}
const bajo = (p: Producto) => p.stock != null && p.stock_min > 0 && p.stock <= p.stock_min

export default function Inventario({ sesion }: { sesion: Sesion }) {
  const { datos, error, recargar } = useDatos(() => api.get<Producto[]>('/productos'))
  const { datos: movs, recargar: recargarMovs } = useDatos(() => api.get<Movimiento[]>('/movimientos'))
  const { datos: provs } = useDatos(() => api.get<Proveedor[]>('/proveedores'))
  const t = sesion.tienda
  const { datos: prods } = useDatos(() => api.get<Productor[]>(`/publico/productores${t.lat != null ? `?lat=${t.lat}&lng=${t.lng}` : ''}`))
  const [editar, setEditar] = useState<Producto | null>(null)
  const [mover, setMover] = useState<Producto | null>(null)
  const [kardex, setKardex] = useState<Producto | null>(null)
  const [caja, setCaja] = useState(false)
  const [buscar, setBuscar] = useState('')
  const [filtro, setFiltro] = useState<'todos' | 'bajo' | 'vitrina'>('todos')

  const todo = () => { recargar(); recargarMovs(); sesion.refrescar() }
  const lista = (datos ?? [])
    .filter((p) => (p.nombre + ' ' + p.categoria + ' ' + p.codigo).toLowerCase().includes(buscar.toLowerCase()))
    .filter((p) => filtro === 'todos' || (filtro === 'bajo' ? bajo(p) : p.publicado))
  const nBajo = (datos ?? []).filter(bajo).length

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div className="renglon">
        <div>
          <h1 style={{ marginBottom: '.2rem' }}>Inventario</h1>
          <p className="secundario" style={{ margin: 0 }}>Cada entrada, venta y ajuste queda en el kárdex. La app de Android/iPhone se sincroniza sola.</p>
        </div>
        <div className="fila">
          <button className="boton ambar" onClick={() => setCaja(true)}>🔫 Caja / pistola</button>
          <button className="boton" onClick={() => setEditar({ ...vacio })}>+ Producto</button>
        </div>
      </div>
      <div className="fila">
        <input style={{ flex: 1, minWidth: 200 }} placeholder="Buscar por nombre, categoría o código…" value={buscar} onChange={(e) => setBuscar(e.target.value)} aria-label="Buscar producto" />
        {([['todos', 'Todos'], ['bajo', `Bajo mínimo${nBajo ? ` (${nBajo})` : ''}`], ['vitrina', 'En mi vitrina']] as const).map(([k, txt]) => (
          <button key={k} className={`boton chico ${filtro === k ? '' : 'claro'}`} onClick={() => setFiltro(k)}>{txt}</button>
        ))}
      </div>
      <Estado error={error} cargando={!datos} />
      {datos && (
        <div className="tarjeta tabla-scroll">
          <table>
            <thead><tr><th>Producto</th><th>Existencia</th><th className="solo-ancho">Precio</th><th></th></tr></thead>
            <tbody>
              {lista.map((p) => (
                <tr key={p.id}>
                  <td onClick={() => setEditar(p)} style={{ cursor: 'pointer' }}>
                    <b>{p.nombre}</b>
                    <div className="secundario chico">
                      {p.categoria}{p.codigo ? ` · ${p.codigo}` : ''}{p.productor ? ` · 🌱 ${p.productor}` : ''}
                      {p.publicado && <span className="chip llego" style={{ marginLeft: 6 }}>En vitrina</span>}
                    </div>
                  </td>
                  <td>
                    <b>{p.stock == null ? '—' : numero(p.stock)}</b> <span className="secundario chico">{p.unidad}</span>
                    {bajo(p) && <span className="chip bajo" style={{ marginLeft: 6 }}>bajo</span>}
                    {p.stock_min > 0 && <div className="secundario chico">mín. {p.stock_min}</div>}
                  </td>
                  <td className="solo-ancho">{pesos(p.precio_c)}{p.precio_mayoreo_c ? <div className="secundario chico">mayoreo {pesos(p.precio_mayoreo_c)}</div> : null}</td>
                  <td>
                    <div className="fila" style={{ gap: '.4rem', justifyContent: 'flex-end' }}>
                      <button className="boton claro chico" onClick={() => setMover(p)}>Mover</button>
                      <button className="boton claro chico" onClick={() => setKardex(p)}>Kárdex</button>
                    </div>
                  </td>
                </tr>
              ))}
              {lista.length === 0 && <tr><td colSpan={4} className="vacio-lista">Sin productos.</td></tr>}
            </tbody>
          </table>
        </div>
      )}

      <section className="columna">
        <div className="renglon">
          <h2 style={{ margin: 0 }}>Últimos movimientos</h2>
          <Enlace href="/panel/proveedores" className="boton claro chico">Proveedores</Enlace>
        </div>
        <ListaMovs movs={(movs ?? []).slice(0, 15)} conProducto />
      </section>

      <Dialogo abierto={!!editar} onCerrar={() => setEditar(null)} titulo={editar?.id ? 'Editar producto' : 'Nuevo producto'}>
        {editar && <FormProducto p={editar} provs={provs ?? []} prods={prods ?? []} alTerminar={() => { setEditar(null); todo() }} />}
      </Dialogo>
      <Dialogo abierto={!!mover} onCerrar={() => setMover(null)} titulo={`Mover: ${mover?.nombre ?? ''}`}>
        {mover && <FormMovimiento p={mover} alTerminar={() => { setMover(null); todo() }} />}
      </Dialogo>
      <Dialogo abierto={!!kardex} onCerrar={() => setKardex(null)} titulo={`Kárdex: ${kardex?.nombre ?? ''}`}>
        {kardex && <Kardex p={kardex} />}
      </Dialogo>
      <Dialogo abierto={caja} onCerrar={() => setCaja(false)} titulo="Caja: escanea con la pistola o la cámara">
        {caja && <Caja productos={datos ?? []} alTerminar={() => { setCaja(false); todo() }} />}
      </Dialogo>
    </div>
  )
}

function ListaMovs({ movs, conProducto }: { movs: Movimiento[]; conProducto?: boolean }) {
  if (movs.length === 0) return <div className="tarjeta vacio-lista">Aún no hay movimientos.</div>
  return (
    <div className="tarjeta tabla-scroll">
      <table>
        <thead><tr>{conProducto && <th>Producto</th>}<th>Movimiento</th><th>Cantidad</th><th>Saldo</th></tr></thead>
        <tbody>
          {movs.map((m) => (
            <tr key={m.id}>
              {conProducto && <td>{m.producto}</td>}
              <td>{tipoMov[m.tipo] ?? m.tipo}<div className="secundario chico">{hace(m.creado)}{m.nota ? ` · ${m.nota}` : ''}</div></td>
              <td style={{ color: m.cantidad < 0 ? 'var(--rojo)' : 'var(--marca)', fontWeight: 700 }}>{m.cantidad > 0 ? '+' : ''}{numero(m.cantidad)}</td>
              <td>{numero(m.saldo)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Kardex({ p }: { p: Producto }) {
  const { datos, error } = useDatos(() => api.get<Movimiento[]>(`/movimientos?producto=${p.id}`), [p.id])
  return <>{!datos ? <Estado error={error} cargando /> : <ListaMovs movs={datos} />}</>
}

function FormMovimiento({ p, alTerminar }: { p: Producto; alTerminar: () => void }) {
  const [tipo, setTipo] = useState<'venta' | 'entrada' | 'salida' | 'ajuste'>('venta')
  const [cantidad, setCantidad] = useState('')
  const [nota, setNota] = useState('')
  const [ocupado, setOcupado] = useState(false)
  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    setOcupado(true)
    try {
      const r = await api.post<{ saldo: number }>('/movimientos', { tipo, producto_id: p.id, cantidad: Number(cantidad), nota })
      avisar(`Listo. Quedan ${numero(r.saldo)} ${p.unidad}`); alTerminar()
    } catch (er) { avisar(mensajeDe(er)) } finally { setOcupado(false) }
  }
  return (
    <form className="columna" onSubmit={enviar}>
      <p className="secundario" style={{ margin: 0 }}>Existencia actual: <b>{p.stock == null ? 'sin contar' : `${numero(p.stock)} ${p.unidad}`}</b></p>
      <div className="fila">
        {(['venta', 'entrada', 'salida', 'ajuste'] as const).map((k) => (
          <button type="button" key={k} className={`boton chico ${tipo === k ? '' : 'claro'}`} onClick={() => setTipo(k)}>{tipoMov[k]}</button>
        ))}
      </div>
      <label className="campo">{tipo === 'ajuste' ? '¿Cuántas contaste en total?' : 'Cantidad'}
        <input type="number" min={tipo === 'ajuste' ? 0 : 1} required value={cantidad} onChange={(e) => setCantidad(e.target.value)} autoFocus />
      </label>
      <label className="campo">Nota (opcional)<input value={nota} onChange={(e) => setNota(e.target.value)} placeholder={tipo === 'entrada' ? 'Factura, proveedor…' : tipo === 'salida' ? 'Merma, caducado, uso de la tienda…' : ''} /></label>
      <button className="boton ancho" disabled={ocupado}>Registrar</button>
    </form>
  )
}

type Linea = { p: Producto; cantidad: number }

/** Caja de súper: cada código escaneado (pistola = teclado + Enter, o cámara) suma una pieza. */
function Caja({ productos, alTerminar }: { productos: Producto[]; alTerminar: () => void }) {
  const [tipo, setTipo] = useState<'venta' | 'entrada' | 'salida'>('venta')
  const [codigo, setCodigo] = useState('')
  const [lineas, setLineas] = useState<Linea[]>([])
  const [error, setError] = useState<string | null>(null)
  const [camara, setCamara] = useState(false)
  const [ocupado, setOcupado] = useState(false)
  const entrada = useRef<HTMLInputElement>(null)

  const agregar = (cod: string) => {
    cod = cod.trim()
    if (!cod) return
    const p = productos.find((x) => x.codigo === cod) ?? productos.find((x) => x.nombre.toLowerCase() === cod.toLowerCase())
    if (!p) { setError(`No tienes ningún producto con el código «${cod}»`); return }
    const ya = lineas.find((l) => l.p.id === p.id)?.cantidad ?? 0
    if (tipo !== 'entrada' && p.stock != null && ya + 1 > p.stock) { setError(`Solo hay ${p.stock} de «${p.nombre}»`); return }
    setError(null)
    setLineas((ls) => {
      const i = ls.findIndex((l) => l.p.id === p.id)
      if (i < 0) return [{ p, cantidad: 1 }, ...ls]
      const n = [...ls]; n[i] = { ...n[i], cantidad: n[i].cantidad + 1 }; return n
    })
  }
  const escanear = (e: FormEvent) => { e.preventDefault(); agregar(codigo); setCodigo(''); entrada.current?.focus() }
  const cambiar = (id: number, d: number) => setLineas((ls) => ls.map((l) => (l.p.id === id ? { ...l, cantidad: Math.max(0, l.cantidad + d) } : l)).filter((l) => l.cantidad > 0))
  const piezas = lineas.reduce((s, l) => s + l.cantidad, 0)
  const total = lineas.reduce((s, l) => s + l.cantidad * l.p.precio_c, 0)

  const registrar = async () => {
    setOcupado(true)
    try {
      await api.post('/movimientos/lote', { tipo, items: lineas.map((l) => ({ producto_id: l.p.id, cantidad: l.cantidad })) })
      avisar(`${tipoMov[tipo]} registrada: ${piezas} pieza(s)`); alTerminar()
    } catch (er) { setError(mensajeDe(er)) } finally { setOcupado(false) }
  }

  return (
    <div className="columna">
      <div className="fila">
        {(['venta', 'entrada', 'salida'] as const).map((k) => (
          <button key={k} className={`boton chico ${tipo === k ? '' : 'claro'}`} onClick={() => setTipo(k)}>{tipoMov[k]}</button>
        ))}
      </div>
      <form className="fila" onSubmit={escanear}>
        <input ref={entrada} autoFocus style={{ flex: 1 }} value={codigo} onChange={(e) => setCodigo(e.target.value)}
          placeholder="Escanea o escribe el código y Enter" aria-label="Código de barras" inputMode="numeric" />
        <button type="button" className="boton claro" onClick={() => setCamara(!camara)}>📷</button>
      </form>
      {camara && <Camara alLeer={(c) => agregar(c)} />}
      {error && <div className="aviso error">{error}</div>}
      <div className="lista">
        {lineas.map((l) => (
          <div key={l.p.id} className="renglon">
            <div><b>{l.p.nombre}</b><div className="secundario chico">{l.p.codigo} · hay {l.p.stock ?? 0}</div></div>
            <div className="fila" style={{ gap: '.3rem' }}>
              <button className="boton claro chico" onClick={() => cambiar(l.p.id, -1)} aria-label="Menos">−</button>
              <b style={{ minWidth: 28, textAlign: 'center' }}>{l.cantidad}</b>
              <button className="boton claro chico" onClick={() => cambiar(l.p.id, 1)} aria-label="Más">+</button>
            </div>
          </div>
        ))}
        {lineas.length === 0 && <div className="vacio-lista chico">Escanea el primer producto. Cada lectura suma una pieza.</div>}
      </div>
      <div className="renglon"><span>{piezas} pieza(s)</span>{tipo === 'venta' && <b>{pesos(total)}</b>}</div>
      <button className="boton ancho" disabled={ocupado || lineas.length === 0} onClick={registrar}>Registrar {tipoMov[tipo].toLowerCase()}</button>
    </div>
  )
}

declare global { interface Window { BarcodeDetector?: new (o?: { formats: string[] }) => { detect: (v: HTMLVideoElement) => Promise<{ rawValue: string }[]> } } }

function Camara({ alLeer }: { alLeer: (codigo: string) => void }) {
  const video = useRef<HTMLVideoElement>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const leer = useRef(alLeer)
  leer.current = alLeer
  useEffect(() => {
    if (!window.BarcodeDetector) { setMsg('Este navegador no lee códigos con la cámara. Usa Chrome en Android, una pistola o escribe el código.'); return }
    let stream: MediaStream | null = null
    let vivo = true
    let ultimo = ''
    let cuando = 0
    const det = new window.BarcodeDetector({ formats: ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128', 'code_39', 'qr_code'] })
    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } }).then((s) => {
      stream = s
      if (!video.current) return
      video.current.srcObject = s
      video.current.play()
      const ciclo = async () => {
        if (!vivo || !video.current) return
        try {
          const r = await det.detect(video.current)
          const c = r[0]?.rawValue
          if (c && (c !== ultimo || Date.now() - cuando > 1500)) { ultimo = c; cuando = Date.now(); leer.current(c); navigator.vibrate?.(60) }
        } catch { /* cuadro sin código */ }
        setTimeout(ciclo, 250)
      }
      ciclo()
    }).catch(() => setMsg('No se pudo abrir la cámara.'))
    return () => { vivo = false; stream?.getTracks().forEach((t) => t.stop()) }
  }, [])
  return msg ? <div className="aviso chico">{msg}</div> : <video ref={video} className="camara" muted playsInline />
}

function FormProducto({ p, provs, prods, alTerminar }: { p: Producto; provs: Proveedor[]; prods: Productor[]; alTerminar: () => void }) {
  const [f, setF] = useState({
    ...p, precio: p.precio_c ? String(p.precio_c / 100) : '', stockTxt: p.stock == null ? '' : String(p.stock),
    mayoreo: p.precio_mayoreo_c ? String(p.precio_mayoreo_c / 100) : '',
  })
  const [ocupado, setOcupado] = useState(false)
  const guardar = async (e: FormEvent) => {
    e.preventDefault()
    setOcupado(true)
    const cuerpo = {
      ...f, precio_c: Math.round(Number(f.precio || 0) * 100), precio_mayoreo_c: Math.round(Number(f.mayoreo || 0) * 100),
      stock: f.stockTxt === '' ? null : Math.max(0, Number(f.stockTxt)), stock_min: Number(f.stock_min) || 0,
      minimo_mayoreo: Number(f.minimo_mayoreo) || 0,
    }
    try {
      if (p.id) await api.put(`/productos/${p.id}`, cuerpo); else await api.post('/productos', cuerpo)
      avisar(f.publicado && !p.publicado ? 'Guardado y publicado en tu vitrina de la red' : 'Producto guardado'); alTerminar()
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
        <label className="campo espacio">Existencia<input type="number" min={0} value={f.stockTxt} placeholder="sin contar" onChange={(e) => setF({ ...f, stockTxt: e.target.value })} /></label>
        <label className="campo espacio">Mínimo<input type="number" min={0} value={f.stock_min} onChange={(e) => setF({ ...f, stock_min: Number(e.target.value) })} /></label>
      </div>
      {p.id ? <p className="secundario chico" style={{ margin: 0 }}>Si cambias la existencia aquí queda como ajuste en el kárdex. Para ventas y entradas usa «Mover» o la caja.</p> : null}
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
      <fieldset className="vitrina-campos">
        <legend>Vitrina de la red</legend>
        <label className="fila" style={{ fontWeight: 600 }}>
          <input type="checkbox" style={{ width: 22, minHeight: 22 }} checked={f.publicado} onChange={(e) => setF({ ...f, publicado: e.target.checked })} />
          Ofrecerlo a otras tiendas de la red
        </label>
        {f.publicado && (
          <>
            <div className="fila">
              <label className="campo espacio">Precio mayoreo ($)<input type="number" min={0} step="0.5" value={f.mayoreo} onChange={(e) => setF({ ...f, mayoreo: e.target.value })} /></label>
              <label className="campo espacio">Desde (piezas)<input type="number" min={0} value={f.minimo_mayoreo} onChange={(e) => setF({ ...f, minimo_mayoreo: Number(e.target.value) })} /></label>
              <label className="campo espacio">Unidad<input value={f.unidad} onChange={(e) => setF({ ...f, unidad: e.target.value })} placeholder="pza, caja, kg" /></label>
            </div>
            <label className="campo">Descripción para otras tiendas<textarea value={f.descripcion} onChange={(e) => setF({ ...f, descripcion: e.target.value })} placeholder="Colores, tallas, tiempo de entrega…" /></label>
          </>
        )}
      </fieldset>
      <div className="fila">
        <button className="boton" disabled={ocupado}>Guardar</button>
        {p.id ? <button type="button" className="boton peligro" onClick={borrar}>Borrar</button> : null}
      </div>
    </form>
  )
}
