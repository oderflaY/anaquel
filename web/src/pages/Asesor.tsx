import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, nombreGiro, type MensajeAsesor, type Resultado } from '../api'
import type { Sesion } from '../App'
import { DialogoNegociar, TarjetaRed } from '../red'
import { Enlace } from '../router'
import { avisar, Estado, mensajeDe, useDatos } from '../ui'

const sugerencias: Record<string, string[]> = {
  ropa: ['Ya viene diciembre y el frío, ¿qué me conviene comprar?', '¿Cómo va mi negocio?', 'Busco chamarras de mayoreo', '¿Qué hago con lo que no se vende?'],
  abarrotes: ['¿Qué compro para las posadas?', '¿Cómo va mi tienda?', 'Busco ponche navideño', '¿Qué se me está acabando?'],
  papeleria: ['¿Qué viene para mi papelería?', 'Busco 1000 gorras', '¿Cómo va mi negocio?', 'Publica que busco piñatas'],
  mayoreo: ['¿Qué están buscando las tiendas?', '¿Qué viene de temporada para mis clientes?', '¿Cómo va mi negocio?', '¿Qué no se está moviendo?'],
}
const generales = ['¿Qué compro para la temporada que viene?', '¿Cómo va mi negocio?', 'Busco proveedores de…', '¿Qué no se está moviendo?']

/** Texto con listas «- » y saltos de línea, sin interpretar HTML. */
function Texto({ t }: { t: string }) {
  const bloques: React.ReactNode[] = []
  let lista: string[] = []
  const cerrar = () => { if (lista.length) bloques.push(<ul key={bloques.length}>{lista.map((x, i) => <li key={i}>{negritas(x)}</li>)}</ul>); lista = [] }
  for (const linea of t.split('\n')) {
    const m = linea.match(/^\s*[-•*]\s+(.*)/)
    if (m) { lista.push(m[1]); continue }
    cerrar()
    if (linea.trim()) bloques.push(<p key={bloques.length}>{negritas(linea)}</p>)
  }
  cerrar()
  return <>{bloques}</>
}
const negritas = (s: string) => s.split(/\*\*(.+?)\*\*/g).map((p, i) => (i % 2 ? <b key={i}>{p}</b> : p))

export default function Asesor({ sesion }: { sesion: Sesion }) {
  const { datos, error, setDatos } = useDatos(() => api.get<{ mensajes: MensajeAsesor[]; motor: 'ia' | 'basico' }>('/asesor'))
  const [texto, setTexto] = useState('')
  const [pensando, setPensando] = useState<string | null>(null)
  const [negociar, setNegociar] = useState<Resultado | null>(null)
  const fin = useRef<HTMLDivElement>(null)
  useEffect(() => { fin.current?.scrollIntoView({ block: 'end', behavior: 'smooth' }) }, [datos?.mensajes.length, pensando])

  if (!datos) return <Estado error={error} cargando />
  const preguntar = async (q: string) => {
    q = q.trim()
    if (!q || pensando) return
    setPensando(q); setTexto('')
    try {
      const r = await api.post<{ pregunta: MensajeAsesor; respuesta: MensajeAsesor }>('/asesor', { mensaje: q })
      setDatos({ ...datos, mensajes: [...datos.mensajes, r.pregunta, r.respuesta] })
      sesion.refrescar()
    } catch (e) { avisar(mensajeDe(e)); setTexto(q) } finally { setPensando(null) }
  }
  const enviar = (e: FormEvent) => { e.preventDefault(); preguntar(texto) }
  const nueva = async () => {
    if (!confirm('¿Empezar una conversación nueva?')) return
    await api.del('/asesor').catch(() => {})
    setDatos({ ...datos, mensajes: [] })
  }
  const chips = sugerencias[sesion.tienda.giro] ?? generales

  return (
    <div className="columna asesor" style={{ gap: '1rem' }}>
      <div className="renglon">
        <div>
          <h1 style={{ marginBottom: '.2rem' }}>Tu asesor</h1>
          <p className="secundario" style={{ margin: 0 }}>
            Conoce tu inventario, tus ventas y la temporada de tu giro ({nombreGiro(sesion.tienda.giro)}), y busca por ti en la red.{' '}
            <span className={`chip ${datos.motor === 'ia' ? 'llego' : 'cancelado'}`}>{datos.motor === 'ia' ? 'Con IA (Claude)' : 'Asesor básico, sin IA'}</span>
          </p>
        </div>
        {datos.mensajes.length > 0 && <button className="boton claro chico" onClick={nueva}>Conversación nueva</button>}
      </div>

      <div className="chat chat-asesor">
        {datos.mensajes.length === 0 && !pensando && (
          <div className="tarjeta columna">
            <b>¿En qué te ayudo hoy?</b>
            <span className="secundario chico">Pregúntame qué comprar para la temporada, cómo va tu negocio o dime qué buscas y lo encuentro en la red.</span>
          </div>
        )}
        {datos.mensajes.map((m) => (
          <div key={m.id} className={`burbuja-chat ${m.rol === 'user' ? 'yo' : 'otro'} ${m.rol === 'assistant' ? 'del-asesor' : ''}`}>
            {m.rol === 'user' ? <div>{m.texto}</div> : <Texto t={m.texto} />}
            {m.pasos && m.pasos.length > 0 && (
              <details className="pasos"><summary>Lo que hizo tu asesor ({m.pasos.length})</summary><ul>{m.pasos.map((p, i) => <li key={i}>{p}</li>)}</ul></details>
            )}
            {m.tarjetas && m.tarjetas.length > 0 && (
              <div className="tarjetas-chat">
                {m.tarjetas.map((r) => <TarjetaRed key={r.tipo + r.id} r={r} onNegociar={setNegociar} compacta />)}
              </div>
            )}
          </div>
        ))}
        {pensando && (
          <>
            <div className="burbuja-chat yo"><div>{pensando}</div></div>
            <div className="burbuja-chat otro pensando" role="status">Revisando tu negocio y la red…</div>
          </>
        )}
        <div ref={fin} />
      </div>

      <div className="fila sugerencias">
        {chips.map((c) => <button key={c} className="boton claro chico" disabled={!!pensando} onClick={() => c.endsWith('…') ? setTexto(c.replace('…', ' ')) : preguntar(c)}>{c}</button>)}
      </div>
      <form className="fila" onSubmit={enviar}>
        <input style={{ flex: 1 }} value={texto} onChange={(e) => setTexto(e.target.value)} placeholder="Escribe tu pregunta…" aria-label="Pregunta para tu asesor" maxLength={1500} />
        <button className="boton" disabled={!!pensando || !texto.trim()}>Preguntar</button>
      </form>
      <p className="secundario chico" style={{ margin: 0 }}>
        Tu asesor no cierra tratos por ti: tú negocias desde las tarjetas. Si le pides que consiga algo, publica tu solicitud en la <Enlace href="/panel/red">Red</Enlace>.
      </p>
      <DialogoNegociar r={negociar} onCerrar={() => setNegociar(null)} />
    </div>
  )
}
