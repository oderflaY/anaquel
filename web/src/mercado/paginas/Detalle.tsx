import { useState } from 'react'
import type { SesionMercado } from '../Mercado'
import { Enlace, ir } from '../../router'
import { api, cantidadTxt, ErrorMercado, pesos, unidad, urlMedia, type Publicacion, type Vendedor } from '../api'
import { Icono } from '../iconos'
import { Alerta, Avatar, Boton, BotonIcono, Cargando, EnlaceBoton, Esqueleto, EstadoError, Existencias, Insignia, MediaProducto, mensajeDe, Modal, useConsulta, useTitulo, avisar } from '../ui'

type Resp = { publicacion: Publicacion; vendedor: Vendedor }

export default function Detalle({ id }: { id: number; sesion: SesionMercado }) {
  const { datos, error, cargando, recargar } = useConsulta((s) => api.get<Resp>(`/mercado/publicaciones/${id}`, s), [id])
  useTitulo(datos?.publicacion.titulo ?? 'Producto')
  if (cargando) return <EsqueletoDetalle />
  if (error || !datos) return <EstadoError error={error ?? 'No encontramos este producto.'} reintentar={recargar} />
  return <Contenido p={datos.publicacion} v={datos.vendedor} recargar={recargar} />
}

function Contenido({ p, v, recargar }: { p: Publicacion; v: Vendedor; recargar: () => void }) {
  const fotos = p.fotos ?? []
  const [actual, setActual] = useState(0)
  const [cantidad, setCantidad] = useState(Math.max(1, p.minimo))
  const [nota, setNota] = useState('')
  const [confirmar, setConfirmar] = useState(false)
  const [ocupado, setOcupado] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const max = p.existencias ?? 100000
  const agotado = p.existencias === 0
  const valida = cantidad >= p.minimo && cantidad <= max
  const total = cantidad * p.precio_c

  const apartar = async () => {
    setOcupado(true); setError(null)
    try {
      const r = await api.post<{ id: number }>('/mercado/ordenes', { publicacion_id: p.id, cantidad, nota })
      avisar('Listo, lo apartaste. Paga con tarjeta al recogerlo.')
      ir(`/mercado/ordenes/${r.id}`)
    } catch (e) {
      setError(mensajeDe(e))
      if (e instanceof ErrorMercado && (e.clave === 'sin_existencias' || e.clave === 'agotado')) recargar()
    } finally { setOcupado(false) }
  }

  return (
    <div className="m-pila" style={{ gap: 'var(--e-6)' }}>
      <nav className="m-migas" aria-label="Ruta">
        <Enlace href="/mercado">Explorar</Enlace><Icono nombre="adelante" className="m-icono" />
        <Enlace href={`/mercado/v/${v.slug}`}>{v.nombre}</Enlace>
      </nav>
      <div className="m-detalle">
        <div className="m-galeria">
          <div className="m-galeria-principal m-media">
            <MediaProducto foto={fotos[actual] ?? null} categoria={p.categoria} />
          </div>
          {fotos.length > 1 && (
            <div className="m-galeria-miniaturas" role="group" aria-label="Fotos">
              {fotos.map((f, i) => (
                <button key={f.url} type="button" aria-current={i === actual} aria-label={`Ver foto ${i + 1} de ${fotos.length}`} onClick={() => setActual(i)}>
                  <img src={urlMedia(f.url)} alt="" loading="lazy" />
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="m-panel-compra m-pila">
          <div className="m-pila-chica">
            <div className="m-fila"><Existencias p={p} />{p.estado === 'pausada' && <Insignia tono="aviso">Pausada</Insignia>}</div>
            <h1>{p.titulo}</h1>
            <div className="m-precio-grande">{pesos(p.precio_c)} <span className="m-terciario" style={{ fontSize: 'var(--texto-16)', fontWeight: 400 }}>por {unidad(p.unidad)}</span></div>
            {p.minimo > 1 && <p className="m-secundario">Compra mínima: {cantidadTxt(p.minimo, p.unidad)}</p>}
            {p.existencias !== null && p.existencias > 0 && <p className="m-terciario">{cantidadTxt(p.existencias, p.unidad)} disponibles</p>}
          </div>

          {p.es_mia ? (
            <div className="m-card m-pila">
              <Alerta>Esta es tu publicación. Así la ven los compradores.</Alerta>
              <EnlaceBoton href={`/mercado/vender/editar/${p.id}`} variante="secundario" icono="editar">Editar publicación</EnlaceBoton>
            </div>
          ) : agotado ? (
            <Alerta tipo="aviso">Se agotó. Revisa otros vendedores o vuelve después.</Alerta>
          ) : (
            <div className="m-card m-pila">
              <div className="m-fila m-entre">
                <span id="t-cantidad" className="m-chico" style={{ fontWeight: 600 }}>Cantidad</span>
                <div className="m-cantidad" role="group" aria-labelledby="t-cantidad">
                  <BotonIcono etiqueta="Quitar uno" icono="menos" disabled={cantidad <= p.minimo} onClick={() => setCantidad(Math.max(p.minimo, cantidad - 1))} />
                  <input type="number" inputMode="numeric" aria-labelledby="t-cantidad" min={p.minimo} max={max} value={cantidad}
                    onChange={(e) => setCantidad(Math.max(0, Math.floor(Number(e.target.value) || 0)))} />
                  <BotonIcono etiqueta="Agregar uno" icono="mas" disabled={cantidad >= max} onClick={() => setCantidad(Math.min(max, cantidad + 1))} />
                </div>
              </div>
              {!valida && <p className="m-campo-error" role="alert"><Icono nombre="alerta" />{cantidad < p.minimo ? `El mínimo es ${cantidadTxt(p.minimo, p.unidad)}` : `Solo hay ${cantidadTxt(max, p.unidad)}`}</p>}
              <div className="m-fila m-entre"><span className="m-secundario">Total</span><b style={{ fontSize: 'var(--texto-20)' }}>{pesos(total)}</b></div>
              <Boton ancho icono="check" disabled={!valida} onClick={() => setConfirmar(true)}>Apartar</Boton>
              <p className="m-terciario">Pagas con tarjeta en la terminal del vendedor al recogerlo.</p>
            </div>
          )}

          {p.descripcion && <section className="m-pila-chica"><h2 style={{ fontSize: 'var(--texto-16)' }}>Descripción</h2><p className="m-secundario" style={{ whiteSpace: 'pre-line' }}>{p.descripcion}</p></section>}

          <section className="m-card m-pila" aria-label="Vendedor">
            <div className="m-vendedor">
              <Avatar nombre={v.nombre} />
              <div className="m-renglon-cuerpo"><b>{v.nombre}</b><span className="m-terciario">{v.colonia}{p.distancia_km != null && ` · a ${p.distancia_km.toLocaleString('es-MX')} km`}</span></div>
            </div>
            <div className="m-fila">
              <EnlaceBoton href={`/mercado/v/${v.slug}`} variante="secundario" chico>Ver sus productos</EnlaceBoton>
              {v.tienda_slug && <EnlaceBoton href={`/t/${v.tienda_slug}`} variante="fantasma" chico icono="tienda">Su tienda en Anaquel</EnlaceBoton>}
            </div>
          </section>
        </div>
      </div>

      <Modal abierto={confirmar} onCerrar={() => !ocupado && setConfirmar(false)} titulo="¿Apartar este producto?">
        <div className="m-renglon" style={{ padding: 0, minHeight: 0 }}>
          <div className="m-miniatura m-media"><MediaProducto foto={p.foto} categoria={p.categoria} conAlt={false} /></div>
          <div className="m-renglon-cuerpo"><b>{p.titulo}</b><span className="m-terciario">{cantidadTxt(cantidad, p.unidad)} · {v.nombre}</span></div>
        </div>
        <div className="m-fila m-entre"><span className="m-secundario">Total a pagar al recoger</span><b style={{ fontSize: 'var(--texto-20)' }}>{pesos(total)}</b></div>
        <label className="m-campo">
          <span>Nota para el vendedor <span className="m-terciario">(opcional)</span></span>
          <textarea maxLength={300} value={nota} onChange={(e) => setNota(e.target.value)} placeholder="Ej. paso el jueves en la tarde" style={{ minHeight: 80 }} />
        </label>
        {error && <Alerta tipo="error">{error}</Alerta>}
        <div className="m-fila" style={{ justifyContent: 'flex-end' }}>
          <Boton variante="secundario" disabled={ocupado} onClick={() => setConfirmar(false)}>Cancelar</Boton>
          <Boton cargando={ocupado} onClick={apartar}>Confirmar</Boton>
        </div>
      </Modal>
    </div>
  )
}

function EsqueletoDetalle() {
  return (
    <Cargando texto="Cargando producto…">
      <div className="m-detalle">
        <Esqueleto style={{ aspectRatio: '1 / 1', borderRadius: 16 }} />
        <div className="m-pila">
          <Esqueleto className="m-esqueleto--titulo" style={{ width: '80%', height: 32 }} />
          <Esqueleto style={{ width: '40%', height: 40 }} />
          <Esqueleto style={{ height: 180, borderRadius: 16 }} />
          <Esqueleto className="m-esqueleto--texto" />
          <Esqueleto className="m-esqueleto--texto" style={{ width: '70%' }} />
        </div>
      </div>
    </Cargando>
  )
}
