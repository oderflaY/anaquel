import { useEffect, useState, type FormEvent } from 'react'
import { Enlace, ir } from '../../router'
import { api, UNIDADES, urlMedia, type Categoria, type Foto, type Publicacion, type Unidad } from '../api'
import { Icono } from '../iconos'
import { Alerta, Boton, BotonIcono, Campo, Cargando, Esqueleto, EstadoError, mensajeDe, useConsulta, useTitulo, avisar } from '../ui'

type Nueva = { archivo: File; vista: string; alt: string }
type Errores = Partial<Record<'titulo' | 'precio' | 'categoria' | 'existencias' | 'minimo' | 'general', string>>

/** "65.5" → 6550 sin errores de redondeo de flotantes. */
function aCentavos(t: string): number | null {
  const m = t.replace(/[\s$,]/g, '').match(/^(\d{1,7})(?:\.(\d{1,2}))?$/)
  if (!m) return null
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'))
}

export default function Publicar({ id }: { id?: number }) {
  useTitulo(id ? 'Editar publicación' : 'Publicar producto')
  const cats = useConsulta((s) => api.get<Categoria[]>('/mercado/categorias', s), [])
  const existente = useConsulta((s) => (id ? api.get<{ publicacion: Publicacion }>(`/mercado/publicaciones/${id}`, s) : Promise.resolve(null)), [id])
  const [f, setF] = useState({ titulo: '', descripcion: '', categoria: '', precio: '', unidad: 'pieza' as Unidad, existencias: '', minimo: '1', sinConteo: false })
  const [fotos, setFotos] = useState<Foto[]>([])
  const [nuevas, setNuevas] = useState<Nueva[]>([])
  const [errores, setErrores] = useState<Errores>({})
  const [guardando, setGuardando] = useState<string | null>(null)

  useEffect(() => {
    const p = existente.datos?.publicacion
    if (!p) return
    setF({ titulo: p.titulo, descripcion: p.descripcion ?? '', categoria: p.categoria, precio: (p.precio_c / 100).toString(), unidad: p.unidad,
      existencias: p.existencias?.toString() ?? '', minimo: String(p.minimo), sinConteo: p.existencias === null })
    setFotos(p.fotos ?? [])
  }, [existente.datos])
  useEffect(() => () => nuevas.forEach((n) => URL.revokeObjectURL(n.vista)), []) // eslint-disable-line react-hooks/exhaustive-deps

  if (id && existente.cargando) return <Cargando><div className="m-card m-pila">{[0, 1, 2, 3].map((i) => <Esqueleto key={i} style={{ height: 44, borderRadius: 12 }} />)}</div></Cargando>
  if (id && (existente.error || (existente.datos && !existente.datos.publicacion.es_mia))) return <EstadoError error={existente.error ?? 'Solo puedes editar tus publicaciones.'} />

  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => { setF({ ...f, [k]: e.target.value }); setErrores({ ...errores, [k === 'precio' ? 'precio' : k]: undefined }) }

  const elegirFotos = (lista: FileList | null) => {
    if (!lista) return
    const libres = 6 - fotos.length - nuevas.length
    const ok = [...lista].filter((a) => /^image\/(jpeg|png|webp)$/.test(a.type) && a.size <= 8 * 1024 * 1024).slice(0, Math.max(0, libres))
    if (ok.length < lista.length) avisar('Algunas fotos no se agregaron: usa JPG, PNG o WebP de hasta 8 MB (máximo 6).', 'error')
    setNuevas([...nuevas, ...ok.map((a) => ({ archivo: a, vista: URL.createObjectURL(a), alt: f.titulo }))])
  }

  const quitarFoto = async (foto: Foto) => {
    if (!id || !foto.id) return
    try { await api.del(`/mercado/publicaciones/${id}/fotos/${foto.id}`); setFotos(fotos.filter((x) => x.id !== foto.id)) }
    catch (e) { avisar(mensajeDe(e), 'error') }
  }

  const guardar = async (e: FormEvent) => {
    e.preventDefault()
    const err: Errores = {}
    const precio = aCentavos(f.precio)
    if (f.titulo.trim().length < 3) err.titulo = 'Escribe al menos 3 letras'
    if (precio === null || precio < 100) err.precio = 'Escribe un precio válido, desde $1 (ej. 65 o 65.50)'
    if (!f.categoria) err.categoria = 'Elige una categoría'
    const exist = f.sinConteo ? null : Number(f.existencias)
    if (!f.sinConteo && (f.existencias === '' || !Number.isInteger(exist) || exist! < 0)) err.existencias = 'Escribe cuántas tienes (0 o más), o marca «sin conteo»'
    const minimo = Number(f.minimo)
    if (!Number.isInteger(minimo) || minimo < 1) err.minimo = 'Mínimo 1'
    setErrores(err)
    if (Object.keys(err).length) { document.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(); return }
    const datos = { titulo: f.titulo.trim(), descripcion: f.descripcion.trim(), categoria: f.categoria, precio_c: precio, unidad: f.unidad, existencias: exist, minimo }
    setGuardando('datos')
    try {
      let pid = id
      if (id) await api.put(`/mercado/publicaciones/${id}`, datos)
      else pid = (await api.post<{ id: number }>('/mercado/publicaciones', datos)).id
      for (let i = 0; i < nuevas.length; i++) {
        setGuardando(`foto ${i + 1} de ${nuevas.length}`)
        const fd = new FormData()
        fd.append('foto', nuevas[i].archivo)
        fd.append('alt', nuevas[i].alt || datos.titulo)
        try { await api.post(`/mercado/publicaciones/${pid}/fotos`, fd) }
        catch (ex) { avisar(`Una foto no se subió: ${mensajeDe(ex)}`, 'error') }
      }
      avisar(id ? 'Cambios guardados' : 'Producto publicado')
      ir(`/mercado/p/${pid}`)
    } catch (ex) { setErrores({ general: mensajeDe(ex) }) } finally { setGuardando(null) }
  }

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }} className="m-pila">
      <nav className="m-migas" aria-label="Ruta"><Enlace href="/mercado/vender">Vender</Enlace><Icono nombre="adelante" /><span aria-current="page">{id ? 'Editar' : 'Nuevo producto'}</span></nav>
      <h1>{id ? 'Editar publicación' : 'Publicar producto'}</h1>
      <form className="m-pila" onSubmit={guardar} noValidate>
        <section className="m-card m-pila" aria-labelledby="t-fotos">
          <div className="m-pila-chica"><h2 id="t-fotos">Fotos</h2><p className="m-terciario">Hasta 6. La primera es la portada. Quitamos la ubicación y los datos ocultos de cada foto.</p></div>
          <div className="m-fila" style={{ alignItems: 'stretch' }}>
            {fotos.map((x) => (
              <div key={x.url} className="m-miniatura" style={{ width: 96, height: 96, position: 'relative' }}>
                <img src={urlMedia(x.url)} alt={x.alt} />
                <BotonIcono etiqueta={`Quitar foto: ${x.alt}`} icono="cerrar" onClick={() => quitarFoto(x)} style={{ position: 'absolute', top: 2, right: 2, width: 32, height: 32, background: 'var(--color-superficie)' }} />
              </div>
            ))}
            {nuevas.map((n, i) => (
              <div key={n.vista} className="m-pila-chica" style={{ width: 140 }}>
                <div className="m-miniatura" style={{ width: 140, height: 96, position: 'relative' }}>
                  <img src={n.vista} alt="" />
                  <BotonIcono etiqueta="Quitar esta foto" icono="cerrar" onClick={() => { URL.revokeObjectURL(n.vista); setNuevas(nuevas.filter((_, j) => j !== i)) }} style={{ position: 'absolute', top: 2, right: 2, width: 32, height: 32, background: 'var(--color-superficie)' }} />
                </div>
                <label className="m-campo"><span className="m-terciario">Describe la foto</span>
                  <input value={n.alt} maxLength={140} onChange={(e) => setNuevas(nuevas.map((x, j) => (j === i ? { ...x, alt: e.target.value } : x)))} style={{ minHeight: 36 }} />
                </label>
              </div>
            ))}
            {fotos.length + nuevas.length < 6 && (
              <label className="m-boton m-boton--secundario" style={{ width: 96, height: 96, flexDirection: 'column', gap: 4 }}>
                <Icono nombre="camara" /><span className="m-chico">Agregar</span>
                <input type="file" accept="image/jpeg,image/png,image/webp" multiple className="sr-only" onChange={(e) => { elegirFotos(e.target.files); e.target.value = '' }} />
              </label>
            )}
          </div>
        </section>

        <section className="m-card m-rejilla-campos" aria-label="Datos del producto">
          <Campo etiqueta="Título" error={errores.titulo} className="m-completo" ayuda="Lo que buscaría un cliente, ej. «Gorra bordada con tu logo».">
            {(p) => <input {...p} maxLength={80} value={f.titulo} onChange={set('titulo')} />}
          </Campo>
          <Campo etiqueta="Precio (MXN)" error={errores.precio}>
            {(p) => <input {...p} inputMode="decimal" placeholder="65.00" value={f.precio} onChange={set('precio')} />}
          </Campo>
          <Campo etiqueta="Se vende por">
            {(p) => <select {...p} value={f.unidad} onChange={set('unidad')}>{Object.entries(UNIDADES).map(([k, [s]]) => <option key={k} value={k}>{s}</option>)}</select>}
          </Campo>
          <Campo etiqueta="Categoría" error={errores.categoria}>
            {(p) => (
              <select {...p} value={f.categoria} onChange={set('categoria')} disabled={cats.cargando}>
                <option value="">{cats.cargando ? 'Cargando…' : 'Elige una'}</option>
                {cats.datos?.map((c) => <option key={c.clave} value={c.clave}>{c.nombre}</option>)}
              </select>
            )}
          </Campo>
          <Campo etiqueta="Compra mínima" error={errores.minimo} ayuda="Para mayoreo, ej. 50 piezas.">
            {(p) => <input {...p} type="number" min={1} inputMode="numeric" value={f.minimo} onChange={set('minimo')} />}
          </Campo>
          <Campo etiqueta="Existencias" error={errores.existencias} className="m-completo">
            {(p) => (
              <div className="m-fila">
                <input {...p} type="number" min={0} inputMode="numeric" style={{ maxWidth: 200 }} disabled={f.sinConteo} value={f.sinConteo ? '' : f.existencias} onChange={set('existencias')} />
                <label className="m-fila" style={{ gap: 8 }}><input type="checkbox" checked={f.sinConteo} onChange={(e) => setF({ ...f, sinConteo: e.target.checked })} style={{ width: 18, height: 18, accentColor: 'var(--color-marca)' }} />Sin conteo (servicios o bajo pedido)</label>
              </div>
            )}
          </Campo>
          <Campo etiqueta="Descripción" opcional className="m-completo">
            {(p) => <textarea {...p} maxLength={1000} value={f.descripcion} onChange={set('descripcion')} placeholder="Material, tallas, tiempo de entrega…" />}
          </Campo>
        </section>
        {errores.general && <Alerta tipo="error">{errores.general}</Alerta>}
        <div className="m-fila" style={{ justifyContent: 'flex-end' }}>
          <Boton variante="secundario" disabled={!!guardando} onClick={() => history.back()}>Cancelar</Boton>
          <Boton type="submit" cargando={!!guardando}>{guardando && guardando !== 'datos' ? `Subiendo ${guardando}…` : id ? 'Guardar cambios' : 'Publicar'}</Boton>
        </div>
      </form>
    </div>
  )
}
