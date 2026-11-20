import { useEffect, useState, type FormEvent } from 'react'
import { api, pesos, type Terminal, type TransaccionSim } from '../api'
import { Icono } from '../iconos'
import { Alerta, Boton, Campo, Cargando, EnlaceBoton, Esqueleto, EstadoVacio, mensajeDe, useConsulta, useTitulo, avisar } from '../ui'

/** Terminal de demostración: se comporta como una terminal real. Al aprobar,
 *  notifica al sistema de pagos (webhook firmado) y el cobro se registra solo. */
export default function TerminalVirtual() {
  useTitulo('Terminal virtual')
  const terms = useConsulta((s) => api.get<Terminal[]>('/pagos/terminales', s), [])
  const [pendientes, setPendientes] = useState<TransaccionSim[] | null>(null)
  const [ocupado, setOcupado] = useState<string | null>(null)
  const [monto, setMonto] = useState('')
  const [errMonto, setErrMonto] = useState<string | null>(null)
  const sims = terms.datos?.filter((t) => t.proveedor === 'simulador') ?? []

  useEffect(() => {
    let vivo = true
    const revisar = () => api.get<TransaccionSim[]>('/pagos/simulador/pendientes').then((p) => vivo && setPendientes(p)).catch(() => vivo && setPendientes([]))
    revisar()
    const t = setInterval(revisar, 2000)
    return () => { vivo = false; clearInterval(t) }
  }, [])

  const resolver = async (tx: TransaccionSim, accion: 'aprobar' | 'rechazar') => {
    setOcupado(tx.id + accion)
    try {
      await api.post(`/pagos/simulador/transacciones/${tx.id}/${accion}`)
      setPendientes((p) => p?.filter((x) => x.id !== tx.id) ?? null)
      avisar(accion === 'aprobar' ? 'Tarjeta aprobada. La terminal notificó al sistema.' : 'Tarjeta rechazada', accion === 'aprobar' ? 'exito' : 'error')
    } catch (e) { avisar(mensajeDe(e), 'error') } finally { setOcupado(null) }
  }

  const directo = async (e: FormEvent) => {
    e.preventDefault()
    const m = monto.replace(/[\s$,]/g, '').match(/^(\d{1,6})(?:\.(\d{1,2}))?$/)
    const c = m ? Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0')) : 0
    if (c < 100) return setErrMonto('Escribe un monto desde $1')
    setErrMonto(null); setOcupado('directo')
    try {
      await api.post(`/pagos/simulador/terminales/${sims[0].id}/cobro-directo`, { monto_c: c, concepto: 'Venta de mostrador' })
      setMonto(''); avisar(`Cobro directo de ${pesos(c)} aprobado. Revisa «Cobros recibidos».`)
    } catch (ex) { setErrMonto(mensajeDe(ex)) } finally { setOcupado(null) }
  }

  if (terms.cargando) return <Cargando><Esqueleto style={{ height: 420, maxWidth: 380, margin: '0 auto', borderRadius: 28 }} /></Cargando>
  if (!sims.length) {
    return (
      <div className="m-pila">
        <h1>Terminal virtual</h1>
        <EstadoVacio icono="terminal" titulo="No tienes una terminal virtual" texto="Vincúlala en Vender → Terminales para probar cobros sin un aparato real."
          accion={<EnlaceBoton href="/mercado/vender?tab=terminales">Vincular terminal virtual</EnlaceBoton>} />
      </div>
    )
  }
  const tx = pendientes?.[0]
  return (
    <div className="m-pila" style={{ gap: 'var(--e-6)' }}>
      <div className="m-pila-chica" style={{ textAlign: 'center' }}>
        <h1>Terminal virtual</h1>
        <p className="m-secundario">Simula la terminal «{sims[0].nombre}». Funciona como una real: avisa al sistema de pagos por notificación.</p>
      </div>
      <div className="m-terminal" role="region" aria-label="Pantalla de la terminal">
        <div className="m-terminal-pantalla" aria-live="polite">
          {pendientes === null ? <Esqueleto style={{ height: 120 }} />
            : tx ? <>
              <span style={{ fontSize: 'var(--texto-14)', color: '#5c564d' }}>{tx.concepto || 'Cobro'} · {tx.terminal}</span>
              <span className="m-precio-grande">{pesos(tx.monto_c)}</span>
              <span style={{ display: 'flex', gap: 8, justifyContent: 'center', alignItems: 'center', color: '#0e7c5a', fontWeight: 600 }}><Icono nombre="tarjeta" />Acerca o inserta la tarjeta</span>
            </> : <>
              <Icono nombre="terminal" className="m-icono" />
              <b>Lista para cobrar</b>
              <span style={{ fontSize: 'var(--texto-14)', color: '#5c564d' }}>Envía un cobro desde una orden, o cobra directo abajo.</span>
            </>}
        </div>
        {tx && (
          <div className="m-fila" style={{ justifyContent: 'center' }}>
            <Boton variante="peligro" cargando={ocupado === tx.id + 'rechazar'} disabled={!!ocupado} onClick={() => resolver(tx, 'rechazar')}>Rechazar</Boton>
            <Boton icono="tarjeta" cargando={ocupado === tx.id + 'aprobar'} disabled={!!ocupado} onClick={() => resolver(tx, 'aprobar')}>Pasar tarjeta</Boton>
          </div>
        )}
      </div>
      <form className="m-card m-pila" style={{ maxWidth: 380, margin: '0 auto', width: '100%' }} onSubmit={directo} noValidate aria-labelledby="t-directo">
        <h2 id="t-directo">Cobro directo en la terminal</h2>
        <p className="m-terciario">Como cuando cobras en el aparato sin usar la app. El sistema lo detecta por la notificación y lo registra solo.</p>
        <Campo etiqueta="Monto (MXN)" error={errMonto}>
          {(p) => <input {...p} inputMode="decimal" placeholder="350.00" value={monto} onChange={(e) => setMonto(e.target.value)} />}
        </Campo>
        <Boton type="submit" variante="secundario" cargando={ocupado === 'directo'}>Cobrar y aprobar</Boton>
      </form>
      {pendientes && pendientes.length > 1 && <Alerta tipo="aviso">Hay {pendientes.length - 1} cobro(s) más en espera.</Alerta>}
    </div>
  )
}
