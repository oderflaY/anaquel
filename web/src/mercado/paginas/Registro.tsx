import { useState, type FormEvent } from 'react'
import { Enlace } from '../../router'
import { api, ErrorMercado, type Cuenta } from '../api'
import { Alerta, Boton, BotonIcono, Campo, mensajeDe, useTitulo } from '../ui'

const GIROS: [string, string][] = [
  ['abarrotes', 'Abarrotes / tiendita'], ['ropa', 'Ropa y accesorios'], ['papeleria', 'Papelería'], ['ferreteria', 'Ferretería'],
  ['farmacia', 'Farmacia'], ['dulceria', 'Dulcería y fiestas'], ['comida', 'Comida'], ['mayoreo', 'Proveedor / mayoreo'],
  ['productor', 'Productor local'], ['servicios', 'Servicios'], ['otro', 'Otro'],
]

type Campos = 'codigo' | 'nombre' | 'email' | 'password' | 'telefono' | 'colonia'

/** Fuerza aproximada solo para orientar; la regla real la aplica el servidor. */
function fuerza(p: string): [number, string] {
  let n = 0
  if (p.length >= 10) n++
  if (p.length >= 14) n++
  if (/[a-z]/.test(p) && /[A-Z]/.test(p)) n++
  if (/\d/.test(p) && /[^A-Za-z0-9]/.test(p)) n++
  return [n, ['Muy débil', 'Débil', 'Aceptable', 'Buena', 'Muy buena'][n]]
}

export default function Registro({ alEntrar }: { alEntrar: (c: Cuenta) => void }) {
  useTitulo('Crear cuenta')
  const [f, setF] = useState({ codigo: '', nombre: '', email: '', password: '', telefono: '', colonia: '', giro: 'abarrotes' })
  const [coords, setCoords] = useState<{ lat: number; lng: number } | null>(null)
  const [ubicando, setUbicando] = useState(false)
  const [ver, setVer] = useState(false)
  const [errores, setErrores] = useState<Partial<Record<Campos, string>>>({})
  const [general, setGeneral] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)

  const campo = (k: Campos) => ({ value: f[k], onChange: (e: React.ChangeEvent<HTMLInputElement>) => { setF({ ...f, [k]: e.target.value }); setErrores({ ...errores, [k]: undefined }) } })

  const ubicar = () => {
    if (!navigator.geolocation) return setGeneral('Tu navegador no permite compartir ubicación. Escribe tu colonia.')
    setUbicando(true)
    navigator.geolocation.getCurrentPosition(
      (p) => { setCoords({ lat: p.coords.latitude, lng: p.coords.longitude }); setUbicando(false) },
      () => { setGeneral('No pudimos obtener tu ubicación. Puedes seguir sin ella.'); setUbicando(false) },
      { enableHighAccuracy: false, timeout: 10000 },
    )
  }

  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    const err: Partial<Record<Campos, string>> = {}
    if (f.codigo.replace(/[^A-Za-z0-9]/g, '').length !== 10) err.codigo = 'El código tiene 10 letras y números'
    if (f.nombre.trim().length < 2) err.nombre = 'Escribe el nombre de tu negocio'
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(f.email.trim())) err.email = 'Revisa tu correo'
    if (f.password.length < 10) err.password = 'Usa al menos 10 caracteres'
    if (f.telefono && f.telefono.replace(/\D/g, '').length !== 10) err.telefono = 'El teléfono lleva 10 dígitos'
    if (!f.colonia.trim()) err.colonia = 'Escribe tu colonia o localidad'
    setErrores(err); setGeneral(null)
    if (Object.keys(err).length) { document.getElementById('registro')?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(); return }
    setOcupado(true)
    try {
      const r = await api.post<{ cuenta: Cuenta }>('/acceso/registro', { ...f, ...(coords ?? {}) })
      alEntrar(r.cuenta)
    } catch (ex) {
      const clave = ex instanceof ErrorMercado ? ex.clave : undefined
      if (clave === 'codigo_invalido') setErrores({ codigo: mensajeDe(ex) })
      else if (clave === 'correo_registrado') setErrores({ email: mensajeDe(ex) })
      else if (mensajeDe(ex).toLowerCase().includes('contraseña')) setErrores({ password: mensajeDe(ex) })
      else setGeneral(mensajeDe(ex))
    } finally { setOcupado(false) }
  }

  const [nivel, txtNivel] = fuerza(f.password)
  return (
    <div style={{ maxWidth: 640, margin: '0 auto' }} className="m-pila">
      <div className="m-pila-chica">
        <h1>Crea la cuenta de tu negocio</h1>
        <p className="m-secundario">Necesitas un código de invitación de otro negocio que ya esté en el mercado. Después revisamos tus datos y te damos acceso.</p>
      </div>
      <form id="registro" className="m-card m-rejilla-campos" onSubmit={enviar} noValidate>
        <Campo etiqueta="Código de invitación" ayuda="Ejemplo: K7M2Q-9XH4T" error={errores.codigo} className="m-completo">
          {(p) => <input {...p} autoComplete="off" autoCapitalize="characters" spellCheck={false} {...campo('codigo')} />}
        </Campo>
        <Campo etiqueta="Nombre del negocio" error={errores.nombre}>
          {(p) => <input {...p} autoComplete="organization" {...campo('nombre')} />}
        </Campo>
        <Campo etiqueta="Giro">
          {(p) => (
            <select {...p} value={f.giro} onChange={(e) => setF({ ...f, giro: e.target.value })}>
              {GIROS.map(([k, n]) => <option key={k} value={k}>{n}</option>)}
            </select>
          )}
        </Campo>
        <Campo etiqueta="Correo" error={errores.email}>
          {(p) => <input {...p} type="email" autoComplete="email" inputMode="email" {...campo('email')} />}
        </Campo>
        <Campo etiqueta="WhatsApp" opcional error={errores.telefono}>
          {(p) => <input {...p} type="tel" autoComplete="tel-national" inputMode="tel" placeholder="618 123 4567" {...campo('telefono')} />}
        </Campo>
        <Campo etiqueta="Contraseña" error={errores.password} className="m-completo"
          ayuda={f.password ? `Seguridad: ${txtNivel}. Mínimo 10 caracteres.` : 'Mínimo 10 caracteres. Una frase corta es fácil de recordar.'}>
          {(p) => (
            <div className="m-con-boton">
              <input {...p} type={ver ? 'text' : 'password'} autoComplete="new-password" {...campo('password')} />
              <BotonIcono etiqueta={ver ? 'Ocultar contraseña' : 'Mostrar contraseña'} icono={ver ? 'ojoNo' : 'ojo'} onClick={() => setVer(!ver)} aria-pressed={ver} />
            </div>
          )}
        </Campo>
        {f.password && <div className="m-completo" aria-hidden="true" style={{ display: 'flex', gap: 4, marginTop: -8 }}>
          {[1, 2, 3, 4].map((i) => <span key={i} style={{ flex: 1, height: 4, borderRadius: 4, background: i <= nivel ? (nivel >= 3 ? 'var(--color-exito)' : 'var(--color-aviso)') : 'var(--color-borde)' }} />)}
        </div>}
        <Campo etiqueta="Colonia o localidad" error={errores.colonia}>
          {(p) => <input {...p} autoComplete="address-level3" placeholder="Zona Centro" {...campo('colonia')} />}
        </Campo>
        <div className="m-campo">
          <span>Ubicación del negocio <span className="m-terciario">(opcional)</span></span>
          {coords
            ? <Alerta tipo="exito">Ubicación lista. Solo mostramos tu colonia y la distancia, nunca tu dirección exacta.</Alerta>
            : <Boton variante="secundario" icono="ubicacion" cargando={ubicando} onClick={ubicar}>Usar mi ubicación</Boton>}
        </div>
        {general && <div className="m-completo"><Alerta tipo="error">{general}</Alerta></div>}
        <div className="m-completo m-pila-chica">
          <Boton type="submit" ancho cargando={ocupado}>Crear cuenta</Boton>
          <p className="m-terciario" style={{ textAlign: 'center' }}>¿Ya tienes cuenta? <Enlace href="/mercado/entrar">Entrar</Enlace></p>
        </div>
      </form>
    </div>
  )
}
