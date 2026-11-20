// Navegación del panel: barra lateral agrupada (computadora) y barra inferior
// con «Más» (teléfono). Íconos propios en SVG, trazo redondeado, sin emojis.
import { useEffect, useState } from 'react'
import { Enlace } from './router'

const TRAZOS: Record<string, string[]> = {
  hoy: ['M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z'],
  asesor: ['M12 3l1.8 4.7 4.7 1.8-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z', 'M19 15l.8 2.2 2.2.8-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z'],
  red: ['M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z', 'M20 20l-4-4'],
  tratos: ['M17 3l4 4-4 4', 'M3 7h18', 'M7 21l-4-4 4-4', 'M21 17H3'],
  inventario: ['M3 7l9-4 9 4v10l-9 4-9-4z', 'M3 7l9 4 9-4', 'M12 11v10'],
  bodega: ['M3 21V9l9-5 9 5v12', 'M7 21v-8h10v8', 'M7 17h10'],
  vitrina: ['M4 9l1.5-5h13L20 9', 'M4 9v11h16V9', 'M4 9a2.7 2.7 0 0 0 5.3 0 2.7 2.7 0 0 0 5.4 0 2.7 2.7 0 0 0 5.3 0', 'M10 20v-5h4v5'],
  anaqueles: ['M4 8h3l2-3h6l2 3h3v11H4z', 'M12 9.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z'],
  pedidos: ['M2 6h12v10H2z', 'M14 10h4l3 3v3h-7', 'M6 16a2 2 0 1 0 0 4 2 2 0 0 0 0-4z', 'M17 16a2 2 0 1 0 0 4 2 2 0 0 0 0-4z'],
  tienda: ['M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z', 'M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9 7 7M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1'],
  campana: ['M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z', 'M10 21a2 2 0 0 0 4 0'],
  mas: ['M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z'],
  cerrar: ['M6 6l12 12M18 6 6 18'],
}

export function Icono({ n, tam = 22 }: { n: string; tam?: number }) {
  return (
    <svg width={tam} height={tam} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      {(TRAZOS[n] ?? []).map((d, i) => <path key={i} d={d} />)}
    </svg>
  )
}

export type Seccion = { href: string; texto: string; icono: string; color: string; n?: number; desc: string }
export type Grupo = { titulo: string; secciones: Seccion[] }

export function gruposPanel(r?: { sin_pedir?: number; tratos_pendientes?: number; atrasados?: number; sin_foto_hoy?: number } | null): Grupo[] {
  return [
    { titulo: '', secciones: [{ href: '/panel', texto: 'Hoy', icono: 'hoy', color: '#e5484d', n: r?.sin_pedir, desc: 'Lo que se te acaba' }] },
    { titulo: 'Vender', secciones: [
      { href: '/panel/productos', texto: 'Inventario', icono: 'inventario', color: '#e0871b', desc: 'Existencias y precios' },
      { href: '/panel/vitrina', texto: 'Mi vitrina', icono: 'vitrina', color: '#c2185b', desc: 'Lo que ofreces a la red' },
    ] },
    { titulo: 'Surtir', secciones: [
      { href: '/panel/pedidos', texto: 'Pedidos', icono: 'pedidos', color: '#2f5da8', n: r?.atrasados, desc: 'A tus proveedores' },
      { href: '/panel/anaqueles', texto: 'Anaqueles', icono: 'anaqueles', color: '#00897b', n: r?.sin_foto_hoy, desc: 'Foto del día' },
      { href: '/panel/bodega', texto: 'Mi bodega', icono: 'bodega', color: '#0097a7', desc: 'Ármala en 3D o 2D' },
    ] },
    { titulo: 'Red de comercio', secciones: [
      { href: '/panel/red', texto: 'Red', icono: 'red', color: '#3f51b5', desc: 'Quién lo tiene más barato' },
      { href: '/panel/tratos', texto: 'Tratos', icono: 'tratos', color: '#e0871b', n: r?.tratos_pendientes, desc: 'Negociaciones' },
      { href: '/panel/asesor', texto: 'Asesor', icono: 'asesor', color: '#8e44ad', desc: 'Pregúntale a tu negocio' },
    ] },
    { titulo: 'Mi negocio', secciones: [
      { href: '/panel/tienda', texto: 'Mi tienda', icono: 'tienda', color: '#5d6b64', desc: 'Datos y ajustes' },
    ] },
  ]
}

const activa = (href: string, camino: string) => (href === '/panel' ? camino === '/panel' : camino.startsWith(href))

function Item({ s, camino, grande }: { s: Seccion; camino: string; grande?: boolean }) {
  const on = activa(s.href, camino)
  return (
    <Enlace href={s.href} className={`nav-item ${on ? 'activo' : ''} ${grande ? 'grande' : ''}`} style={{ '--c': s.color } as React.CSSProperties}
      aria-current={on ? 'page' : undefined}>
      <span className="nav-ico"><Icono n={s.icono} tam={grande ? 24 : 20} /></span>
      <span className="nav-txt"><b>{s.texto}</b>{grande && <small>{s.desc}</small>}</span>
      {s.n ? <span className="burbuja">{s.n}</span> : null}
    </Enlace>
  )
}

/** Barra lateral (pantallas anchas). */
export function Lateral({ grupos, camino, tienda }: { grupos: Grupo[]; camino: string; tienda: { nombre: string; slug?: string } }) {
  const nombre = tienda.nombre.replace(/ \(demo\)$/, '')
  const ini = nombre.split(' ').filter((p) => /^\p{L}/u.test(p)).slice(0, 2).map((p) => p[0].toUpperCase()).join('') || 'T'
  return (
    <aside className="lateral" aria-label="Secciones del panel">
      <Enlace href="/panel/tienda" className="lateral-tienda">
        <span className="avatar">{ini}</span>
        <span><b>{nombre}</b>{tienda.slug && <small>@{tienda.slug}</small>}</span>
      </Enlace>
      {grupos.map((g) => (
        <div key={g.titulo || 'inicio'} className="lateral-grupo">
          {g.titulo && <h4>{g.titulo}</h4>}
          {g.secciones.map((s) => <Item key={s.href} s={s} camino={camino} />)}
        </div>
      ))}
    </aside>
  )
}

/** Barra de abajo (teléfono): 4 secciones + «Más», que abre todas agrupadas. */
export function BarraAbajo({ grupos, camino }: { grupos: Grupo[]; camino: string }) {
  const [abierta, setAbierta] = useState(false)
  useEffect(() => { setAbierta(false) }, [camino])
  useEffect(() => {
    if (!abierta) return
    const f = (e: KeyboardEvent) => e.key === 'Escape' && setAbierta(false)
    window.addEventListener('keydown', f)
    return () => window.removeEventListener('keydown', f)
  }, [abierta])
  const todas = grupos.flatMap((g) => g.secciones)
  const fijas = ['/panel', '/panel/productos', '/panel/bodega', '/panel/red'].map((h) => todas.find((s) => s.href === h)!)
  const enMas = !fijas.some((s) => activa(s.href, camino))
  const pendMas = todas.filter((s) => !fijas.includes(s)).reduce((a, s) => a + (s.n ?? 0), 0)
  return (
    <>
      <nav className="barra-abajo" aria-label="Secciones">
        {fijas.map((s) => (
          <Enlace key={s.href} href={s.href} className={`tab ${activa(s.href, camino) ? 'activo' : ''}`} style={{ '--c': s.color } as React.CSSProperties}>
            <span className="nav-ico"><Icono n={s.icono} /></span>{s.texto}
            {s.n ? <span className="burbuja">{s.n}</span> : null}
          </Enlace>
        ))}
        <button className={`tab ${enMas || abierta ? 'activo' : ''}`} style={{ '--c': '#0e7c5a' } as React.CSSProperties} onClick={() => setAbierta(true)} aria-expanded={abierta}>
          <span className="nav-ico"><Icono n="mas" /></span>Más
          {pendMas ? <span className="burbuja">{pendMas}</span> : null}
        </button>
      </nav>
      {abierta && (
        <div className="hoja-fondo" onClick={() => setAbierta(false)}>
          <div className="hoja-mas" role="dialog" aria-label="Todas las secciones" onClick={(e) => e.stopPropagation()}>
            <div className="hoja-asa" />
            <div className="fila entre"><h3 style={{ margin: 0 }}>Todo tu negocio</h3>
              <button className="boton claro chico" onClick={() => setAbierta(false)} aria-label="Cerrar"><Icono n="cerrar" tam={18} /></button></div>
            {grupos.map((g, gi) => (
              <div key={g.titulo || 'inicio'} className="hoja-grupo" style={{ animationDelay: `${gi * 50}ms` }}>
                {g.titulo && <h4>{g.titulo}</h4>}
                <div className="hoja-rejilla">{g.secciones.map((s) => <Item key={s.href} s={s} camino={camino} grande />)}</div>
              </div>
            ))}
          </div>
        </div>
      )}
    </>
  )
}
