import { useState, type FormEvent } from 'react'
import type { SesionMercado } from '../Mercado'
import { api, fecha, type Cuenta as C } from '../api'
import { Icono } from '../iconos'
import { Alerta, Avatar, Boton, BotonIcono, Campo, EsqueletoLista, EstadoError, EstadoVacio, Insignia, mensajeDe, useConsulta, useTitulo, avisar } from '../ui'

type Invitacion = { id: number; creada: string; expira: string; usada: string | null; usada_por: string | null }
type SesionAct = { actual: boolean; creada: string; ultimo_uso: string; agente: string }

export default function Cuenta({ sesion }: { sesion: SesionMercado }) {
  useTitulo('Mi cuenta')
  const c = sesion.cuenta
  return (
    <div className="m-pila" style={{ maxWidth: 720, margin: '0 auto', gap: 'var(--e-6)' }}>
      <div className="m-fila">
        <Avatar nombre={c.nombre} grande />
        <div className="m-pila-chica"><h1>{c.nombre}</h1><span className="m-terciario">{c.email}</span>
          <span>{c.estado === 'verificada' ? <Insignia tono="exito">Cuenta verificada</Insignia> : <Insignia tono="aviso">En revisión</Insignia>}</span></div>
      </div>
      {c.estado === 'verificada' && <Invitaciones />}
      <Perfil c={c} recargar={sesion.recargar} />
      <Contrasena />
      <Sesiones />
      {c.rol === 'admin' && <Admin />}
    </div>
  )
}

function Invitaciones() {
  const { datos, setDatos, error, cargando, recargar } = useConsulta((s) => api.get<Invitacion[]>('/acceso/invitaciones', s), [])
  const [codigo, setCodigo] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const crear = async () => {
    setOcupado(true); setErr(null)
    try {
      const r = await api.post<{ codigo: string; expira: string }>('/acceso/invitaciones')
      setCodigo(r.codigo); recargar()
    } catch (e) { setErr(mensajeDe(e)) } finally { setOcupado(false) }
  }
  const copiar = async () => { try { await navigator.clipboard.writeText(codigo!); avisar('Código copiado') } catch { avisar('No se pudo copiar. Selecciónalo y cópialo.', 'error') } }
  void setDatos
  return (
    <section className="m-card m-pila" aria-labelledby="t-inv">
      <div className="m-pila-chica"><h2 id="t-inv">Invitar a otro negocio</h2><p className="m-terciario">Solo entra quien recibe un código de un negocio verificado. Cada código sirve una vez y vence en 7 días.</p></div>
      {codigo && (
        <Alerta tipo="exito">
          <div className="m-fila m-entre">
            <span>Código: <b style={{ fontFamily: 'ui-monospace, monospace', fontSize: 'var(--texto-20)', letterSpacing: '.08em' }}>{codigo}</b></span>
            <BotonIcono etiqueta="Copiar código" icono="copiar" onClick={copiar} />
          </div>
          <span className="m-terciario">Guárdalo: por seguridad no lo volveremos a mostrar.</span>
        </Alerta>
      )}
      {err && <Alerta tipo="error">{err}</Alerta>}
      <Boton variante="secundario" icono="invitar" cargando={ocupado} onClick={crear}>Crear código de invitación</Boton>
      {cargando ? <EsqueletoLista n={1} /> : error ? <EstadoError error={error} reintentar={recargar} /> : !!datos?.length && (
        <ul className="m-lista" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {datos.map((i) => {
            const vencida = !i.usada && new Date(i.expira) < new Date()
            return (
              <li key={i.id} className="m-renglon" style={{ minHeight: 56 }}>
                <div className="m-renglon-cuerpo"><span>Creada {fecha(i.creada)}</span><span className="m-terciario">{i.usada ? `La usó ${i.usada_por ?? 'un negocio'}` : vencida ? 'Venció' : `Vence ${fecha(i.expira)}`}</span></div>
                {i.usada ? <Insignia tono="exito">Usada</Insignia> : vencida ? <Insignia>Vencida</Insignia> : <Insignia tono="info">Disponible</Insignia>}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

function Perfil({ c, recargar }: { c: C; recargar: () => void }) {
  const [f, setF] = useState({ nombre: c.nombre, descripcion: c.descripcion, telefono: c.telefono, colonia: c.colonia })
  const [err, setErr] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const guardar = async (e: FormEvent) => {
    e.preventDefault(); setOcupado(true); setErr(null)
    try { await api.put('/acceso/yo', f); avisar('Datos guardados'); recargar() } catch (ex) { setErr(mensajeDe(ex)) } finally { setOcupado(false) }
  }
  return (
    <form className="m-card m-rejilla-campos" onSubmit={guardar} aria-labelledby="t-perfil">
      <h2 id="t-perfil" className="m-completo">Datos del negocio</h2>
      <Campo etiqueta="Nombre">{(p) => <input {...p} maxLength={80} value={f.nombre} onChange={(e) => setF({ ...f, nombre: e.target.value })} />}</Campo>
      <Campo etiqueta="Colonia">{(p) => <input {...p} maxLength={80} value={f.colonia} onChange={(e) => setF({ ...f, colonia: e.target.value })} />}</Campo>
      <Campo etiqueta="WhatsApp" opcional>{(p) => <input {...p} type="tel" inputMode="tel" value={f.telefono} onChange={(e) => setF({ ...f, telefono: e.target.value })} />}</Campo>
      <Campo etiqueta="Descripción" opcional className="m-completo">{(p) => <textarea {...p} maxLength={600} value={f.descripcion} onChange={(e) => setF({ ...f, descripcion: e.target.value })} />}</Campo>
      {err && <div className="m-completo"><Alerta tipo="error">{err}</Alerta></div>}
      <div className="m-completo"><Boton type="submit" variante="secundario" cargando={ocupado}>Guardar</Boton></div>
    </form>
  )
}

function Contrasena() {
  const [actual, setActual] = useState('')
  const [nueva, setNueva] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const cambiar = async (e: FormEvent) => {
    e.preventDefault(); setErr(null)
    if (nueva.length < 10) return setErr('La nueva contraseña debe tener al menos 10 caracteres')
    setOcupado(true)
    try { await api.put('/acceso/contrasena', { actual, nueva }); setActual(''); setNueva(''); avisar('Contraseña cambiada. Cerramos tus otras sesiones.') }
    catch (ex) { setErr(mensajeDe(ex)) } finally { setOcupado(false) }
  }
  return (
    <form className="m-card m-rejilla-campos" onSubmit={cambiar} aria-labelledby="t-pass">
      <h2 id="t-pass" className="m-completo">Cambiar contraseña</h2>
      <Campo etiqueta="Contraseña actual">{(p) => <input {...p} type="password" autoComplete="current-password" value={actual} onChange={(e) => setActual(e.target.value)} />}</Campo>
      <Campo etiqueta="Nueva contraseña" ayuda="Mínimo 10 caracteres.">{(p) => <input {...p} type="password" autoComplete="new-password" value={nueva} onChange={(e) => setNueva(e.target.value)} />}</Campo>
      {err && <div className="m-completo"><Alerta tipo="error">{err}</Alerta></div>}
      <div className="m-completo"><Boton type="submit" variante="secundario" cargando={ocupado} disabled={!actual || !nueva}>Cambiar contraseña</Boton></div>
    </form>
  )
}

function Sesiones() {
  const { datos, error, cargando, recargar } = useConsulta((s) => api.get<SesionAct[]>('/acceso/sesiones', s), [])
  const [ocupado, setOcupado] = useState(false)
  const cerrar = async () => {
    setOcupado(true)
    try { await api.post('/acceso/sesiones/cerrar-otras'); avisar('Cerramos tus otras sesiones'); recargar() } catch (e) { avisar(mensajeDe(e), 'error') } finally { setOcupado(false) }
  }
  const disp = (a: string) => (/Android|iPhone|iPad/i.test(a) ? 'Celular' : /Windows|Mac|Linux/i.test(a) ? 'Computadora' : 'Dispositivo') + (/Chrome/i.test(a) ? ' · Chrome' : /Firefox/i.test(a) ? ' · Firefox' : /Safari/i.test(a) ? ' · Safari' : '')
  return (
    <section className="m-card m-pila" aria-labelledby="t-ses">
      <div className="m-fila m-entre"><h2 id="t-ses">Dónde tienes sesión</h2>{(datos?.length ?? 0) > 1 && <Boton variante="fantasma" chico cargando={ocupado} onClick={cerrar}>Cerrar las demás</Boton>}</div>
      {cargando ? <EsqueletoLista n={1} /> : error ? <EstadoError error={error} reintentar={recargar} /> : (
        <ul className="m-lista" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {datos?.map((s, i) => (
            <li key={i} className="m-renglon" style={{ minHeight: 56 }}>
              <Icono nombre="escudo" />
              <div className="m-renglon-cuerpo"><span>{disp(s.agente)}</span><span className="m-terciario">Último uso {fecha(s.ultimo_uso)}</span></div>
              {s.actual && <Insignia tono="exito">Esta sesión</Insignia>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function Admin() {
  const { datos, setDatos, error, cargando, recargar } = useConsulta((s) => api.get<C[]>('/acceso/admin/cuentas?estado=pendiente', s), [])
  const [ocupado, setOcupado] = useState<string | null>(null)
  const accion = async (id: number, a: 'verificar' | 'suspender') => {
    setOcupado(`${id}${a}`)
    try { await api.post(`/acceso/admin/cuentas/${id}/${a}`); setDatos((d) => d?.filter((x) => x.id !== id) ?? null); avisar(a === 'verificar' ? 'Cuenta verificada' : 'Cuenta suspendida') }
    catch (e) { avisar(mensajeDe(e), 'error') } finally { setOcupado(null) }
  }
  return (
    <section className="m-card m-pila" aria-labelledby="t-admin">
      <div className="m-pila-chica"><h2 id="t-admin">Cuentas por verificar</h2><p className="m-terciario">Confirma que el negocio existe y está en Durango antes de darle acceso.</p></div>
      {cargando ? <EsqueletoLista n={2} /> : error ? <EstadoError error={error} reintentar={recargar} />
        : !datos?.length ? <EstadoVacio icono="check" titulo="No hay cuentas pendientes" />
        : <ul className="m-lista" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {datos.map((x) => (
            <li key={x.id} className="m-renglon">
              <Avatar nombre={x.nombre} />
              <div className="m-renglon-cuerpo"><b>{x.nombre}</b><span className="m-terciario">{x.email} · {x.colonia}{x.telefono && ` · ${x.telefono}`}</span></div>
              <div className="m-fila" style={{ gap: 8 }}>
                <Boton chico variante="peligro" cargando={ocupado === `${x.id}suspender`} disabled={!!ocupado} onClick={() => accion(x.id, 'suspender')}>Rechazar</Boton>
                <Boton chico cargando={ocupado === `${x.id}verificar`} disabled={!!ocupado} onClick={() => accion(x.id, 'verificar')}>Verificar</Boton>
              </div>
            </li>
          ))}
        </ul>}
    </section>
  )
}
