// Piezas de la red de comercio local que se usan en varias páginas.
import { useEffect, useState, type FormEvent } from 'react'
import { api, nombreGiro, numero, pesos, whatsapp, type Producto, type Resultado, type Trato } from './api'
import { ir } from './router'
import { avisar, Dialogo, mensajeDe } from './ui'

const etiquetaTipo: Record<string, string> = {
  producto: 'En vitrina', anuncio: 'Anuncio', solicitud: 'Busca', productor: '🌱 Productor',
  servicio: 'Servicio', promocion: 'Promoción',
}

/** Tarjeta de un resultado de la red. Sin `onNegociar` (visitantes) solo muestra contacto. */
export function TarjetaRed({ r, onNegociar, compacta }: { r: Resultado; onNegociar?: (r: Resultado) => void; compacta?: boolean }) {
  const tipo = r.tipo === 'anuncio' ? (r.subtipo ?? 'anuncio') : r.tipo
  const tel = r.tienda?.telefono ?? r.telefono ?? ''
  const wa = whatsapp(tel, `Hola, te encontré en Anaquel. Me interesa: ${r.titulo}`)
  return (
    <div className={`tarjeta tarjeta-red t-${tipo}`}>
      <div className="fila entre" style={{ gap: '.4rem' }}>
        <span className={`chip red-${tipo}`}>{etiquetaTipo[tipo] ?? tipo}</span>
        {r.distancia_km != null && <span className="secundario chico">a {r.distancia_km} km</span>}
      </div>
      <b className="titulo-red">{r.tipo === 'solicitud' ? `${r.cantidad ? numero(r.cantidad) + ' ' : ''}${r.titulo}` : r.titulo}</b>
      {r.tienda && <span className="secundario chico">{r.tienda.nombre} · {nombreGiro(r.tienda.giro)}</span>}
      {r.tipo === 'productor' && r.lugar && <span className="secundario chico">{r.lugar}</span>}
      {!compacta && r.detalle && <span className="chico detalle-red">{r.detalle}</span>}
      {r.tipo === 'producto' && (
        <div className="precios">
          {r.disponible != null && <span><b>{numero(r.disponible)}</b> {r.unidad} disponibles</span>}
          {r.precio_mayoreo_c ? (
            <span><b>{pesos(r.precio_mayoreo_c)}</b> mayoreo{r.minimo_mayoreo ? ` desde ${numero(r.minimo_mayoreo)}` : ''}</span>
          ) : null}
          {r.precio_c ? <span className="secundario">{pesos(r.precio_c)} pieza suelta</span> : null}
        </div>
      )}
      {r.tipo === 'anuncio' && r.precio && <span className="precios"><b>{r.precio}</b></span>}
      <div className="fila" style={{ marginTop: 'auto', paddingTop: '.3rem' }}>
        {onNegociar && r.tipo !== 'productor' && (
          <button className="boton chico" onClick={() => onNegociar(r)}>
            {r.tipo === 'solicitud' ? 'Ofrecer' : r.tipo === 'anuncio' && r.subtipo === 'servicio' ? 'Contratar' : 'Negociar'}
          </button>
        )}
        {wa && <a className="boton wa chico" href={wa} target="_blank" rel="noreferrer">WhatsApp</a>}
        {r.tienda && <a className="boton claro chico" href={`${import.meta.env.BASE_URL}t/${r.tienda.slug}`}>Ver tienda</a>}
      </div>
    </div>
  )
}

/** Diálogo para abrir un trato: comprar un producto/servicio u ofrecer a una solicitud. */
export function DialogoNegociar({ r, onCerrar }: { r: Resultado | null; onCerrar: () => void }) {
  return (
    <Dialogo abierto={!!r} onCerrar={onCerrar}
      titulo={r?.tipo === 'solicitud' ? `Ofrecer a ${r.tienda?.nombre ?? ''}` : `Negociar con ${r?.tienda?.nombre ?? ''}`}>
      {r && <FormTrato r={r} onCerrar={onCerrar} />}
    </Dialogo>
  )
}

function FormTrato({ r, onCerrar }: { r: Resultado; onCerrar: () => void }) {
  const ofrezco = r.tipo === 'solicitud'
  const inicial = ofrezco ? r.cantidad || 1 : Math.max(r.minimo_mayoreo || 1, 1)
  const [cantidad, setCantidad] = useState(String(inicial))
  const precioDe = (n: number) =>
    (r.precio_mayoreo_c && n >= (r.minimo_mayoreo ?? 0) ? r.precio_mayoreo_c : r.precio_c) ?? 0
  const [precio, setPrecio] = useState(String((precioDe(inicial) || 0) / 100 || ''))
  const [mensaje, setMensaje] = useState('')
  const [mios, setMios] = useState<Producto[] | null>(null)
  const [miProducto, setMiProducto] = useState<number | ''>('')
  const [ocupado, setOcupado] = useState(false)

  useEffect(() => {
    if (!ofrezco) return
    api.get<Producto[]>('/productos').then((ps) => {
      setMios(ps)
      const q = r.titulo.toLowerCase().replace(/s\b/g, '')
      const p = ps.find((x) => x.nombre.toLowerCase().includes(q)) ?? ps.find((x) => x.publicado)
      if (p) { setMiProducto(p.id); if (p.precio_mayoreo_c || p.precio_c) setPrecio(String((p.precio_mayoreo_c || p.precio_c) / 100)) }
    }).catch(() => setMios([]))
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const n = Math.max(1, Number(cantidad) || 0)
  const pc = Math.round(Number(precio || 0) * 100)
  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    setOcupado(true)
    try {
      const cuerpo: Record<string, unknown> = { cantidad: n, precio_c: pc, mensaje }
      if (r.tipo === 'producto') cuerpo.producto_id = r.id
      else if (r.tipo === 'anuncio') cuerpo.anuncio_id = r.id
      else { cuerpo.solicitud_id = r.id; if (miProducto) cuerpo.mi_producto_id = miProducto }
      const t = await api.post<Trato>('/tratos', cuerpo)
      avisar(ofrezco ? 'Oferta enviada' : 'Propuesta enviada. Te avisamos cuando responda.')
      onCerrar()
      ir(`/panel/tratos/${t.id}`)
    } catch (er) { avisar(mensajeDe(er)) } finally { setOcupado(false) }
  }

  return (
    <form className="columna" onSubmit={enviar}>
      <div className="aviso chico">
        <b>{r.titulo}</b>
        {r.tipo === 'producto' && <> · hay {numero(r.disponible ?? 0)} {r.unidad}{r.precio_mayoreo_c ? ` · mayoreo ${pesos(r.precio_mayoreo_c)} desde ${numero(r.minimo_mayoreo ?? 0)}` : ''}</>}
        {r.tipo === 'solicitud' && r.detalle && <div className="secundario">{r.detalle}</div>}
        {r.tipo === 'anuncio' && r.precio && <> · {r.precio}</>}
      </div>
      {ofrezco && (
        <label className="campo">¿Qué producto tuyo le ofreces?
          <select value={miProducto} onChange={(e) => setMiProducto(e.target.value ? Number(e.target.value) : '')}>
            <option value="">— Ninguno de mi inventario —</option>
            {(mios ?? []).map((p) => <option key={p.id} value={p.id}>{p.nombre}{p.stock != null ? ` (tienes ${p.stock})` : ''}</option>)}
          </select>
        </label>
      )}
      <div className="fila">
        <label className="campo espacio">Cantidad
          <input type="number" min={1} required value={cantidad} onChange={(e) => {
            setCantidad(e.target.value)
            if (!ofrezco && r.tipo === 'producto') { const p = precioDe(Number(e.target.value) || 0); if (p) setPrecio(String(p / 100)) }
          }} />
        </label>
        <label className="campo espacio">Precio por pieza ($)
          <input type="number" min={0} step="0.5" value={precio} onChange={(e) => setPrecio(e.target.value)} />
        </label>
      </div>
      <div className="renglon"><span className="secundario">Total</span><b>{pesos(n * pc)}</b></div>
      <label className="campo">Mensaje
        <textarea value={mensaje} onChange={(e) => setMensaje(e.target.value)}
          placeholder={ofrezco ? 'Te las puedo surtir esta semana…' : '¿Me lo dejas en este precio? ¿Cuándo me lo entregas?'} />
      </label>
      <button className="boton ancho" disabled={ocupado}>{ocupado ? 'Enviando…' : ofrezco ? 'Enviar oferta' : 'Enviar propuesta'}</button>
      <p className="secundario chico" style={{ margin: 0 }}>La otra tienda puede aceptar o contraofertar. Al entregarse, Anaquel mueve el inventario de las dos.</p>
    </form>
  )
}
