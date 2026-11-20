import { useEffect, useState, type FormEvent } from 'react'
import { Enlace } from '../../router'
import { api, ErrorMercado, type Cuenta, type CuentaDemo } from '../api'
import { Icono } from '../iconos'
import { Alerta, Avatar, Boton, BotonIcono, Campo, Esqueleto, mensajeDe, useTitulo } from '../ui'

export default function Entrar({ alEntrar }: { alEntrar: (c: Cuenta) => void }) {
  useTitulo('Entrar')
  const [email, setEmail] = useState('')
  const [pass, setPass] = useState('')
  const [ver, setVer] = useState(false)
  const [error, setError] = useState<{ msg: string; campo?: 'email' | 'pass' } | null>(null)
  const [ocupado, setOcupado] = useState<string | null>(null)
  const [demos, setDemos] = useState<CuentaDemo[] | null>(null)

  useEffect(() => {
    api.get<{ demo: boolean; cuentas_demo?: CuentaDemo[] }>('/acceso/config')
      .then((c) => setDemos(c.demo ? c.cuentas_demo ?? [] : []))
      .catch(() => setDemos([]))
  }, [])

  const entrar = async (e: string, p: string, quien: string) => {
    setOcupado(quien); setError(null)
    try {
      const r = await api.post<{ cuenta: Cuenta }>('/acceso/entrar', { email: e, password: p })
      alEntrar(r.cuenta)
    } catch (err) {
      const clave = err instanceof ErrorMercado ? err.clave : undefined
      setError({ msg: mensajeDe(err), campo: clave === 'credenciales' ? 'pass' : undefined })
    } finally { setOcupado(null) }
  }

  const enviar = (ev: FormEvent) => {
    ev.preventDefault()
    if (!email.trim()) return setError({ msg: 'Escribe tu correo', campo: 'email' })
    if (!pass) return setError({ msg: 'Escribe tu contraseña', campo: 'pass' })
    entrar(email, pass, 'form')
  }

  return (
    <div className="m-entrar">
      <section className="m-portada-marca" aria-labelledby="t-portada">
        <span className="m-insignia m-insignia--acento m-insignia--sin-punto" style={{ alignSelf: 'flex-start' }}>Solo negocios de Durango</span>
        <h1 id="t-portada">Compra y vende <em>cerca de ti</em>, entre negocios de confianza</h1>
        <ul className="m-beneficios">
          <li><Icono nombre="escudo" /><span>Cada cuenta se verifica antes de entrar.</span></li>
          <li><Icono nombre="ubicacion" /><span>Proveedores y productores de tu ciudad, con la distancia a tu negocio.</span></li>
          <li><Icono nombre="tarjeta" /><span>Aparta y paga con tarjeta en la terminal del vendedor.</span></li>
        </ul>
      </section>

      <div className="m-pila">
        <section className="m-card m-pila" aria-labelledby="t-entrar">
          <h2 id="t-entrar">Entrar al mercado</h2>
          <form className="m-pila" onSubmit={enviar} noValidate>
            <Campo etiqueta="Correo" error={error?.campo === 'email' ? error.msg : null}>
              {(p) => <input {...p} type="email" autoComplete="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
            </Campo>
            <Campo etiqueta="Contraseña" error={error?.campo === 'pass' ? error.msg : null}>
              {(p) => (
                <div className="m-con-boton">
                  <input {...p} type={ver ? 'text' : 'password'} autoComplete="current-password" value={pass} onChange={(e) => setPass(e.target.value)} />
                  <BotonIcono etiqueta={ver ? 'Ocultar contraseña' : 'Mostrar contraseña'} icono={ver ? 'ojoNo' : 'ojo'} onClick={() => setVer(!ver)} aria-pressed={ver} />
                </div>
              )}
            </Campo>
            {error && !error.campo && <Alerta tipo="error">{error.msg}</Alerta>}
            <Boton type="submit" ancho cargando={ocupado === 'form'} disabled={!!ocupado}>Entrar</Boton>
          </form>
          <p className="m-secundario m-chico">¿Te invitaron? <Enlace href="/mercado/registro">Crea la cuenta de tu negocio</Enlace></p>
        </section>

        {demos === null ? (
          <div className="m-card m-pila" aria-hidden="true">
            <Esqueleto className="m-esqueleto--titulo" style={{ width: '60%' }} />
            {[0, 1, 2].map((i) => <Esqueleto key={i} style={{ height: 56, borderRadius: 12 }} />)}
          </div>
        ) : demos.length > 0 && (
          <section className="m-card m-pila" aria-labelledby="t-demo">
            <div className="m-pila-chica">
              <h2 id="t-demo">Probar con un negocio de demostración</h2>
              <p className="m-terciario">Contraseña de todas: <code>demo1234</code></p>
            </div>
            <div className="m-demo-lista">
              {demos.map((d) => (
                <button key={d.email} type="button" className="m-demo" disabled={!!ocupado} aria-busy={ocupado === d.email || undefined}
                  onClick={() => entrar(d.email, 'demo1234', d.email)}>
                  <Avatar nombre={d.nombre} />
                  <span className="m-renglon-cuerpo"><b>{d.nombre}</b><span className="m-terciario">{d.que}</span></span>
                  {ocupado === d.email ? <span className="m-puntos" aria-hidden="true"><i /><i /><i /></span> : <Icono nombre="adelante" />}
                </button>
              ))}
            </div>
          </section>
        )}
      </div>
    </div>
  )
}
