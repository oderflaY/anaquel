import { useRef, useState } from 'react'
import { api, formFoto, hace, media, type Anaquel } from '../api'
import type { Sesion } from '../App'
import { Enlace, ir } from '../router'
import { avisar, Estado, mensajeDe, useDatos } from '../ui'

export default function Anaqueles({ sesion }: { sesion: Sesion }) {
  const { datos, error } = useDatos(() => api.get<Anaquel[]>('/anaqueles'))
  const [nombre, setNombre] = useState('')
  const [subiendo, setSubiendo] = useState(false)
  const archivo = useRef<HTMLInputElement>(null)

  const crear = async (f: File) => {
    setSubiendo(true)
    try {
      const a = await api.post<Anaquel>('/anaqueles', await formFoto(f, { nombre: nombre || `Anaquel ${(datos?.length ?? 0) + 1}` }))
      sesion.refrescar()
      avisar('Listo. Ahora marca dónde va cada producto.')
      ir(`/panel/anaqueles/${a.id}?editar=1`)
    } catch (e) { avisar(mensajeDe(e)) } finally { setSubiendo(false) }
  }

  return (
    <div className="columna" style={{ gap: '1.25rem' }}>
      <div>
        <h1 style={{ marginBottom: '.2rem' }}>Anaqueles</h1>
        <p className="secundario" style={{ margin: 0 }}>Cada anaquel tiene una foto de referencia (lleno) y las zonas donde va cada producto.</p>
      </div>
      <Estado error={error} cargando={!datos} />
      <div className="rejilla">
        {datos?.map((a) => (
          <Enlace key={a.id} href={`/panel/anaqueles/${a.id}`} className="tarjeta columna" style={{ textDecoration: 'none', color: 'inherit', gap: '.6rem' }}>
            <img src={media(a.ultima_foto ?? a.foto_ref)} alt="" style={{ borderRadius: 10, aspectRatio: '4/3', objectFit: 'cover' }} />
            <div className="renglon">
              <b>{a.nombre}</b>
              {a.faltantes > 0 ? <span className="chip vacio">{a.faltantes} con hueco</span> : <span className="chip lleno">Completo</span>}
            </div>
            <span className="secundario chico">
              {a.zonas.length} zonas · última foto {hace(a.ultima_en)}
            </span>
          </Enlace>
        ))}
        <div className="tarjeta columna">
          <h3>Nuevo anaquel</h3>
          <p className="secundario chico">Toma la foto con el anaquel <b>lleno</b>, de frente y desde un lugar al que puedas volver cada día (por ejemplo, desde el mostrador).</p>
          <label className="campo">Nombre<input value={nombre} onChange={(e) => setNombre(e.target.value)} placeholder="Refrescos, Botanas, Abarrotes…" /></label>
          <input ref={archivo} type="file" accept="image/*" capture="environment" hidden
            onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) crear(f) }} />
          <button className="boton" disabled={subiendo} onClick={() => archivo.current?.click()}>
            {subiendo ? 'Subiendo…' : '📷 Tomar foto del anaquel lleno'}
          </button>
        </div>
      </div>
    </div>
  )
}
