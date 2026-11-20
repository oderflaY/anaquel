// Anaquel Mercado: mercado local de Durango, solo para clientes verificados.
// Se carga aparte (lazy) para no pesarle al panel ni a la bodega 3D.
import { useEffect, useRef, useState, type ReactNode } from 'react'
import './tokens.css'
import './mercado.css'
import { Enlace, ir } from '../router'
import { api, EVENTO_SESION, pesos, type Cuenta, type Registro } from './api'
import { Icono } from './iconos'
import { Avatar, avisar, Esqueleto, EsqueletoRejilla, Toasts } from './ui'
import Entrar from './paginas/Entrar'
import Registro_ from './paginas/Registro'
import Revision from './paginas/Revision'
import Inicio from './paginas/Inicio'
import Detalle from './paginas/Detalle'
import Vendedor from './paginas/Vendedor'
import Reels from './paginas/Reels'
import Ordenes, { OrdenDetalle } from './paginas/Ordenes'
import Vender from './paginas/Vender'
import Publicar from './paginas/Publicar'
import TerminalVirtual from './paginas/TerminalVirtual'
import CuentaPagina from './paginas/Cuenta'

export type SesionMercado = { cuenta: Cuenta; recargar: () => void; salir: () => Promise<void> }

const PUBLICAS = ['/mercado/entrar', '/mercado/registro']

export default function Mercado({ ruta }: { ruta: string }) {
  const [camino, consulta = ''] = ruta.split('?')
  const params = new URLSearchParams(consulta)
  const [cuenta, setCuenta] = useState<Cuenta | null>(null)
  const [revisada, setRevisada] = useState(false)

  const recargar = () => {
    api.get<{ cuenta: Cuenta | null }>('/acceso/sesion').then((r) => setCuenta(r.cuenta)).catch(() => setCuenta(null)).finally(() => setRevisada(true))
  }
  useEffect(() => {
    document.body.dataset.zona = 'mercado'
    recargar()
    const f = (e: Event) => {
      const clave = (e as CustomEvent<string>).detail
      if (clave === 'sin_sesion') { setCuenta(null); if (!PUBLICAS.includes(location.pathname.replace(/^.*\/mercado/, '/mercado'))) ir('/mercado/entrar', true) }
      else recargar()
    }
    window.addEventListener(EVENTO_SESION, f)
    return () => { delete document.body.dataset.zona; window.removeEventListener(EVENTO_SESION, f) }
  }, [])

  const salir = async () => {
    await api.post('/acceso/salir').catch(() => {})
    setCuenta(null)
    ir('/mercado/entrar')
  }
  const alEntrar = (c: Cuenta) => { setCuenta(c); ir(params.get('volver')?.startsWith('/mercado') ? params.get('volver')! : '/mercado', true) }

  // ---- Elegir página ----
  let pagina: ReactNode
  const publica = PUBLICAS.includes(camino)
  if (camino === '/mercado/entrar') pagina = <Entrar alEntrar={alEntrar} />
  else if (camino === '/mercado/registro') pagina = <Registro_ alEntrar={alEntrar} />
  else if (!revisada) {
    pagina = (
      <div className="m-pila" style={{ gap: 'var(--e-6)' }}>
        <div className="m-pila"><Esqueleto className="m-esqueleto--titulo" style={{ width: 260 }} /><Esqueleto style={{ height: 44, borderRadius: 999 }} /></div>
        <EsqueletoRejilla />
      </div>
    )
  }
  else if (!cuenta) {
    queueMicrotask(() => ir(`/mercado/entrar?volver=${encodeURIComponent(camino)}`, true))
    pagina = null
  } else if (cuenta.estado !== 'verificada' && camino !== '/mercado/cuenta') pagina = <Revision cuenta={cuenta} salir={salir} />
  else {
    const s: SesionMercado = { cuenta, recargar, salir }
    const m = (re: RegExp) => camino.match(re)
    let x: RegExpMatchArray | null
    pagina = (x = m(/^\/mercado\/p\/(\d+)$/)) ? <Detalle id={Number(x[1])} sesion={s} />
      : (x = m(/^\/mercado\/v\/([a-z0-9-]+)$/)) ? <Vendedor slug={x[1]} />
      : camino === '/mercado/reels' ? <Reels />
      : (x = m(/^\/mercado\/ordenes\/(\d+)$/)) ? <OrdenDetalle id={Number(x[1])} sesion={s} />
      : camino === '/mercado/ordenes' ? <Ordenes rol={params.get('rol') === 'ventas' ? 'ventas' : 'compras'} sesion={s} />
      : camino === '/mercado/vender/nueva' ? <Publicar />
      : (x = m(/^\/mercado\/vender\/editar\/(\d+)$/)) ? <Publicar id={Number(x[1])} />
      : camino === '/mercado/vender' ? <Vender sesion={s} pestana={params.get('tab') ?? 'publicaciones'} />
      : camino === '/mercado/terminal' ? <TerminalVirtual />
      : camino === '/mercado/cuenta' ? <CuentaPagina sesion={s} />
      : <Inicio q={params.get('q') ?? ''} categoria={params.get('categoria') ?? ''} />
  }

  const conNav = !!cuenta && cuenta.estado === 'verificada' && !publica
  const enReels = camino === '/mercado/reels'
  const links: [string, string, string, boolean][] = [
    ['/mercado', 'Explorar', 'tienda', camino === '/mercado' || camino.startsWith('/mercado/p/') || camino.startsWith('/mercado/v/')],
    ['/mercado/reels', 'Reels', 'video', enReels],
    ['/mercado/ordenes', 'Mis órdenes', 'recibo', camino.startsWith('/mercado/ordenes')],
  ]
  if (cuenta?.vende) links.push(['/mercado/vender', 'Vender', 'etiqueta', camino.startsWith('/mercado/vender') || camino === '/mercado/terminal'])

  return (
    <div className="m-app">
      <a href="#contenido" className="m-saltar">Saltar al contenido</a>
      <header className="m-barra">
        <div className="m-contenedor">
          <Enlace href={conNav ? '/mercado' : '/mercado/entrar'} className="m-logo" aria-label="Anaquel Mercado, inicio">
            <img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" width={30} height={30} />
            <span>Anaquel <b>Mercado</b></span>
          </Enlace>
          {conNav && (
            <nav className="m-nav" aria-label="Principal">
              {links.map(([href, txt, ic, activo]) => (
                <Enlace key={href} href={href} aria-current={activo ? 'page' : undefined}><Icono nombre={ic} />{txt}</Enlace>
              ))}
            </nav>
          )}
          <span className="m-espacio" />
          {conNav && cuenta && <MenuCuenta cuenta={cuenta} salir={salir} />}
        </div>
      </header>
      <main id="contenido" className={enReels ? '' : 'm-principal'} tabIndex={-1}>
        {enReels ? pagina : <div className="m-contenedor">{pagina}</div>}
      </main>
      {conNav && (
        <nav className="m-nav-inferior" aria-label="Principal" style={{ ['--columnas' as string]: links.length }}>
          {links.map(([href, txt, ic, activo]) => (
            <Enlace key={href} href={href} aria-current={activo ? 'page' : undefined}><Icono nombre={ic} />{txt}</Enlace>
          ))}
        </nav>
      )}
      {cuenta?.vende && cuenta.estado === 'verificada' && <AvisosDePago />}
      <Toasts />
    </div>
  )
}

function MenuCuenta({ cuenta, salir }: { cuenta: Cuenta; salir: () => void }) {
  const [abierto, setAbierto] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const [tema, setTema] = useState(() => document.documentElement.dataset.theme ?? '')
  useEffect(() => {
    if (!abierto) return
    const fuera = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setAbierto(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') { setAbierto(false); (ref.current?.querySelector('button') as HTMLElement)?.focus() } }
    document.addEventListener('mousedown', fuera); document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', fuera); document.removeEventListener('keydown', esc) }
  }, [abierto])
  const oscuro = tema === 'dark' || (tema === '' && matchMedia('(prefers-color-scheme: dark)').matches)
  const cambiarTema = () => {
    const nuevo = oscuro ? 'light' : 'dark'
    document.documentElement.dataset.theme = nuevo
    try { localStorage.setItem('anaquel.tema', nuevo) } catch { /* sin almacenamiento */ }
    setTema(nuevo)
  }
  return (
    <div className="m-menu" ref={ref}>
      <button type="button" className="m-boton-icono" aria-haspopup="menu" aria-expanded={abierto} aria-label={`Cuenta de ${cuenta.nombre}`} onClick={() => setAbierto(!abierto)}>
        <Avatar nombre={cuenta.nombre} />
      </button>
      {abierto && (
        <div className="m-menu-panel" role="menu" onClick={(e) => { if ((e.target as HTMLElement).closest('a,button')) setAbierto(false) }}>
          <div className="m-menu-cabeza"><b>{cuenta.nombre}</b><div className="m-terciario">{cuenta.email}</div></div>
          <Enlace href="/mercado/cuenta" role="menuitem"><Icono nombre="usuario" />Mi cuenta e invitaciones</Enlace>
          {cuenta.tienda_slug && <Enlace href={`/t/${cuenta.tienda_slug}`} role="menuitem"><Icono nombre="tienda" />Mi tienda en Anaquel</Enlace>}
          <button type="button" role="menuitem" onClick={cambiarTema}><Icono nombre={oscuro ? 'sol' : 'luna'} />{oscuro ? 'Tema claro' : 'Tema oscuro'}</button>
          <button type="button" role="menuitem" onClick={salir}><Icono nombre="salir" />Salir</button>
        </div>
      )}
    </div>
  )
}

/** Escucha el registro de cobros del sistema de pagos: cuando la terminal
 *  notifica un pago (desde la app o directo en el aparato), avisa al vendedor. */
function AvisosDePago() {
  const ultimo = useRef<number | null>(null)
  useEffect(() => {
    let vivo = true
    const revisar = async () => {
      try {
        const r = await api.get<{ items: Registro[] }>(`/pagos/registros${ultimo.current ? `?desde=${ultimo.current}` : ''}`)
        if (!vivo) return
        if (ultimo.current !== null) {
          for (const x of [...r.items].reverse()) {
            avisar(`Pago recibido: ${pesos(x.monto_c)} en ${x.terminal || x.marca}${x.origen === 'terminal' ? ' (cobro directo)' : ''}`)
          }
        }
        if (r.items.length) ultimo.current = Math.max(ultimo.current ?? 0, ...r.items.map((x) => x.id))
        else if (ultimo.current === null) ultimo.current = 0
      } catch { /* sin conexión: se reintenta */ }
    }
    revisar()
    const t = setInterval(revisar, 8000)
    return () => { vivo = false; clearInterval(t) }
  }, [])
  return null
}
