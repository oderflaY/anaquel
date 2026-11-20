import type { Cuenta } from '../api'
import { Icono } from '../iconos'
import { Boton, useTitulo } from '../ui'

/** Protocolo de acceso: la cuenta existe pero aún no entra al mercado. */
export default function Revision({ cuenta, salir }: { cuenta: Cuenta; salir: () => void }) {
  useTitulo('Cuenta en revisión')
  const suspendida = cuenta.estado === 'suspendida'
  const pasos = [
    ['Recibimos tu registro', true],
    ['Revisamos que tu negocio esté en Durango', false],
    ['Te damos acceso al mercado', false],
  ] as const
  return (
    <div style={{ maxWidth: 520, margin: '0 auto' }} className="m-card m-pila">
      <div className={`m-estado ${suspendida ? 'm-estado--error' : ''}`} style={{ padding: '8px 0' }}>
        <span className="m-estado-icono"><Icono nombre={suspendida ? 'alerta' : 'reloj'} /></span>
        <h1>{suspendida ? 'Tu cuenta está suspendida' : 'Tu cuenta está en revisión'}</h1>
        <p>{suspendida
          ? 'Escríbenos para revisar tu caso. Mientras tanto no puedes comprar ni vender.'
          : `Hola, ${cuenta.nombre}. Solo negocios verificados de Durango entran al mercado. Te avisaremos a ${cuenta.email}.`}</p>
      </div>
      {!suspendida && (
        <ol className="m-linea" aria-label="Avance de la revisión">
          {pasos.map(([t, hecho], i) => (
            <li key={t} data-estado={hecho ? 'hecho' : i === 1 ? 'actual' : ''} aria-current={i === 1 ? 'step' : undefined}>
              <span className="m-paso">{hecho ? <Icono nombre="check" /> : <span aria-hidden="true">{i + 1}</span>}</span>
              <span>{t}{hecho && <span className="sr-only"> (listo)</span>}</span>
            </li>
          ))}
        </ol>
      )}
      <Boton variante="secundario" icono="salir" onClick={salir}>Salir</Boton>
    </div>
  )
}
