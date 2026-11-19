import { useMemo, useState, type FormEvent } from 'react'
import { api, type Productor, type TiendaCercana } from '../api'
import { url } from '../router'
import { avisar, Estado, Mapa, mensajeDe, miUbicacion, useDatos, type Punto } from '../ui'

export default function Productores() {
  const [centro, setCentro] = useState<[number, number] | undefined>()
  const [q, setQ] = useState('')
  const consulta = `${centro ? `lat=${centro[0]}&lng=${centro[1]}&` : ''}q=${encodeURIComponent(q)}`
  const { datos, error, recargar } = useDatos(() => api.get<Productor[]>(`/publico/productores?${consulta}`), [consulta])
  const { datos: tiendas } = useDatos(() => api.get<TiendaCercana[]>('/publico/tiendas'))
  const [registrar, setRegistrar] = useState(false)

  const puntos = useMemo<Punto[]>(() => [
    ...(datos ?? []).map((p) => ({ lat: p.lat, lng: p.lng, titulo: `🌱 ${p.nombre}`, detalle: p.productos })),
    ...(tiendas ?? []).map((t) => ({ lat: t.lat, lng: t.lng, titulo: `🏪 ${t.nombre}`, detalle: t.direccion, color: '#2f5da8', enlace: url(`/t/${t.slug}`) })),
  ], [datos, tiendas])

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div className="renglon">
        <div>
          <h1 style={{ marginBottom: '.2rem' }}>Productores de aquí</h1>
          <p className="secundario" style={{ margin: 0 }}>Huevo, queso, tortilla, miel… lo que se produce cerca, y las tiendas que lo venden.</p>
        </div>
        <button className="boton" onClick={() => setRegistrar(!registrar)}>{registrar ? 'Ver directorio' : 'Soy productor: registrarme'}</button>
      </div>

      {registrar ? <Registro alTerminar={() => { setRegistrar(false); recargar() }} /> : (
        <>
          <div className="fila">
            <input style={{ flex: 1, minWidth: 200 }} placeholder="¿Qué buscas? (huevo, queso, miel…)" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Buscar" />
            <button className="boton claro" onClick={() => miUbicacion().then(setCentro).catch((e) => avisar(e.message))}>📍 Cerca de mí</button>
          </div>
          <Mapa puntos={puntos} centro={centro} />
          <span className="secundario chico">🟢 Productores · 🔵 Tiendas con Anaquel</span>
          <Estado error={error} cargando={!datos} />
          <div className="rejilla">
            {datos?.map((p) => (
              <div key={p.id} className="tarjeta columna" style={{ gap: '.35rem' }}>
                <b>🌱 {p.nombre}</b>
                <span className="chico">{p.productos}</span>
                {p.descripcion && <span className="secundario chico">{p.descripcion}</span>}
                <span className="secundario chico">{p.lugar}{p.distancia_km != null ? ` · a ${p.distancia_km} km` : ''}</span>
                <a className="boton wa chico" style={{ alignSelf: 'flex-start' }} target="_blank" rel="noreferrer"
                  href={`https://wa.me/${p.telefono.replace(/\D/g, '').replace(/^(\d{10})$/, '52$1')}?text=${encodeURIComponent(`Hola ${p.nombre}, te encontré en Anaquel. ¿Tienes ${p.productos.split(',')[0].trim()}?`)}`}>
                  WhatsApp
                </a>
              </div>
            ))}
            {datos?.length === 0 && <div className="tarjeta vacio-lista">No hay productores con esa búsqueda.</div>}
          </div>
        </>
      )}
    </div>
  )
}

function Registro({ alTerminar }: { alTerminar: () => void }) {
  const [f, setF] = useState({ nombre: '', telefono: '', productos: '', descripcion: '', lugar: '' })
  const [pos, setPos] = useState<{ lat: number; lng: number } | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    if (!pos) { setError('Toca el mapa para marcar dónde estás'); return }
    setOcupado(true); setError(null)
    try {
      await api.post('/publico/productores', { ...f, ...pos })
      avisar('¡Listo! Las tiendas de tu zona ya pueden encontrarte.')
      alTerminar()
    } catch (er) { setError(mensajeDe(er)) } finally { setOcupado(false) }
  }
  const c = (k: keyof typeof f) => ({ value: f[k], onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setF({ ...f, [k]: e.target.value }) })
  return (
    <form className="tarjeta columna" onSubmit={enviar} style={{ maxWidth: 760 }}>
      <p className="secundario" style={{ margin: 0 }}>Cuando a una tienda cerca de ti se le acabe lo que produces, Anaquel le sugerirá comprarte a ti.</p>
      <label className="campo">Tu nombre o el de tu rancho/taller<input required {...c('nombre')} /></label>
      <label className="campo">WhatsApp (10 dígitos)<input type="tel" required {...c('telefono')} placeholder="618 000 0000" /></label>
      <label className="campo">¿Qué produces? (separa con comas)<input required {...c('productos')} placeholder="huevo, queso fresco, nopal" /></label>
      <label className="campo">Colonia, rancho o municipio<input {...c('lugar')} /></label>
      <label className="campo">Descripción (opcional)<textarea {...c('descripcion')} placeholder="Entregas, cantidades, horarios…" /></label>
      <div className="renglon">
        <b className="chico">¿Dónde estás? (toca el mapa)</b>
        <button type="button" className="boton claro chico" onClick={() => miUbicacion().then(([lat, lng]) => setPos({ lat, lng })).catch((er) => avisar(er.message))}>📍 Mi ubicación</button>
      </div>
      <Mapa puntos={[]} centro={pos ? [pos.lat, pos.lng] : undefined} elegido={pos} alElegir={(lat, lng) => setPos({ lat, lng })} alto={280} />
      {error && <div className="aviso error">{error}</div>}
      <button className="boton" disabled={ocupado}>{ocupado ? 'Registrando…' : 'Registrarme'}</button>
    </form>
  )
}
