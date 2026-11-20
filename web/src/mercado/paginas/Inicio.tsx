import { useEffect, useRef, useState } from 'react'
import { ir } from '../../router'
import { api, intercalar, type Categoria, type Espacios, type Pagina, type Publicacion } from '../api'
import { Icono, ICONO_CATEGORIA } from '../iconos'
import { BannerPatrocinado, Boton, Esqueleto, EsqueletoRejilla, EstadoError, EstadoVacio, mensajeDe, TarjetaProducto, useConsulta, useTitulo } from '../ui'

export default function Inicio({ q, categoria }: { q: string; categoria: string }) {
  useTitulo(q ? `Buscar «${q}»` : 'Explorar')
  const [texto, setTexto] = useState(q)
  // Páginas extra cargadas con «Ver más», atadas a la consulta que las pidió:
  // si cambia la búsqueda, se descartan (nunca se mezclan resultados).
  const clave = `${q}|${categoria}`
  const [extra, setExtra] = useState<{ clave: string; items: Publicacion[]; siguiente: number | null } | null>(null)
  const [cargandoMas, setCargandoMas] = useState(false)
  const [errorMas, setErrorMas] = useState<string | null>(null)
  const centinela = useRef<HTMLDivElement>(null)

  const cats = useConsulta((s) => api.get<Categoria[]>('/mercado/categorias', s), [])
  const consulta = (antes?: number) => {
    const p = new URLSearchParams()
    if (q) p.set('q', q)
    if (categoria) p.set('categoria', categoria)
    if (antes) p.set('antes', String(antes))
    return `/mercado/publicaciones?${p}`
  }
  const lista = useConsulta((s) => api.get<Pagina<Publicacion>>(consulta(), s), [q, categoria])
  // Anuncios: los decide el servidor (mismo orden en web y app). Si fallan, el catálogo sigue igual.
  const anuncios = useConsulta((s) => {
    const p = new URLSearchParams()
    if (q) p.set('q', q)
    if (categoria) p.set('categoria', categoria)
    return api.get<Espacios>(`/mercado/promociones/espacios?${p}`, s).catch(() => ({ banner: null, productos: [] }) as Espacios)
  }, [q, categoria])
  useEffect(() => { setTexto(q) }, [q])

  const navegar = (nq: string, ncat: string, reemplazar = false) => {
    const p = new URLSearchParams()
    if (nq.trim()) p.set('q', nq.trim())
    if (ncat) p.set('categoria', ncat)
    const s = p.toString()
    ir(`/mercado${s ? `?${s}` : ''}`, reemplazar)
  }

  // Búsqueda: actualiza la URL (se puede compartir y el botón "atrás" funciona).
  useEffect(() => {
    if (texto === q) return
    const t = setTimeout(() => navegar(texto, categoria, true), 350)
    return () => clearTimeout(t)
  }, [texto]) // eslint-disable-line react-hooks/exhaustive-deps


  const propia = extra?.clave === clave && !lista.cargando ? extra : null
  const mas = propia?.items ?? []
  const siguiente = lista.cargando ? null : propia ? propia.siguiente : lista.datos?.siguiente ?? null
  const cargarMas = async () => {
    if (!siguiente || cargandoMas) return
    const pedida = clave
    setCargandoMas(true); setErrorMas(null)
    try {
      const r = await api.get<Pagina<Publicacion>>(consulta(siguiente))
      setExtra((x) => ({ clave: pedida, items: [...(x?.clave === pedida ? x.items : []), ...r.items], siguiente: r.siguiente }))
    } catch (e) { setErrorMas(mensajeDe(e)) } finally { setCargandoMas(false) }
  }
  // Carga automática al llegar al final (con botón de respaldo).
  useEffect(() => {
    const el = centinela.current
    if (!el || !siguiente) return
    const o = new IntersectionObserver((e) => { if (e[0].isIntersecting) cargarMas() }, { rootMargin: '400px' })
    o.observe(el)
    return () => o.disconnect()
  }) // eslint-disable-line react-hooks/exhaustive-deps

  const organicos = [...(lista.datos?.items ?? []), ...mas]
  const items = intercalar(organicos, anuncios.datos?.productos ?? [])
  const banner = anuncios.datos?.banner
  const nombreCat = cats.datos?.find((c) => c.clave === categoria)?.nombre

  return (
    <div className="m-pila" style={{ gap: 'var(--e-6)' }}>
      <div className="m-pila">
        <h1>{q ? `Resultados para «${q}»` : nombreCat ?? 'Hecho y vendido en Durango'}</h1>
        <form role="search" className="m-buscador" style={{ maxWidth: 'none' }} onSubmit={(e) => { e.preventDefault(); navegar(texto, categoria) }}>
          <Icono nombre="buscar" />
          <label htmlFor="buscar" className="sr-only">Buscar productos o negocios</label>
          <input id="buscar" type="search" placeholder="Buscar gorras, queso, fletes…" value={texto} onChange={(e) => setTexto(e.target.value)} enterKeyHint="search" maxLength={80} />
        </form>
        <div className="m-chips" role="group" aria-label="Categorías">
          {cats.cargando
            ? Array.from({ length: 6 }, (_, i) => <Esqueleto key={i} style={{ width: 110, height: 40, borderRadius: 999, flexShrink: 0 }} />)
            : <>
              <button type="button" className="m-chip" aria-pressed={!categoria} onClick={() => navegar(q, '')}><Icono nombre="todo" />Todo</button>
              {cats.datos?.map((c) => (
                <button key={c.clave} type="button" className="m-chip" aria-pressed={categoria === c.clave} onClick={() => navegar(q, categoria === c.clave ? '' : c.clave)}>
                  <Icono nombre={ICONO_CATEGORIA[c.clave] ?? c.icono} />{c.nombre}
                </button>
              ))}
            </>}
        </div>
      </div>

      {lista.cargando ? <EsqueletoRejilla />
        : lista.error ? <EstadoError error={lista.error} reintentar={lista.recargar} />
        : items.length === 0 ? (
          <EstadoVacio icono="buscar" titulo={q ? 'No encontramos nada con esa búsqueda' : 'Aún no hay productos aquí'}
            texto={q ? 'Prueba con otra palabra o quita el filtro de categoría.' : 'Vuelve pronto: los negocios de Durango publican todos los días.'}
            accion={(q || categoria) && <Boton variante="secundario" onClick={() => { setTexto(''); navegar('', '') }}>Ver todo</Boton>} />
        ) : (
          <section aria-labelledby="t-productos">
            <h2 id="t-productos" className="sr-only">Productos</h2>
            <p className="sr-only" role="status">{items.length} productos{siguiente ? ', hay más' : ''}</p>
            {banner && <BannerPatrocinado b={banner} />}
            <div className="m-rejilla">{items.map((p) => <TarjetaProducto key={('promo_id' in p ? 'a' : 'o') + p.id} p={p} promo={'promo_id' in p ? p.promo_id : undefined} />)}</div>
            {siguiente && (
              <div className="m-cargar-mas" ref={centinela}>
                {errorMas ? <Boton variante="secundario" onClick={cargarMas}>Reintentar</Boton>
                  : <Boton variante="secundario" cargando={cargandoMas} onClick={cargarMas}>Ver más productos</Boton>}
              </div>
            )}
          </section>
        )}
    </div>
  )
}
