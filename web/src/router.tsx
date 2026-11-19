import { useEffect, useState, type AnchorHTMLAttributes, type MouseEvent } from 'react'

// Enrutador mínimo con la History API (no necesita librería).
// La app vive bajo BASE (/anaquel/); dentro del código las rutas van sin él.

export const BASE = import.meta.env.BASE_URL.replace(/\/$/, '')
export const url = (ruta: string) => (ruta.startsWith('http') ? ruta : BASE + ruta)
const actual = () => (location.pathname.startsWith(BASE) ? location.pathname.slice(BASE.length) || '/' : location.pathname) + location.search

export function ir(ruta: string, reemplazar = false) {
  if (reemplazar) history.replaceState(null, '', url(ruta))
  else history.pushState(null, '', url(ruta))
  window.dispatchEvent(new Event('anaquel:ruta'))
  window.scrollTo(0, 0)
}

export function useRuta(): string {
  const [ruta, setRuta] = useState(actual())
  useEffect(() => {
    const f = () => setRuta(actual())
    window.addEventListener('popstate', f)
    window.addEventListener('anaquel:ruta', f)
    return () => { window.removeEventListener('popstate', f); window.removeEventListener('anaquel:ruta', f) }
  }, [])
  return ruta
}

export function Enlace({ href, onClick, ...resto }: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) {
  return (
    <a
      href={url(href)}
      onClick={(e: MouseEvent<HTMLAnchorElement>) => {
        onClick?.(e)
        if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.shiftKey || href.startsWith('http')) return
        e.preventDefault()
        ir(href)
      }}
      {...resto}
    />
  )
}
