import { useEffect, useState } from 'react'
import { api, guardarToken, token, type Resumen, type Tienda } from './api'
import { Enlace, ir, useRuta } from './router'
import { Toasts } from './ui'
import Portada from './pages/Portada'
import Entrar from './pages/Entrar'
import Hoy from './pages/Hoy'
import Anaqueles from './pages/Anaqueles'
import AnaquelDetalle from './pages/AnaquelDetalle'
import Pedidos from './pages/Pedidos'
import Productos from './pages/Productos'
import Proveedores from './pages/Proveedores'
import MiTienda from './pages/MiTienda'
import TiendaPublica from './pages/TiendaPublica'
import Productores from './pages/Productores'

export type Sesion = { tienda: Tienda; resumen: Resumen | null; refrescar: () => void; salir: () => void }

export default function App() {
  const ruta = useRuta()
  const camino = ruta.split('?')[0]
  const [tienda, setTienda] = useState<Tienda | null>(null)
  const [resumen, setResumen] = useState<Resumen | null>(null)
  const [revisada, setRevisada] = useState(!token())

  const refrescar = () => {
    if (!token()) return
    api.get<Tienda>('/yo').then(setTienda).catch(() => setTienda(null)).finally(() => setRevisada(true))
    api.get<Resumen>('/resumen').then(setResumen).catch(() => {})
  }
  useEffect(refrescar, [ruta])
  useEffect(() => {
    const f = () => { setTienda(null); setResumen(null); ir('/entrar', true) }
    window.addEventListener('anaquel:salir', f)
    return () => window.removeEventListener('anaquel:salir', f)
  }, [])

  const salir = () => { guardarToken(null); setTienda(null); setResumen(null); ir('/') }
  const entrar = (t: Tienda) => { setTienda(t); ir('/panel') }

  // Páginas públicas
  let pagina: React.ReactNode
  const enPanel = camino.startsWith('/panel')
  if (camino.startsWith('/t/')) pagina = <TiendaPublica slug={decodeURIComponent(camino.slice(3))} />
  else if (camino === '/productores') pagina = <Productores />
  else if (camino === '/entrar') pagina = <Entrar alEntrar={entrar} />
  else if (enPanel) {
    if (!token()) { queueMicrotask(() => ir('/entrar', true)); pagina = null }
    else if (!tienda) pagina = <div className="vacio-lista">{revisada ? 'No se pudo cargar tu tienda.' : 'Cargando…'}</div>
    else {
      const s: Sesion = { tienda, resumen, refrescar, salir }
      const m = camino.match(/^\/panel\/anaqueles\/(\d+)/)
      pagina = m ? <AnaquelDetalle id={Number(m[1])} sesion={s} />
        : camino === '/panel/anaqueles' ? <Anaqueles sesion={s} />
        : camino === '/panel/pedidos' ? <Pedidos sesion={s} />
        : camino === '/panel/productos' ? <Productos sesion={s} />
        : camino === '/panel/proveedores' ? <Proveedores sesion={s} />
        : camino === '/panel/tienda' ? <MiTienda sesion={s} />
        : <Hoy sesion={s} />
    }
  } else pagina = <Portada conSesion={!!tienda} />

  const pestanas: [string, string, number?][] = [
    ['/panel', 'Hoy', resumen?.sin_pedir],
    ['/panel/anaqueles', 'Anaqueles'],
    ['/panel/pedidos', 'Pedidos', resumen?.atrasados],
    ['/panel/productos', 'Productos'],
    ['/panel/proveedores', 'Proveedores'],
    ['/panel/tienda', 'Mi tienda'],
  ]

  return (
    <>
      <header className="barra">
        <div className="contenedor">
          <Enlace href={tienda ? '/panel' : '/'} className="logo">
            <img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" /> <span>Anaquel</span>
          </Enlace>
          {enPanel && tienda ? (
            <nav className="nav" aria-label="Panel">
              {pestanas.map(([href, txt, n]) => (
                <Enlace key={href} href={href} className={(href === '/panel' ? camino === '/panel' : camino.startsWith(href)) ? 'activo' : ''}>
                  {txt}{n ? <span className="burbuja">{n}</span> : null}
                </Enlace>
              ))}
            </nav>
          ) : (
            <nav className="nav" aria-label="Principal">
              <Enlace href="/productores" className={camino === '/productores' ? 'activo' : ''}>Productores</Enlace>
              <Enlace href={tienda ? '/panel' : '/entrar'}>{tienda ? 'Mi panel' : 'Entrar'}</Enlace>
            </nav>
          )}
        </div>
      </header>
      <main className="contenedor" style={{ paddingTop: '1.25rem', paddingBottom: '2rem' }}>{pagina}</main>
      <Toasts />
    </>
  )
}
