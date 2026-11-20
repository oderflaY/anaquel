import { api, type Pagina, type Publicacion, type Vendedor as V } from '../api'
import { Avatar, Cargando, EnlaceBoton, Esqueleto, EsqueletoRejilla, EstadoError, EstadoVacio, TarjetaProducto, useConsulta, useTitulo } from '../ui'

const GIROS: Record<string, string> = {
  abarrotes: 'Abarrotes', ropa: 'Ropa y accesorios', papeleria: 'Papelería', ferreteria: 'Ferretería', farmacia: 'Farmacia',
  dulceria: 'Dulcería', comida: 'Comida', mayoreo: 'Proveedor / mayoreo', productor: 'Productor local', servicios: 'Servicios', otro: 'Negocio local',
}

export default function Vendedor({ slug }: { slug: string }) {
  const perfil = useConsulta((s) => api.get<{ vendedor: V; publicaciones: number }>(`/mercado/vendedores/${slug}`, s), [slug])
  const pubs = useConsulta((s) => api.get<Pagina<Publicacion>>(`/mercado/publicaciones?vendedor=${encodeURIComponent(slug)}&limite=48`, s), [slug])
  useTitulo(perfil.datos?.vendedor.nombre ?? 'Vendedor')

  if (perfil.error) return <EstadoError error={perfil.error} reintentar={perfil.recargar} />
  const v = perfil.datos?.vendedor
  return (
    <div className="m-pila" style={{ gap: 'var(--e-6)' }}>
      {!v ? (
        <Cargando texto="Cargando vendedor…">
          <div className="m-card m-fila"><Esqueleto className="m-esqueleto--circulo" style={{ width: 72, height: 72 }} /><div className="m-pila-chica" style={{ flex: 1 }}><Esqueleto className="m-esqueleto--titulo" style={{ width: '40%' }} /><Esqueleto className="m-esqueleto--texto" style={{ width: '60%' }} /></div></div>
        </Cargando>
      ) : (
        <section className="m-card m-fila" style={{ alignItems: 'flex-start' }} aria-labelledby="t-vendedor">
          <Avatar nombre={v.nombre} grande />
          <div className="m-pila-chica" style={{ flex: 1, minWidth: 220 }}>
            <h1 id="t-vendedor">{v.nombre}</h1>
            <p className="m-terciario">{GIROS[v.giro] ?? 'Negocio local'} · {v.colonia} · {perfil.datos!.publicaciones} {perfil.datos!.publicaciones === 1 ? 'producto' : 'productos'}</p>
            {v.descripcion && <p className="m-secundario">{v.descripcion}</p>}
          </div>
          {v.tienda_slug && <EnlaceBoton href={`/t/${v.tienda_slug}`} variante="secundario" icono="tienda" chico>Su tienda en Anaquel</EnlaceBoton>}
        </section>
      )}
      <section className="m-pila" aria-labelledby="t-productos">
        <h2 id="t-productos">Productos</h2>
        {pubs.cargando ? <EsqueletoRejilla n={4} />
          : pubs.error ? <EstadoError error={pubs.error} reintentar={pubs.recargar} />
          : !pubs.datos?.items.length ? <EstadoVacio icono="etiqueta" titulo="Este negocio aún no publica productos" />
          : <div className="m-rejilla">{pubs.datos.items.map((p) => <TarjetaProducto key={p.id} p={p} />)}</div>}
      </section>
    </div>
  )
}
