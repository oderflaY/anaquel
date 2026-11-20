import { lazy, Suspense, useEffect, useState } from 'react'
import { api, guardarToken, token, type Resumen, type Tienda } from './api'
import { Enlace, ir, useRuta } from './router'
import { Toasts } from './ui'
import { BarraAbajo, Icono, Lateral, gruposPanel } from './Navegacion'
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
import Red from './pages/Red'
import Tratos, { TratoDetalle } from './pages/Tratos'
import Asesor from './pages/Asesor'
import Vitrina from './pages/Vitrina'
import Avisos from './pages/Avisos'
import RedPublica from './pages/RedPublica'

// El 3D (three.js) se baja solo cuando se abre.
const Bodega3D = lazy(() => import('./pages/Bodega3D'))
const Grafo = lazy(() => import('./pages/Grafo'))
// El mercado (servicios en services/) también se baja solo cuando se abre.
const Mercado = lazy(() => import('./mercado/Mercado'))

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

  // Anaquel Mercado: tiene su propio encabezado, sesión (cookie) y diseño.
  if (camino === '/mercado' || camino.startsWith('/mercado/')) {
    return <Suspense fallback={null}><Mercado ruta={ruta} /></Suspense>
  }

  // Páginas públicas
  let pagina: React.ReactNode
  const enPanel = camino.startsWith('/panel')
  const m3d = camino.match(/^\/t\/([^/]+)\/3d$/)
  if (m3d) pagina = <Bodega3D slug={decodeURIComponent(m3d[1])} />
  else if (camino.startsWith('/t/')) pagina = <TiendaPublica slug={decodeURIComponent(camino.slice(3))} />
  else if (camino === '/productores') pagina = <Productores />
  else if (camino === '/red') pagina = <RedPublica consulta={ruta.split('?')[1] ?? ''} />
  else if (camino === '/entrar') pagina = <Entrar alEntrar={entrar} />
  else if (enPanel) {
    if (!token()) { queueMicrotask(() => ir('/entrar', true)); pagina = null }
    else if (!tienda) pagina = <div className="vacio-lista">{revisada ? 'No se pudo cargar tu tienda.' : 'Cargando…'}</div>
    else {
      const s: Sesion = { tienda, resumen, refrescar, salir }
      const m = camino.match(/^\/panel\/anaqueles\/(\d+)/)
      const mt = camino.match(/^\/panel\/tratos\/(\d+)/)
      pagina = m ? <AnaquelDetalle id={Number(m[1])} sesion={s} />
        : mt ? <TratoDetalle id={Number(mt[1])} sesion={s} />
        : camino === '/panel/tratos' ? <Tratos />
        : camino === '/panel/red' ? <Red sesion={s} consulta={ruta.split('?')[1] ?? ''} />
        : camino === '/panel/asesor' ? <Asesor sesion={s} />
        : camino === '/panel/bodega' ? <Bodega3D sesion={s} />
        : camino === '/panel/grafo' ? <Grafo />
        : camino === '/panel/vitrina' ? <Vitrina sesion={s} />
        : camino === '/panel/avisos' ? <Avisos sesion={s} />
        : camino === '/panel/anaqueles' ? <Anaqueles sesion={s} />
        : camino === '/panel/pedidos' ? <Pedidos sesion={s} />
        : camino === '/panel/productos' ? <Productos sesion={s} />
        : camino === '/panel/proveedores' ? <Proveedores sesion={s} />
        : camino === '/panel/tienda' ? <MiTienda sesion={s} />
        : <Hoy sesion={s} />
    }
  } else pagina = <Portada conSesion={!!tienda} />

  const grupos = gruposPanel(resumen)

  // Dentro de la app (WebView) va sin encabezado: la app ya tiene el suyo.
  const enApp = new URLSearchParams(ruta.split('?')[1] ?? '').get('app') === '1'

  return (
    <>
      {!enApp && <header className="barra">
        <div className={`contenedor ${enPanel && tienda ? 'panel-ancho' : ''}`}>
          <Enlace href={tienda ? '/panel' : '/'} className="logo">
            <img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="" /> <span>Anaquel</span>
          </Enlace>
          {enPanel && tienda ? <div style={{ flex: 1 }} /> : null}
          {enPanel && tienda ? (
            <Enlace href="/panel/avisos" className={`campana ${camino === '/panel/avisos' ? 'activo' : ''}`} aria-label={`Avisos${resumen?.avisos ? `: ${resumen.avisos} nuevos` : ''}`}>
              <Icono n="campana" />{resumen?.avisos ? <span className="burbuja">{resumen.avisos}</span> : null}
            </Enlace>
          ) : (
            <nav className="nav" aria-label="Principal">
              <Enlace href="/mercado">Mercado</Enlace>
              <Enlace href="/red" className={camino === '/red' ? 'activo' : ''}>Red local</Enlace>
              <Enlace href="/productores" className={camino === '/productores' ? 'activo' : ''}>Productores</Enlace>
              <Enlace href={tienda ? '/panel' : '/entrar'}>{tienda ? 'Mi panel' : 'Entrar'}</Enlace>
            </nav>
          )}
        </div>
      </header>}
      {enPanel && tienda && !enApp ? (
        // Panel: barra lateral agrupada en computadora y barra de abajo en teléfono.
        <div className="panel-marco">
          <Lateral grupos={grupos} camino={camino} tienda={tienda} />
          <main key={camino} className="panel-contenido">
            <Suspense fallback={<div className="vacio-lista">Cargando el 3D…</div>}>{pagina}</Suspense>
          </main>
          <BarraAbajo grupos={grupos} camino={camino} />
        </div>
      ) : (
        <main className={`contenedor ${enApp ? 'en-app' : ''}`} style={{ paddingTop: enApp ? '.6rem' : '1.25rem', paddingBottom: '2rem' }}>
          <Suspense fallback={<div className="vacio-lista">Cargando el 3D…</div>}>{pagina}</Suspense>
        </main>
      )}
      <Toasts />
    </>
  )
}
