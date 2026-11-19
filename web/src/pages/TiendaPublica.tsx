import { useState } from 'react'
import { api, hace, nombreGiro, pesos, type TiendaPublica as TP } from '../api'
import { TarjetaRed } from '../red'
import { Estado, Mapa, Nivel, useDatos } from '../ui'

export default function TiendaPublica({ slug }: { slug: string }) {
  const { datos, error } = useDatos(() => api.get<TP>(`/publico/tiendas/${encodeURIComponent(slug)}`), [slug])
  const [buscar, setBuscar] = useState('')
  if (!datos) return <Estado error={error} cargando />
  const t = datos.tienda
  const lista = datos.productos.filter((p) => (p.nombre + ' ' + p.categoria).toLowerCase().includes(buscar.toLowerCase()))
  const wa = t.telefono ? `https://wa.me/${t.telefono.replace(/\D/g, '').replace(/^(\d{10})$/, '52$1')}` : null

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div>
        <h1 style={{ marginBottom: '.2rem' }}>{t.nombre}</h1>
        <p className="secundario" style={{ margin: 0 }}>{nombreGiro(t.giro)}{t.direccion ? ` · ${t.direccion}` : ''}</p>
        {t.descripcion && <p style={{ margin: '.3rem 0 0' }}>{t.descripcion}</p>}
        {datos.actualizado && <p className="secundario chico">Actualizado {hace(datos.actualizado)} con la foto de sus anaqueles.</p>}
        {wa && <a className="boton wa chico" href={wa} target="_blank" rel="noreferrer">Escribir por WhatsApp</a>}
      </div>

      {(datos.vitrina?.length ?? 0) + (datos.anuncios?.length ?? 0) > 0 && (
        <section className="columna">
          <h2 style={{ margin: 0 }}>Para otros negocios</h2>
          <div className="rejilla">
            {(datos.vitrina ?? []).map((r) => <TarjetaRed key={'p' + r.id} r={r} />)}
            {(datos.anuncios ?? []).map((a) => (
              <TarjetaRed key={'a' + a.id} r={{ tipo: 'anuncio', subtipo: a.tipo, id: a.id, titulo: a.titulo, detalle: a.texto, precio: a.precio, creado: a.creado, telefono: t.telefono }} />
            ))}
          </div>
        </section>
      )}

      {datos.hecho_cerca.length > 0 && (
        <section className="tarjeta columna" style={{ borderTop: '4px solid var(--marca)' }}>
          <h2 style={{ margin: 0 }}>🌱 Hecho aquí cerca</h2>
          <div className="lista">
            {datos.hecho_cerca.map((p) => (
              <div key={p.nombre} className="renglon">
                <div><b>{p.nombre}</b><div className="secundario chico">De {p.productor}{p.lugar ? `, ${p.lugar}` : ''}</div></div>
                <Nivel nivel={p.estado} />
              </div>
            ))}
          </div>
        </section>
      )}

      <section className="columna">
        <h2 style={{ margin: 0 }}>¿Qué hay hoy?</h2>
        <input placeholder="Buscar producto…" value={buscar} onChange={(e) => setBuscar(e.target.value)} aria-label="Buscar producto" />
        <div className="tarjeta lista">
          {lista.map((p) => (
            <div key={p.nombre} className="renglon">
              <div><b>{p.nombre}</b><div className="secundario chico">{p.categoria}{p.precio_c ? ` · ${pesos(p.precio_c)}` : ''}</div></div>
              <div className="fila">
                {p.pedido && <span className="chip enviado">Ya viene en camino</span>}
                <Nivel nivel={p.estado} />
              </div>
            </div>
          ))}
          {lista.length === 0 && <div className="vacio-lista">Sin resultados.</div>}
        </div>
      </section>

      {t.lat != null && t.lng != null && (
        <Mapa puntos={[{ lat: t.lat, lng: t.lng, titulo: t.nombre, detalle: t.direccion }]} centro={[t.lat, t.lng]} alto={260} />
      )}
    </div>
  )
}
