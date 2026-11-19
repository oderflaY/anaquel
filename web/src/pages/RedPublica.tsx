import { useState, type FormEvent } from 'react'
import { api, type Resultado } from '../api'
import { TarjetaRed } from '../red'
import { Enlace, ir } from '../router'
import { Estado, useDatos } from '../ui'

/** La red vista por cualquier persona: qué venden, anuncian y buscan las tiendas de la ciudad. */
export default function RedPublica({ consulta }: { consulta: string }) {
  const q = new URLSearchParams(consulta).get('q') ?? ''
  const [texto, setTexto] = useState(q)
  const { datos, error } = useDatos(() => api.get<Resultado[]>(`/publico/red?q=${encodeURIComponent(q)}`), [q])
  const buscar = (e: FormEvent) => { e.preventDefault(); ir(`/red${texto ? `?q=${encodeURIComponent(texto)}` : ''}`) }
  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div>
        <h1 style={{ marginBottom: '.2rem' }}>Comercio local en la red Anaquel</h1>
        <p className="secundario" style={{ margin: 0 }}>Tiendas, proveedores, servicios y productores de la ciudad. Todos venden, todos compran, también al mayoreo.</p>
      </div>
      <form className="fila" onSubmit={buscar}>
        <input style={{ flex: 1, minWidth: 200 }} value={texto} onChange={(e) => setTexto(e.target.value)} placeholder="Gorras, chamarras, bordado, flete…" aria-label="Buscar en la red" />
        <button className="boton">Buscar</button>
      </form>
      <div className="aviso chico">¿Tienes un negocio? <Enlace href="/entrar">Registra tu tienda</Enlace> para negociar, publicar tu inventario y tener tu asesor.</div>
      <Estado error={error} cargando={!datos} />
      {datos && (
        <div className="rejilla">
          {datos.map((r) => <TarjetaRed key={r.tipo + r.id} r={r} />)}
          {datos.length === 0 && <div className="tarjeta vacio-lista" style={{ gridColumn: '1/-1' }}>Sin resultados.</div>}
        </div>
      )}
    </div>
  )
}
