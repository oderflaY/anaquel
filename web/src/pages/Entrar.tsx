import { useEffect, useState, type FormEvent } from 'react'
import { api, guardarToken, type Tienda } from '../api'
import { mensajeDe } from '../ui'

type Resp = { token: string; tienda: Tienda }

export default function Entrar({ alEntrar }: { alEntrar: (t: Tienda) => void }) {
  const [modo, setModo] = useState<'entrar' | 'registro'>('entrar')
  const [f, setF] = useState({ nombre: '', email: '', password: '', telefono: '', direccion: '' })
  const [error, setError] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)

  const enviar = async (datos: object, ruta: string) => {
    setOcupado(true); setError(null)
    try {
      const r = await api.post<Resp>(ruta, datos)
      guardarToken(r.token)
      alEntrar(r.tienda)
    } catch (e) { setError(mensajeDe(e)) } finally { setOcupado(false) }
  }
  const demo = () => enviar({ email: 'demo@anaquel.mx', password: 'demo1234' }, '/login')

  useEffect(() => {
    if (new URLSearchParams(location.search).get('demo') === '1') demo()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (modo === 'entrar') enviar({ email: f.email, password: f.password }, '/login')
    else enviar(f, '/registro')
  }
  const campo = (k: keyof typeof f) => ({ value: f[k], onChange: (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value }) })

  return (
    <div style={{ maxWidth: 440, margin: '1rem auto' }} className="columna">
      <div className="tarjeta columna">
        <div className="fila">
          <button className={`boton chico ${modo === 'entrar' ? '' : 'claro'}`} onClick={() => setModo('entrar')}>Entrar</button>
          <button className={`boton chico ${modo === 'registro' ? '' : 'claro'}`} onClick={() => setModo('registro')}>Registrar mi tienda</button>
        </div>
        <form className="columna" onSubmit={submit}>
          {modo === 'registro' && (
            <label className="campo">Nombre de la tienda<input required {...campo('nombre')} placeholder="Abarrotes Doña Lupita" /></label>
          )}
          <label className="campo">Correo<input type="email" required autoComplete="email" {...campo('email')} /></label>
          <label className="campo">Contraseña<input type="password" required minLength={6} autoComplete={modo === 'entrar' ? 'current-password' : 'new-password'} {...campo('password')} /></label>
          {modo === 'registro' && (
            <>
              <label className="campo">WhatsApp de la tienda<input type="tel" {...campo('telefono')} placeholder="618 123 4567" /></label>
              <label className="campo">Dirección<input {...campo('direccion')} placeholder="Calle, número, colonia" /></label>
              <p className="secundario chico">Después podrás marcar tu tienda en el mapa desde «Mi tienda».</p>
            </>
          )}
          {error && <div className="aviso error">{error}</div>}
          <button className="boton ancho" disabled={ocupado}>{ocupado ? 'Un momento…' : modo === 'entrar' ? 'Entrar' : 'Crear mi tienda'}</button>
        </form>
      </div>
      <div className="tarjeta columna">
        <b>¿Solo quieres verla funcionar?</b>
        <p className="secundario chico" style={{ margin: 0 }}>Entra a «Abarrotes Doña Lupita», una tienda de demostración con su anaquel, faltantes y pedidos.</p>
        <button className="boton ambar" onClick={demo} disabled={ocupado}>Entrar a la demo</button>
      </div>
    </div>
  )
}
