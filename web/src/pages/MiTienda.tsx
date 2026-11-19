import { useState, type FormEvent } from 'react'
import { api, type Tienda } from '../api'
import type { Sesion } from '../App'
import { Enlace, url } from '../router'
import { avisar, Mapa, mensajeDe, miUbicacion } from '../ui'

export default function MiTienda({ sesion }: { sesion: Sesion }) {
  const t = sesion.tienda
  const [f, setF] = useState({ nombre: t.nombre, telefono: t.telefono, direccion: t.direccion, password: '' })
  const [pos, setPos] = useState<{ lat: number; lng: number } | null>(t.lat != null && t.lng != null ? { lat: t.lat, lng: t.lng } : null)
  const [ocupado, setOcupado] = useState(false)
  const enlace = `${location.origin}${url(`/t/${t.slug}`)}`

  const guardar = async (e: FormEvent) => {
    e.preventDefault()
    setOcupado(true)
    try {
      await api.put<Tienda>('/yo', { ...f, lat: pos?.lat ?? null, lng: pos?.lng ?? null })
      setF({ ...f, password: '' }); avisar('Datos guardados'); sesion.refrescar()
    } catch (er) { avisar(mensajeDe(er)) } finally { setOcupado(false) }
  }
  const compartir = async () => {
    try {
      if (navigator.share) await navigator.share({ title: t.nombre, text: `Mira qué hay hoy en ${t.nombre}`, url: enlace })
      else { await navigator.clipboard.writeText(enlace); avisar('Enlace copiado') }
    } catch { /* el usuario cerró el menú */ }
  }

  return (
    <div className="columna" style={{ gap: '1.25rem', maxWidth: 760 }}>
      <h1>Mi tienda</h1>
      <div className="tarjeta columna">
        <h3 style={{ margin: 0 }}>Tu página para los vecinos</h3>
        <p className="secundario chico" style={{ margin: 0 }}>Muestra qué hay hoy según tu última foto y los productos de productores locales que vendes.</p>
        <div className="fila">
          <code style={{ wordBreak: 'break-all' }}>{enlace}</code>
        </div>
        <div className="fila">
          <Enlace href={`/t/${t.slug}`} className="boton claro chico">Ver mi página</Enlace>
          <button className="boton chico" onClick={compartir}>Compartir</button>
        </div>
      </div>
      <form className="tarjeta columna" onSubmit={guardar}>
        <label className="campo">Nombre<input required value={f.nombre} onChange={(e) => setF({ ...f, nombre: e.target.value })} /></label>
        <label className="campo">WhatsApp<input type="tel" value={f.telefono} onChange={(e) => setF({ ...f, telefono: e.target.value })} /></label>
        <label className="campo">Dirección<input value={f.direccion} onChange={(e) => setF({ ...f, direccion: e.target.value })} /></label>
        <div className="columna" style={{ gap: '.4rem' }}>
          <div className="renglon">
            <b className="chico">Ubicación (toca el mapa)</b>
            <button type="button" className="boton claro chico" onClick={() => miUbicacion().then(([lat, lng]) => setPos({ lat, lng })).catch((e) => avisar(e.message))}>
              📍 Usar mi ubicación
            </button>
          </div>
          <Mapa puntos={[]} centro={pos ? [pos.lat, pos.lng] : undefined} elegido={pos} alElegir={(lat, lng) => setPos({ lat, lng })} alto={280} />
          <span className="secundario chico">Sirve para sugerirte productores cercanos y para que los vecinos encuentren tu tienda.</span>
        </div>
        <label className="campo">Nueva contraseña (opcional)<input type="password" minLength={6} autoComplete="new-password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} /></label>
        <div className="fila">
          <button className="boton" disabled={ocupado}>Guardar</button>
          <button type="button" className="boton peligro" onClick={sesion.salir}>Cerrar sesión</button>
        </div>
      </form>
    </div>
  )
}
