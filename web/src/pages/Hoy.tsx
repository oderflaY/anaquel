import { useMemo, useState } from 'react'
import { api, guardarToken, type Faltante, type Pedido, type Sugerencia, type Tienda } from '../api'
import type { Sesion } from '../App'
import { Enlace } from '../router'
import { avisar, Estado, mensajeDe, Nivel, useDatos } from '../ui'

const clave = (s?: Sugerencia) => (s ? `${s.tipo}:${s.id}` : 'ninguno')

/** Abre WhatsApp en una pestaña reservada en el mismo clic (si no, el navegador la bloquea). */
export async function pedirPorWhatsApp(crear: () => Promise<Pedido>) {
  const ventana = window.open('', '_blank')
  try {
    const p = await crear()
    if (ventana) ventana.location.href = p.whatsapp
    else location.href = p.whatsapp
    return p
  } catch (e) {
    ventana?.close()
    throw e
  }
}

export default function Hoy({ sesion }: { sesion: Sesion }) {
  const { datos, error, recargar } = useDatos(() => api.get<Faltante[]>('/faltantes'))
  const r = sesion.resumen
  // A quién se le pide cada producto (por defecto, la primera sugerencia).
  const [eleccion, setEleccion] = useState<Record<number, string>>({})
  const [cantidad, setCantidad] = useState<Record<number, number>>({})
  const [ocupado, setOcupado] = useState<string | null>(null)

  const porPedir = (datos ?? []).filter((f) => !f.en_pedido)
  const yaPedidos = (datos ?? []).filter((f) => f.en_pedido)

  const grupos = useMemo(() => {
    const g = new Map<string, { sug?: Sugerencia; items: Faltante[] }>()
    for (const f of porPedir) {
      const k = eleccion[f.producto.id] ?? clave(f.sugerencias[0])
      const sug = f.sugerencias.find((s) => clave(s) === k)
      if (!g.has(k)) g.set(k, { sug, items: [] })
      g.get(k)!.items.push(f)
    }
    return [...g.entries()].sort(([a], [b]) => (a === 'ninguno' ? 1 : b === 'ninguno' ? -1 : 0))
  }, [porPedir, eleccion]) // eslint-disable-line react-hooks/exhaustive-deps

  const pedir = async (k: string, sug: Sugerencia, items: Faltante[]) => {
    setOcupado(k)
    try {
      await pedirPorWhatsApp(() => api.post<Pedido>('/pedidos', {
        [sug.tipo === 'proveedor' ? 'proveedor_id' : 'productor_id']: sug.id,
        items: items.map((f) => ({ producto_id: f.producto.id, cantidad: cantidad[f.producto.id] ?? f.sugerido })),
      }))
      avisar(`Pedido enviado a ${sug.nombre}`)
      recargar(); sesion.refrescar()
    } catch (e) { avisar(mensajeDe(e)) } finally { setOcupado(null) }
  }

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div>
        <h1 style={{ marginBottom: '.2rem' }}>Hoy en {sesion.tienda.nombre}</h1>
        <p className="secundario" style={{ margin: 0 }}>Lo que falta, a quién pedírselo y lo que viene en camino.</p>
        {sesion.tienda.email === 'demo@anaquel.mx' && (
          <button className="boton claro chico" style={{ marginTop: '.6rem' }} onClick={async () => {
            if (!confirm('¿Dejar la demo como nueva? Se borran los cambios que se le hayan hecho.')) return
            try {
              const r = await api.post<{ token: string; tienda: Tienda }>('/demo/reiniciar')
              guardarToken(r.token); setEleccion({}); setCantidad({}); recargar(); sesion.refrescar(); avisar('Demo reiniciada')
            } catch (e) { avisar(mensajeDe(e)) }
          }}>↺ Reiniciar demo</button>
        )}
      </div>

      {r && (
        <div className="cifras">
          <div className={`tarjeta cifra ${r.sin_pedir ? 'alerta' : ''}`}><b>{r.sin_pedir}</b>por pedir</div>
          <div className="tarjeta cifra"><b>{r.pedidos_abiertos}</b>pedidos en camino</div>
          <div className={`tarjeta cifra ${r.atrasados ? 'alerta' : ''}`}><b>{r.atrasados}</b>no han llegado (3+ días)</div>
          <div className="tarjeta cifra"><b>{r.anaqueles - r.sin_foto_hoy}/{r.anaqueles}</b>anaqueles con foto hoy</div>
        </div>
      )}

      {r && r.anaqueles === 0 && (
        <div className="aviso">Empieza por registrar tu primer anaquel: <Enlace href="/panel/anaqueles">toma la foto de tu anaquel lleno</Enlace>.</div>
      )}
      {r && r.anaqueles > 0 && r.sin_foto_hoy > 0 && (
        <div className="aviso">Te faltan {r.sin_foto_hoy} anaquel(es) por fotografiar hoy. <Enlace href="/panel/anaqueles">Tomar foto</Enlace></div>
      )}
      {r && r.atrasados > 0 && (
        <div className="aviso error">Tienes {r.atrasados} pedido(s) que no han llegado en 3 días o más. <Enlace href="/panel/pedidos">Revisar</Enlace></div>
      )}

      <section className="columna">
        <h2>Lista de resurtido</h2>
        <Estado error={error} cargando={!datos} />
        {datos && porPedir.length === 0 && (
          <div className="tarjeta vacio-lista">No falta nada por pedir. Toma la foto de hoy para revisar tus anaqueles.</div>
        )}
        {grupos.map(([k, { sug, items }]) => (
          <div key={k} className="tarjeta columna">
            <div className="renglon">
              <div>
                <h3 style={{ margin: 0 }}>{sug ? sug.nombre : 'Sin proveedor asignado'}</h3>
                <div className="secundario chico">
                  {sug ? (sug.tipo === 'productor'
                    ? `Productor local${sug.distancia_km != null ? ` · a ${sug.distancia_km} km` : ''}${sug.lugar ? ` · ${sug.lugar}` : ''}`
                    : 'Proveedor') : <>Agrega un proveedor en <Enlace href="/panel/proveedores">Proveedores</Enlace> o asigna uno al producto.</>}
                </div>
              </div>
              {sug && (
                <button className="boton wa" disabled={ocupado === k} onClick={() => pedir(k, sug, items)}>
                  {ocupado === k ? 'Enviando…' : `Pedir por WhatsApp (${items.length})`}
                </button>
              )}
            </div>
            <div className="lista">
              {items.map((f) => (
                <div key={f.producto.id} className="item-falta">
                  <div className="espacio" style={{ minWidth: 180 }}>
                    <b>{f.producto.nombre}</b> <Nivel nivel={f.nivel} />
                    <div className="secundario chico">{f.motivo}</div>
                  </div>
                  <div className="fila" style={{ flexWrap: 'nowrap' }}>
                    <input type="number" min={1} aria-label="Cantidad" style={{ width: 76 }} value={cantidad[f.producto.id] ?? f.sugerido}
                      onChange={(e) => setCantidad({ ...cantidad, [f.producto.id]: Math.max(1, Number(e.target.value) || 1) })} />
                    {f.sugerencias.length > 0 ? (
                      <select aria-label="Pedir a" style={{ width: 200 }} value={eleccion[f.producto.id] ?? clave(f.sugerencias[0])}
                        onChange={(e) => setEleccion({ ...eleccion, [f.producto.id]: e.target.value })}>
                        {f.sugerencias.map((s) => (
                          <option key={clave(s)} value={clave(s)}>
                            {s.tipo === 'productor' ? '🌱 ' : ''}{s.nombre}{s.distancia_km != null ? ` (${s.distancia_km} km)` : ''}
                          </option>
                        ))}
                      </select>
                    ) : <span className="secundario chico">Sin sugerencias</span>}
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </section>

      {yaPedidos.length > 0 && (
        <section className="columna">
          <h2>Ya pedidos</h2>
          <div className="tarjeta lista">
            {yaPedidos.map((f) => (
              <div key={f.producto.id} className="renglon">
                <span><b>{f.producto.nombre}</b> <Nivel nivel={f.nivel} /></span>
                <Enlace href="/panel/pedidos" className="chico">Pedido #{f.en_pedido}</Enlace>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}
