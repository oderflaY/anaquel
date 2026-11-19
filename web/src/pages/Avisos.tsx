import { useEffect } from 'react'
import { api, hace, type Aviso } from '../api'
import type { Sesion } from '../App'
import { Enlace } from '../router'
import { Estado, useDatos } from '../ui'

const icono: Record<string, string> = { encontrado: '🎯', interesado: '🛒', trato: '🤝', sistema: '🔔' }

export default function Avisos({ sesion }: { sesion: Sesion }) {
  const { datos, error } = useDatos(() => api.get<Aviso[]>('/avisos'))
  // Al abrir la página se marcan como leídos (se siguen mostrando resaltados esta vez).
  useEffect(() => {
    if (datos?.some((a) => !a.leido)) api.post('/avisos/leidos').then(sesion.refrescar).catch(() => {})
  }, [datos]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="columna" style={{ gap: '1.25rem', maxWidth: 760 }}>
      <div>
        <h1 style={{ marginBottom: '.2rem' }}>Avisos</h1>
        <p className="secundario" style={{ margin: 0 }}>Lo que tu asesor encontró para ti, quién busca lo que tú vendes y cómo van tus tratos.</p>
      </div>
      <Estado error={error} cargando={!datos} />
      {datos && datos.length === 0 && <div className="tarjeta vacio-lista">Sin avisos todavía.</div>}
      <div className="lista">
        {(datos ?? []).map((a) => (
          <Enlace key={a.id} href={a.enlace || '/panel'} className={`tarjeta aviso-fila ${a.leido ? '' : 'nuevo'}`}>
            <span className="icono-aviso" aria-hidden>{icono[a.tipo] ?? '🔔'}</span>
            <div>
              <div>{a.texto}</div>
              <span className="secundario chico">{hace(a.creado)}</span>
            </div>
          </Enlace>
        ))}
      </div>
    </div>
  )
}
