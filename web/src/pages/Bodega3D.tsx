// Bodega 3D de la tienda (basada en la de MatObra): el local a escala con
// cada producto en su entrepaño y su existencia real. Se le pregunta por voz o
// escrito "¿dónde está…?", contesta en voz alta, traza la ruta a pie y hace
// el recorrido. Mapas de calor (existencia, rotación, foto del día, valor),
// ficha con kárdex y movimientos, y editor para acomodar muebles y productos.
// Con `slug` es el recorrido público de una tienda (solo ver).
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, hace, numero, type Movimiento } from '../api'
import type { Sesion } from '../App'
import { Enlace } from '../router'
import { avisar, Estado, mensajeDe, useDatos } from '../ui'
import { Tienda3D, type Hover, type Modo, type Tema } from '../tienda3d/escena'
import { trazar, type Ruta } from '../tienda3d/ruta'
import {
  alturaHablada, calcularHuecos, describirLugar, estadoDe, huella, LADOS, normalizar, pesos, singular, TIPOS, unidades,
  type DatosBodega, type Lugar, type Mueble, type Plano, type TipoMueble,
} from '../tienda3d/tipos'

// ---------------------------------------------------------------- voz

interface Reconocedor {
  lang: string; interimResults: boolean; continuous: boolean; maxAlternatives: number
  onresult: ((e: { resultIndex: number; results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } }> }) => void) | null
  onerror: ((e: { error: string }) => void) | null
  onend: (() => void) | null
  start(): void; stop(): void
}
const W = window as unknown as { SpeechRecognition?: new () => Reconocedor; webkitSpeechRecognition?: new () => Reconocedor }
const CrearReconocedor = W.SpeechRecognition ?? W.webkitSpeechRecognition

function hablar(textos: string[], onPaso?: (i: number) => void) {
  if (!('speechSynthesis' in window)) return
  speechSynthesis.cancel()
  const voces = speechSynthesis.getVoices()
  const voz = voces.find((v) => v.lang === 'es-MX') ?? voces.find((v) => v.lang.startsWith('es')) ?? null
  textos.forEach((t, i) => {
    const u = new SpeechSynthesisUtterance(t)
    u.lang = 'es-MX'
    u.voice = voz
    u.rate = 1.03
    u.onstart = () => onPaso?.(i)
    if (i === textos.length - 1) u.onend = () => onPaso?.(-1)
    speechSynthesis.speak(u)
  })
}
const callar = () => { if ('speechSynthesis' in window) speechSynthesis.cancel() }

function useTema(): Tema {
  const q = () => {
    const forzado = document.documentElement.dataset.theme
    if (forzado === 'light' || forzado === 'dark') return forzado
    return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  const [t, setT] = useState<Tema>(q)
  useEffect(() => {
    const mq = matchMedia('(prefers-color-scheme: dark)')
    const f = () => setT(q())
    mq.addEventListener('change', f)
    return () => mq.removeEventListener('change', f)
  }, [])
  return t
}

// ---------------------------------------------------------------- búsqueda

const VACIAS = new Set(('donde esta estan el la los las de del un una unos unas hay que me mi mis tengo tienes tiene busco buscar quiero ' +
  'por favor en a al como llego encuentro encontrar se lo le y para con ver ensename muestrame dime oye anaquel tienda queda quedan esta').split(' '))

type Respuesta = {
  pregunta: string; texto: string; voz: string; ids: number[]; uno: Lugar | null
  ruta: Ruta | null; mueble?: string; enRed?: string
}


function buscar(q: string, d: DatosBodega, desde: 'entrada' | 'caja', publica: boolean): Respuesta {
  const n = normalizar(q)
  const ps = d.productos
  const huecos = calcularHuecos(d.plano, ps)
  const lista = (xs: Lugar[], max = 6) => xs.slice(0, max).map((p) => p.nombre).join(', ') + (xs.length > max ? ` y ${xs.length - max} más` : '')
  const base = { pregunta: q, ruta: null, uno: null } as const

  if (!publica) {
    if (/se acab|agotad|no hay|sin existencia|ya no hay/.test(n)) {
      const xs = ps.filter((p) => estadoDe(p) === 'agotado')
      const t = xs.length ? `Se acabaron ${xs.length}: ${lista(xs)}.` : 'No se te ha acabado nada.'
      return { ...base, texto: t, voz: t, ids: xs.map((p) => p.id) }
    }
    if (/falta|bajo|minimo|surtir|pedir|resurt/.test(n)) {
      const xs = ps.filter((p) => estadoDe(p) === 'agotado' || estadoDe(p) === 'bajo')
      const t = xs.length ? `Te faltan ${xs.length}: ${lista(xs)}. Están marcados en rojo y amarillo.` : 'Todo está arriba del mínimo.'
      return { ...base, texto: t, voz: t, ids: xs.map((p) => p.id) }
    }
    if (/mas se vende|mas vendid|lo que sale|mas sale|estrella/.test(n)) {
      const xs = [...ps].filter((p) => p.vendidos30 > 0).sort((a, b) => b.vendidos30 - a.vendidos30).slice(0, 5)
      const t = xs.length ? `Lo que más se vendió en 30 días: ${xs.map((p) => `${p.nombre} (${p.vendidos30})`).join(', ')}.` : 'Todavía no hay ventas registradas.'
      return { ...base, texto: t, voz: t, ids: xs.map((p) => p.id) }
    }
    if (/no se mueve|no se vende|parad|estancad|sin venta/.test(n)) {
      const xs = ps.filter((p) => p.vendidos30 === 0 && (p.stock ?? 0) > 0)
      const t = xs.length ? `No se ha vendido en 30 días: ${lista(xs)}. Considera ofrecerlo en la red.` : 'Todo lo que tienes se movió este mes.'
      return { ...base, texto: t, voz: t, ids: xs.map((p) => p.id) }
    }
  }
  if (/en la red|vitrina|publicad|mayoreo/.test(n)) {
    const xs = ps.filter((p) => p.publicado)
    const t = xs.length ? `En la vitrina de la red: ${lista(xs)}.` : 'No hay productos publicados en la red.'
    return { ...base, texto: t, voz: t, ids: xs.map((p) => p.id) }
  }
  // "¿Qué hay en la góndola 2 / el refri / el rack A1?"
  const tiposDichos: [RegExp, TipoMueble[]][] = [
    [/gondola/, ['gondola']], [/refri|refrigerador|frio/, ['refri']], [/congelador|helado/, ['congelador']], [/rack/, ['rack']],
    [/tarima/, ['tarima']], [/perchero/, ['perchero']], [/mesa/, ['mesa']], [/mostrador|caja/, ['mostrador']], [/exhibidor|botanero/, ['botanero']],
    [/anaquel|pared/, ['anaquel']],
  ]
  if (/que hay|que tiene|contenido|que lleva/.test(n) || /^(la |el )?(gondola|refri|rack|tarima|perchero|mesa|anaquel)\b/.test(n)) {
    for (const [re, tipos] of tiposDichos) {
      if (!re.test(n)) continue
      const num = n.match(/(\d+|[a-z]\d+)\s*$/)?.[1]
      const cand = d.plano.muebles.filter((m) => tipos.includes(m.tipo))
      const m = cand.find((x) => num && normalizar(x.nombre + ' ' + x.id).split(' ').some((w) => w === num || w.endsWith(num))) ?? cand[0]
      if (!m) break
      const xs = ps.filter((p) => p.mueble === m.id).sort((a, b) => b.nivel - a.nivel)
      const t = xs.length ? `En ${m.nombre} hay ${xs.length} productos: ${lista(xs, 8)}.` : `${m.nombre} está vacío.`
      return { ...base, texto: t, voz: t, ids: xs.map((p) => p.id), mueble: m.id }
    }
  }
  // Producto por nombre.
  const palabras = n.split(' ').filter((w) => w && !VACIAS.has(w)).map(singular)
  if (!palabras.length) return { ...base, texto: 'Dime qué buscas, por ejemplo «¿dónde está el atún?».', voz: 'Dime qué buscas.', ids: [] }
  const puntaje = (p: Lugar) => {
    const ws = normalizar(p.nombre + ' ' + p.categoria).split(' ').map(singular)
    let s = 0
    for (const w of palabras) {
      if (ws.some((x) => x === w)) s += 2
      else if (w.length >= 3 && ws.some((x) => x.startsWith(w) || (x.length >= 4 && w.startsWith(x)))) s += 1
    }
    return s
  }
  const conPuntos = ps.map((p) => ({ p, s: puntaje(p) })).filter((x) => x.s > 0)
  const mejor = Math.max(0, ...conPuntos.map((x) => x.s))
  const xs = conPuntos.filter((x) => x.s === mejor).map((x) => x.p)
    .sort((a, b) => Number(estadoDe(a) === 'agotado') - Number(estadoDe(b) === 'agotado'))
  if (!xs.length) {
    const t = `No encontré «${q.trim()}» en ${publica ? 'esta tienda' : 'tu tienda'}.${publica ? '' : ' ¿Lo busco con las otras tiendas de la red?'}`
    return { ...base, texto: t, voz: t, ids: [], enRed: publica ? undefined : palabras.join(' ') }
  }
  const p = xs[0]
  const h = huecos.get(p.id)
  if (!h) {
    const t = `${p.nombre} no tiene lugar en el plano todavía.${publica ? '' : ' Ábrelo y asígnale un mueble.'}`
    return { ...base, texto: t, voz: t, ids: [p.id], uno: p }
  }
  const ruta = trazar(d.plano, h, p.nombre, desde)
  const est = estadoDe(p)
  const queda = p.stock == null
    ? (p.foto ? ` Según la foto de hoy: ${p.foto === 'lleno' ? 'está lleno' : p.foto === 'poco' ? 'queda poco' : 'se acabó'}.` : '')
    : publica ? (est === 'agotado' ? ' Por ahora se acabó.' : est === 'bajo' ? ' Quedan pocas.' : ' Sí hay.')
      : est === 'agotado' ? ' Ojo: se acabó.' : ` Te ${p.stock === 1 ? 'queda' : 'quedan'} ${unidades(p.stock, p.unidad)}${est === 'bajo' ? ', ya está bajo el mínimo' : ''}.`
  const otros = xs.length > 1 ? ` También encontré: ${xs.slice(1, 5).map((x) => x.nombre).join(', ')}.` : ''
  const lugar = `${describirLugar(h)}, ${h.mueble.tipo === 'perchero' ? 'colgado' : alturaHablada(h.y + 0.1)}`
  return {
    ...base, uno: p, ruta, ids: xs.map((x) => x.id),
    texto: `${p.nombre}: ${lugar}.${queda}${otros}`,
    voz: `${p.nombre} está en ${h.mueble.nombre}, ${alturaHablada(h.y + 0.1)}.${queda}`,
  }
}

// ---------------------------------------------------------------- página

const EJEMPLOS_PANEL = ['¿Dónde está el atún?', '¿Qué se acabó?', '¿Qué me falta surtir?', 'Lo que más se vende', '¿Qué hay en el refri?', 'Lo que no se mueve']
const EJEMPLOS_PUBLICO = ['¿Dónde están los refrescos?', '¿Tienen huevo?', 'Lo que venden en la red']

const MODOS: [Modo, string][] = [['normal', 'Normal'], ['existencia', 'Existencia'], ['rotacion', 'Rotación'], ['foto', 'Foto del día'], ['valor', 'Valor']]
const LEYENDA: Record<Exclude<Modo, 'normal'>, [string, string][]> = {
  existencia: [['#18a06e', 'Bien'], ['#f2b233', 'Bajo mínimo'], ['#d64532', 'Se acabó'], ['#8a958f', 'Sin conteo']],
  rotacion: [['#3b82f6', 'Se vende poco'], ['#ef4444', 'Se vende mucho'], ['#6b7b8c', 'No se movió en 30 días']],
  foto: [['#18a06e', 'Lleno'], ['#f2b233', 'Queda poco'], ['#d64532', 'Vacío'], ['#8a958f', 'Sin foto']],
  valor: [['#e9d5ff', 'Poco dinero parado'], ['#6d28d9', 'Mucho dinero parado']],
}

export default function Bodega3D({ sesion, slug }: { sesion?: Sesion; slug?: string }) {
  const publica = !!slug
  const tema = useTema()
  const { datos, error, setDatos } = useDatos(() => api.get<DatosBodega>(publica ? `/publico/tiendas/${encodeURIComponent(slug!)}/bodega` : '/bodega'), [slug])
  const caja = useRef<HTMLDivElement>(null)
  const escena = useRef<Tienda3D | null>(null)
  const [webgl, setWebgl] = useState(true)
  const [elegido, setElegido] = useState<number | null>(null)
  const [muebleSel, setMuebleSel] = useState<string | null>(null)
  const [hover, setHover] = useState<Hover>(null)
  const [modo, setModo] = useState<Modo>('normal')
  const [q, setQ] = useState('')
  const [parcial, setParcial] = useState('')
  const [escuchando, setEscuchando] = useState(false)
  const [conVoz, setConVoz] = useState(true)
  const [desde, setDesde] = useState<'entrada' | 'caja'>('entrada')
  const [resp, setResp] = useState<Respuesta | null>(null)
  const [paso, setPaso] = useState(-1)
  const [recorriendo, setRecorriendo] = useState(false)
  const [editor, setEditor] = useState(false)
  const [borrador, setBorrador] = useState<Plano | null>(null)
  const rec = useRef<Reconocedor | null>(null)

  const plano = editor && borrador ? borrador : datos?.plano
  const vista = useMemo(() => (datos && plano ? { ...datos, plano } : null), [datos, plano])

  // La escena se crea una vez.
  useEffect(() => {
    if (!caja.current) return
    try {
      const e = new Tienda3D(caja.current, tema)
      e.onPick = (id, mueble) => { setElegido(id); setMuebleSel(id == null ? mueble : null); if (id != null) e.resaltar([id], '', false) }
      e.onHover = setHover
      escena.current = e
    } catch {
      setWebgl(false)
    }
    return () => { callar(); escena.current?.dispose(); escena.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  useEffect(() => { escena.current?.setTema(tema) }, [tema])
  useEffect(() => { if (vista) escena.current?.setDatos(vista) }, [vista])
  useEffect(() => { escena.current?.setModo(modo) }, [modo, vista])

  // Editor: mover muebles arrastrando.
  useEffect(() => {
    const e = escena.current
    if (!e) return
    e.setEditor(editor)
    e.onMover = (id, x, z) => setBorrador((b) => b && { ...b, muebles: b.muebles.map((m) => (m.id === id ? { ...m, x, z } : m)) })
    e.onSeleccion = setMuebleSel
  }, [editor])
  useEffect(() => { if (editor) escena.current?.setSeleccion(muebleSel) }, [muebleSel, editor, vista])

  const preguntar = useCallback((texto: string) => {
    if (!datos || !texto.trim()) return
    setQ(texto)
    const r = buscar(texto, datos, desde, publica)
    setResp(r)
    setPaso(-1)
    const e = escena.current
    e?.detenerRecorrido()
    setRecorriendo(false)
    if (r.mueble) { e?.resaltar(r.ids, '', false); e?.verMueble(r.mueble); setMuebleSel(r.mueble); setElegido(null) }
    else if (r.uno) { e?.resaltar(r.ids.slice(0, 1), r.uno.nombre); setElegido(r.uno.id); setMuebleSel(null) }
    else if (r.ids.length) { e?.resaltar(r.ids, '', true); setElegido(null) }
    else e?.resaltar([])
    e?.setRuta(r.ruta)
    if (conVoz) hablar([r.voz, ...(r.ruta ? r.ruta.pasos.map((p) => p.voz) : [])], (i) => setPaso(i >= 1 ? i - 1 : -1))
  }, [datos, desde, publica, conVoz])

  // ?q=… pregunta al abrir (la app manda aquí su «Llévame»); ?modo=existencia abre con ese mapa.
  const preguntado = useRef(false)
  useEffect(() => {
    if (!datos || preguntado.current || !escena.current) return
    preguntado.current = true
    const ps = new URLSearchParams(location.search)
    const m = ps.get('modo') as Modo | null
    if (m && MODOS.some(([k]) => k === m)) setModo(m)
    const pq = ps.get('q')
    if (pq) setTimeout(() => preguntar(pq), 300)
  }, [datos, preguntar])

  function escuchar() {
    if (!CrearReconocedor) { avisar('Este navegador no reconoce voz: usa Chrome o escribe la pregunta.'); return }
    if (escuchando) { rec.current?.stop(); return }
    callar()
    const r = new CrearReconocedor()
    r.lang = 'es-MX'
    r.interimResults = true
    r.continuous = false
    r.maxAlternatives = 1
    let final = ''
    r.onresult = (e) => {
      let interino = ''
      for (let i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) final += e.results[i][0].transcript
        else interino += e.results[i][0].transcript
      }
      setParcial(final || interino)
    }
    r.onerror = (e) => { if (e.error === 'not-allowed') avisar('Permite el micrófono para preguntar por voz.') }
    r.onend = () => { setEscuchando(false); setParcial(''); if (final.trim()) preguntar(final) }
    rec.current = r
    setEscuchando(true)
    r.start()
  }

  function recorrer() {
    if (!resp?.ruta || !resp.uno) return
    setRecorriendo(true)
    escena.current?.recorrer(resp.ruta, resp.uno.id, () => setRecorriendo(false))
    if (conVoz) hablar(resp.ruta.pasos.map((p) => p.voz), setPaso)
  }

  function rutaA(p: Lugar) {
    if (!datos) return
    const h = calcularHuecos(datos.plano, datos.productos).get(p.id)
    if (!h) return
    const r = trazar(datos.plano, h, p.nombre, desde)
    setResp({ pregunta: p.nombre, texto: `${p.nombre}: ${describirLugar(h)}.`, voz: `${p.nombre} está en ${h.mueble.nombre}.`, ids: [p.id], uno: p, ruta: r })
    escena.current?.resaltar([p.id], p.nombre)
    escena.current?.setRuta(r)
    if (conVoz && r) hablar(r.pasos.map((x) => x.voz), setPaso)
  }

  const nuevaInfo = (d: DatosBodega) => { setDatos(d); sesion?.refrescar() }

  // ---------------------------------------------------------------- vista
  const elegidoP = datos?.productos.find((p) => p.id === elegido) ?? null
  const hoverP = hover ? datos?.productos.find((p) => p.id === hover.id) : null
  const muebleP = vista?.plano.muebles.find((m) => m.id === muebleSel) ?? null
  const cifras = useMemo(() => {
    const ps = datos?.productos ?? []
    return {
      piezas: ps.reduce((s, p) => s + Math.max(0, p.stock ?? 0), 0),
      valor: ps.reduce((s, p) => s + Math.max(0, p.stock ?? 0) * p.precio_c, 0),
      agotados: ps.filter((p) => estadoDe(p) === 'agotado').length,
      bajos: ps.filter((p) => estadoDe(p) === 'bajo').length,
      sinLugar: ps.filter((p) => !p.mueble || p.nivel < 1),
      muebles: datos?.plano.muebles.length ?? 0,
    }
  }, [datos])

  return (
    <div className={`columna bodega3d ${publica ? 'publica' : ''}`} style={{ gap: '1rem' }}>
      <div className="renglon">
        <div>
          <h1 style={{ marginBottom: '.2rem' }}>{publica ? datos?.tienda.nombre ?? 'Recorrido 3D' : 'Bodega 3D'}</h1>
          <p className="secundario" style={{ margin: 0 }}>
            {publica ? 'Recorre la tienda, busca un producto y te digo dónde está y cómo llegar.'
              : 'Tu tienda a escala: cada producto en su entrepaño con lo que de verdad te queda. Pregúntale dónde está algo y te lleva.'}
          </p>
        </div>
        {!publica && datos && (
          <div className="fila">
            {!editor ? (
              <button className="boton claro" onClick={() => { setEditor(true); setBorrador(structuredClone(datos.plano)); setElegido(null); setResp(null); escena.current?.setRuta(null); escena.current?.vistaPlano() }}>✏️ Acomodar muebles</button>
            ) : null}
            {datos.tienda.slug && <Enlace className="boton claro" href={`/t/${datos.tienda.slug}/3d`}>👁 Como lo ven tus clientes</Enlace>}
          </div>
        )}
      </div>

      {!editor && (
        <div className="tarjeta columna pregunta3d" style={{ gap: '.6rem' }}>
          <form className="fila busca3d" onSubmit={(e) => { e.preventDefault(); preguntar(q) }}>
            <button type="button" className={`boton microfono ${escuchando ? 'escuchando' : 'claro'}`} onClick={escuchar} aria-label="Preguntar por voz" title="Preguntar por voz">
              <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3Z" /><path d="M19 11a7 7 0 0 1-14 0M12 18v3" /></svg>
            </button>
            <input style={{ flex: 1, minWidth: 180 }} value={escuchando ? parcial : q} onChange={(e) => setQ(e.target.value)}
              placeholder={escuchando ? 'Te escucho…' : publica ? '¿Dónde están los refrescos?' : '¿Dónde está el atún?'} aria-label="¿Qué buscas?" />
            <button className="boton" type="submit">Buscar</button>
            <select value={desde} onChange={(e) => setDesde(e.target.value as 'entrada' | 'caja')} style={{ width: 'auto' }} aria-label="Desde dónde">
              <option value="entrada">Desde la entrada</option>
              <option value="caja">Desde la caja</option>
            </select>
            <label className="fila chico secundario" style={{ gap: '.3rem' }}>
              <input type="checkbox" style={{ width: 'auto', minHeight: 0 }} checked={conVoz} onChange={(e) => { setConVoz(e.target.checked); if (!e.target.checked) callar() }} /> Contestar en voz alta
            </label>
          </form>
          <div className="fila ejemplos3d" style={{ gap: '.4rem' }}>
            {(publica ? EJEMPLOS_PUBLICO : EJEMPLOS_PANEL).map((t) => <button key={t} className="boton claro chico" onClick={() => preguntar(t)}>{t}</button>)}
          </div>
          {resp && (
            <div className="respuesta3d">
              <div className="fila entre" style={{ alignItems: 'flex-start' }}>
                <p style={{ margin: 0, flex: 1, fontWeight: 600 }}>🔊 {resp.texto}</p>
                <button className="boton claro chico" onClick={() => hablar([resp.voz, ...(resp.ruta?.pasos.map((p) => p.voz) ?? [])], (i) => setPaso(i >= 1 ? i - 1 : -1))}>Repetir</button>
              </div>
              {resp.enRed && <Enlace className="boton chico" href={`/panel/red?q=${encodeURIComponent(resp.enRed)}`}>Buscar «{resp.enRed}» en la red</Enlace>}
              {resp.ruta && (
                <>
                  <div className="fila" style={{ gap: '.4rem', marginTop: '.5rem' }}>
                    <span className="chip enviado">🧭 {resp.ruta.metros} m</span>
                    <span className="chip lleno">{resp.ruta.segundos} s a pie</span>
                    <span className="espacio" />
                    {!recorriendo
                      ? <button className="boton chico" onClick={recorrer}>🚶 Recorrido en primera persona</button>
                      : <button className="boton ambar chico" onClick={() => { escena.current?.detenerRecorrido(); setRecorriendo(false); callar() }}>Detener recorrido</button>}
                    <button className="boton claro chico" onClick={() => escena.current?.verRuta()}>Ver ruta</button>
                  </div>
                  <ol className="pasos3d">
                    {resp.ruta.pasos.map((p, i) => <li key={i} className={paso === i ? 'activo' : ''}>{p.texto}</li>)}
                  </ol>
                </>
              )}
            </div>
          )}
        </div>
      )}

      <Estado error={error} cargando={!datos && !error} />

      <div className={`rejilla3d ${editor ? 'con-editor' : ''}`}>
        <div className="lienzo3d" ref={caja}>
          {!webgl && <p className="vacio-lista">Tu navegador no tiene WebGL; no se puede mostrar la tienda en 3D.</p>}
          <div className="barra3d">
            {!editor && (publica ? MODOS.filter(([m]) => m === 'normal' || m === 'existencia') : MODOS).map(([m, t]) => (
              <button key={m} className={`boton chico ${modo === m ? '' : 'claro'}`} onClick={() => setModo(m)}>{publica && m === 'existencia' ? 'Qué hay' : t}</button>
            ))}
          </div>
          <div className="barra3d abajo">
            <button className="boton claro chico" onClick={() => { escena.current?.vistaGeneral(); escena.current?.setRuta(null); setResp(null); setElegido(null); setMuebleSel(null); callar() }}>General</button>
            <button className="boton claro chico" onClick={() => escena.current?.vistaPlano()}>Plano</button>
            <button className="boton claro chico" onClick={() => escena.current?.vistaCalle()}>Desde la calle</button>
            {!publica && <button className="boton claro chico" onClick={() => { const u = escena.current?.foto(); if (u) { const a = document.createElement('a'); a.href = u; a.download = 'bodega-3d.png'; a.click() } }}>📷 Imagen</button>}
          </div>
          {modo !== 'normal' && (
            <div className="leyenda3d">
              {LEYENDA[modo].map(([c, t]) => <span key={t}><i style={{ background: c }} />{t}</span>)}
            </div>
          )}
          {hover && hoverP && !editor && (
            <div className="tip3d" style={{ left: Math.min(hover.x + 14, (caja.current?.clientWidth ?? 400) - 220), top: hover.y + 14 }}>
              <b>{hoverP.nombre}</b>
              <span>{pesos(hoverP.precio_c)} · {hoverP.stock == null ? 'sin conteo' : publica ? { agotado: 'se acabó', bajo: 'quedan pocas', bien: 'hay', sin: '' }[estadoDe(hoverP)] : unidades(hoverP.stock, hoverP.unidad)}</span>
            </div>
          )}
          {editor && <div className="aviso-editor">Arrastra un mueble para moverlo · flechas: ajustar · R: girar · Supr: quitar</div>}
        </div>

        <aside className="lado3d">
          {editor && borrador && datos ? (
            <Editor plano={borrador} setPlano={setBorrador} sel={muebleSel} setSel={setMuebleSel} anaqueles={datos.anaqueles}
              alCancelar={() => { setEditor(false); setBorrador(null); setMuebleSel(null) }}
              alGuardar={async () => {
                try {
                  const d = await api.put<DatosBodega>('/bodega/plano', borrador)
                  nuevaInfo(d)
                  setEditor(false); setBorrador(null); setMuebleSel(null)
                  avisar('Plano guardado')
                } catch (e) { avisar(mensajeDe(e)) }
              }} />
          ) : elegidoP && datos ? (
            <Ficha key={elegidoP.id} p={elegidoP} datos={datos} publica={publica} slug={slug}
              alCerrar={() => { setElegido(null); escena.current?.resaltar([]) }}
              alRuta={() => rutaA(elegidoP)} alCambio={nuevaInfo} />
          ) : muebleP && datos ? (
            <DetalleMueble m={muebleP} datos={datos} publica={publica}
              alElegir={(p) => { setElegido(p.id); setMuebleSel(null); escena.current?.resaltar([p.id], p.nombre) }}
              alCerrar={() => setMuebleSel(null)} />
          ) : datos ? (
            <div className="columna" style={{ gap: '.75rem' }}>
              {!publica && (
                <div className="cifras3d">
                  <div><b>{numero(cifras.piezas)}</b><span>piezas en tienda</span></div>
                  <div><b>{pesos(cifras.valor)}</b><span>de mercancía (a precio de venta)</span></div>
                  <div className={cifras.agotados ? 'alerta' : ''}><b>{cifras.agotados}</b><span>se acabaron</span></div>
                  <div className={cifras.bajos ? 'aviso3d' : ''}><b>{cifras.bajos}</b><span>bajo el mínimo</span></div>
                </div>
              )}
              <h3 style={{ margin: '.3rem 0 0' }}>Muebles</h3>
              <div className="lista" style={{ gap: '.4rem' }}>
                {datos.plano.muebles.map((m) => {
                  const xs = datos.productos.filter((p) => p.mueble === m.id)
                  const malos = xs.filter((p) => estadoDe(p) === 'agotado').length
                  return (
                    <button key={m.id} className="mueble-fila" onClick={() => { setMuebleSel(m.id); escena.current?.verMueble(m.id); escena.current?.resaltar(xs.map((p) => p.id), '', false) }}>
                      <span className="icono-mueble">{ICONO[m.tipo]}</span>
                      <span className="espacio"><b>{m.nombre}</b><small>{TIPOS[m.tipo].nombre} · {xs.length} productos</small></span>
                      {!publica && malos > 0 && <span className="chip agotado">{malos} sin</span>}
                    </button>
                  )
                })}
              </div>
              {!publica && cifras.sinLugar.length > 0 && (
                <div className="aviso chico">
                  <b>{cifras.sinLugar.length} sin lugar:</b> {cifras.sinLugar.slice(0, 8).map((p) => p.nombre).join(', ')}{cifras.sinLugar.length > 8 ? '…' : ''}
                  <div className="fila" style={{ marginTop: '.4rem' }}>
                    <button className="boton chico" onClick={async () => { try { nuevaInfo(await api.post<DatosBodega>('/bodega/acomodar', {})); avisar('Listo: se acomodaron en el mueble que les toca') } catch (e) { avisar(mensajeDe(e)) } }}>Acomodarlos solos</button>
                  </div>
                </div>
              )}
              {!publica && (
                <details className="chico">
                  <summary>Acomodo automático</summary>
                  <p className="secundario" style={{ margin: '.4rem 0' }}>Junta cada categoría en su mueble, lo frío al refri, la ropa de colgar al perchero, y lo que más se vende a la altura de los ojos.</p>
                  <div className="fila">
                    <button className="boton claro chico" onClick={async () => { if (!confirm('¿Reacomodar todos los productos? (lo que sale en la foto del anaquel se queda)')) return; try { nuevaInfo(await api.post<DatosBodega>('/bodega/acomodar', { todos: true })); escena.current?.vistaGeneral() } catch (e) { avisar(mensajeDe(e)) } }}>Reacomodar todo</button>
                    <button className="boton peligro chico" onClick={async () => { if (!confirm('¿Volver a armar el local con los muebles típicos de tu giro? Se pierde tu acomodo de muebles.')) return; try { nuevaInfo(await api.post<DatosBodega>('/bodega/acomodar', { plano_nuevo: true })); escena.current?.vistaGeneral() } catch (e) { avisar(mensajeDe(e)) } }}>Plano nuevo según mi giro</button>
                  </div>
                </details>
              )}
              <p className="secundario chico" style={{ margin: 0 }}>Arrastra para girar, rueda o pellizca para acercar, clic en un producto para ver su ficha.</p>
            </div>
          ) : null}
        </aside>
      </div>
    </div>
  )
}

const ICONO: Record<TipoMueble, string> = {
  gondola: '🛒', anaquel: '🗄️', refri: '🧊', congelador: '🍦', rack: '🏗️', tarima: '📦', perchero: '👕', mesa: '🪵', mostrador: '💵', botanero: '🍿',
}

// ---------------------------------------------------------------- ficha del producto

function Ficha({ p, datos, publica, slug, alCerrar, alRuta, alCambio }: {
  p: Lugar; datos: DatosBodega; publica: boolean; slug?: string
  alCerrar: () => void; alRuta: () => void; alCambio: (d: DatosBodega) => void
}) {
  const h = useMemo(() => calcularHuecos(datos.plano, datos.productos).get(p.id), [datos, p.id])
  const est = estadoDe(p)
  const [mueble, setMueble] = useState(p.mueble)
  const [lado, setLado] = useState(p.lado)
  const [nivel, setNivel] = useState(Math.max(1, p.nivel))
  const [tipo, setTipo] = useState<'venta' | 'entrada' | 'ajuste'>('venta')
  const [cant, setCant] = useState('1')
  const [ocupado, setOcupado] = useState(false)
  const { datos: movs, recargar } = useDatos(() => (publica ? Promise.resolve([] as Movimiento[]) : api.get<Movimiento[]>(`/movimientos?producto=${p.id}`)), [p.id, p.stock])
  const m = datos.plano.muebles.find((x) => x.id === mueble)
  const dias = p.vendidos30 > 0 && p.stock != null ? Math.round(p.stock / (p.vendidos30 / 30)) : null

  async function guardarLugar(extra: Partial<{ posicion: number }> = {}) {
    setOcupado(true)
    try {
      alCambio(await api.put<DatosBodega>('/bodega/lugares', [{ producto_id: p.id, mueble, lado, nivel, posicion: extra.posicion ?? (mueble === p.mueble && nivel === p.nivel && lado === p.lado ? p.posicion : 999) }]))
      avisar('Lugar guardado')
    } catch (e) { avisar(mensajeDe(e)) } finally { setOcupado(false) }
  }
  /** Cambia de lugar con el vecino de la izquierda o la derecha en el mismo entrepaño. */
  async function correr(dir: -1 | 1) {
    if (!h) return
    const vecinos = datos.productos.filter((x) => x.mueble === p.mueble && x.lado === p.lado && x.nivel === p.nivel).sort((a, b) => a.posicion - b.posicion || a.id - b.id)
    const i = vecinos.findIndex((x) => x.id === p.id)
    const j = i + dir
    if (j < 0 || j >= vecinos.length) return
    const orden = vecinos.map((x) => x.id)
    ;[orden[i], orden[j]] = [orden[j], orden[i]]
    setOcupado(true)
    try {
      alCambio(await api.put<DatosBodega>('/bodega/lugares', orden.map((id, k) => {
        const x = vecinos.find((v) => v.id === id)!
        return { producto_id: id, mueble: x.mueble, lado: x.lado, nivel: x.nivel, posicion: k + 1 }
      })))
    } catch (e) { avisar(mensajeDe(e)) } finally { setOcupado(false) }
  }
  async function mover() {
    const n = Number(cant)
    if (!Number.isFinite(n) || n < 0 || (tipo !== 'ajuste' && n <= 0)) { avisar('Escribe una cantidad válida'); return }
    setOcupado(true)
    try {
      await api.post('/movimientos', { tipo, producto_id: p.id, cantidad: Math.round(n), nota: 'Desde la bodega 3D' })
      alCambio(await api.get<DatosBodega>('/bodega'))
      recargar()
      avisar(tipo === 'venta' ? 'Venta anotada' : tipo === 'entrada' ? 'Entrada anotada' : 'Conteo guardado')
    } catch (e) { avisar(mensajeDe(e)) } finally { setOcupado(false) }
  }

  return (
    <div className="columna" style={{ gap: '.7rem' }}>
      <div className="fila entre" style={{ alignItems: 'flex-start' }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <h2 style={{ margin: 0 }}>{p.nombre}</h2>
          <div className="secundario chico">{p.categoria}{p.codigo ? ` · ${p.codigo}` : ''}</div>
        </div>
        <button className="boton claro chico" onClick={alCerrar} aria-label="Cerrar">✕</button>
      </div>
      <div className="tarjeta-plana">
        <div className="secundario chico">DÓNDE ESTÁ</div>
        <b>{h ? describirLugar(h) : 'Sin lugar en el plano'}</b>
        {h && <div className="secundario chico">{h.mueble.tipo === 'perchero' ? 'Colgado en el perchero' : alturaHablada(h.y + 0.1)}{p.origen === 'foto' ? ' · según la foto del anaquel' : ''}</div>}
        {h && <button className="boton chico" style={{ marginTop: '.5rem' }} onClick={alRuta}>🧭 Cómo llegar</button>}
      </div>
      <div className="terminos">
        <div><span className="secundario chico">{publica ? 'Precio' : 'Existencia'}</span><b>{publica ? pesos(p.precio_c) : p.stock == null ? '—' : unidades(p.stock, p.unidad)}</b></div>
        {publica ? (
          <div><span className="secundario chico">Hay</span><b><span className={`chip ${est === 'agotado' ? 'agotado' : est === 'bajo' ? 'poco' : 'hay'}`}>{est === 'agotado' ? 'Se acabó' : est === 'bajo' ? 'Quedan pocas' : 'Sí hay'}</span></b></div>
        ) : (
          <>
            <div><span className="secundario chico">Mínimo</span><b>{numero(p.stock_min)}</b></div>
            <div><span className="secundario chico">Precio</span><b>{pesos(p.precio_c)}</b></div>
            <div><span className="secundario chico">Vendidos 30 días</span><b>{numero(p.vendidos30)}</b></div>
            <div><span className="secundario chico">Te alcanza para</span><b>{dias == null ? '—' : dias > 365 ? '+1 año' : `${dias} días`}</b></div>
            <div><span className="secundario chico">Dinero en el anaquel</span><b>{pesos(Math.max(0, p.stock ?? 0) * p.precio_c)}</b></div>
          </>
        )}
      </div>
      {!publica && <div className="fila" style={{ gap: '.4rem' }}><span className={`chip ${est === 'agotado' ? 'agotado' : est === 'bajo' ? 'bajo' : est === 'bien' ? 'hay' : 'cancelado'}`}>{{ agotado: 'Se acabó', bajo: 'Bajo el mínimo', bien: 'Bien surtido', sin: 'Sin conteo' }[est]}</span>{p.publicado && <span className="chip enviado">En la red</span>}{p.foto && <span className={`chip ${p.foto}`}>📷 {p.foto === 'lleno' ? 'Foto: lleno' : p.foto === 'poco' ? 'Foto: queda poco' : 'Foto: vacío'}</span>}</div>}
      {!publica && p.foto === 'vacio' && (p.stock ?? 0) > 0 && (
        <div className="aviso chico">La foto de hoy dice que el hueco está vacío, pero el sistema dice que hay {numero(p.stock!)}. Puede estar en la trastienda sin acomodar o haber merma: cuenta y haz un ajuste.</div>
      )}
      {!publica && p.foto === 'lleno' && p.stock === 0 && (
        <div className="aviso chico">La foto dice que está lleno, pero el sistema marca 0. Registra la entrada que falta o haz un conteo.</div>
      )}
      {publica && p.publicado && (
        <div className="tarjeta-plana">
          <b>Se vende por mayoreo a otras tiendas</b>
          <div className="chico">{p.precio_mayoreo_c ? `${pesos(p.precio_mayoreo_c)} c/u desde ${unidades(p.minimo_mayoreo, p.unidad)}` : 'Pregunta el precio'}{p.stock != null ? ` · disponibles ${numero(p.stock)}` : ''}</div>
          <Enlace className="boton chico" style={{ marginTop: '.4rem' }} href={`/red?q=${encodeURIComponent(p.nombre)}`}>Negociar en la red</Enlace>
        </div>
      )}
      {publica && slug && <Enlace className="boton claro chico" href={`/t/${slug}`}>Ver la tienda</Enlace>}

      {!publica && (
        <>
          <div className="tarjeta-plana columna" style={{ gap: '.45rem' }}>
            <div className="secundario chico">ANOTAR UN MOVIMIENTO (kárdex)</div>
            <div className="fila" style={{ gap: '.4rem' }}>
              <select value={tipo} onChange={(e) => { setTipo(e.target.value as typeof tipo); if (e.target.value === 'ajuste') setCant(String(p.stock ?? 0)) }} style={{ flex: 1, width: 'auto' }}>
                <option value="venta">Vendí</option>
                <option value="entrada">Llegó mercancía</option>
                <option value="ajuste">Conté y hay…</option>
              </select>
              <input type="number" min={0} value={cant} onChange={(e) => setCant(e.target.value)} style={{ width: 90 }} aria-label="Cantidad" />
              <button className="boton chico" disabled={ocupado} onClick={mover}>Anotar</button>
            </div>
            {movs && movs.length > 0 && (
              <ul className="kardex3d">
                {movs.slice(0, 6).map((x) => (
                  <li key={x.id}><span>{({ entrada: 'Entrada', venta: 'Venta', salida: 'Salida', ajuste: 'Ajuste', trato_entrada: 'Compra red', trato_salida: 'Venta red' } as Record<string, string>)[x.tipo] ?? x.tipo} <small className="secundario">{hace(x.creado)}</small></span>
                    <b style={{ color: x.cantidad < 0 ? 'var(--rojo)' : 'var(--marca)' }}>{x.cantidad > 0 ? '+' : ''}{numero(x.cantidad)}</b><small className="secundario">→ {numero(x.saldo)}</small></li>
                ))}
              </ul>
            )}
          </div>
          <div className="tarjeta-plana columna" style={{ gap: '.45rem' }}>
            <div className="secundario chico">CAMBIAR DE LUGAR</div>
            {p.origen === 'foto' && <div className="chico secundario">Su lugar sale de la zona marcada en la foto del anaquel. Si lo cambias aquí, se queda fijo a mano.</div>}
            <select value={mueble} onChange={(e) => { setMueble(e.target.value); setNivel(1); setLado(0) }}>
              <option value="">— Sin lugar —</option>
              {datos.plano.muebles.map((x) => <option key={x.id} value={x.id}>{x.nombre} ({TIPOS[x.tipo].nombre})</option>)}
            </select>
            {m && (
              <div className="fila" style={{ gap: '.4rem' }}>
                {TIPOS[m.tipo].lados === 2 && (
                  <select value={lado} onChange={(e) => setLado(Number(e.target.value))} style={{ flex: 1, width: 'auto' }}>
                    {LADOS.map((t, i) => <option key={i} value={i}>Lado {t}</option>)}
                  </select>
                )}
                {m.niveles > 1 && (
                  <select value={nivel} onChange={(e) => setNivel(Number(e.target.value))} style={{ flex: 1, width: 'auto' }}>
                    {Array.from({ length: m.niveles }, (_, i) => m.niveles - i).map((n) => <option key={n} value={n}>{m.tipo === 'rack' ? 'Nivel' : 'Entrepaño'} {n}{n === 1 ? ' (abajo)' : n === m.niveles ? ' (arriba)' : ''}</option>)}
                  </select>
                )}
              </div>
            )}
            <div className="fila" style={{ gap: '.4rem' }}>
              <button className="boton chico" disabled={ocupado || (mueble === p.mueble && nivel === p.nivel && lado === p.lado && p.origen !== 'foto')} onClick={() => guardarLugar()}>Guardar lugar</button>
              {h && h.deCuantos > 1 && <>
                <button className="boton claro chico" disabled={ocupado || h.orden === 1 || p.origen === 'foto'} onClick={() => correr(-1)} title="Recorrer a la izquierda">◀</button>
                <button className="boton claro chico" disabled={ocupado || h.orden === h.deCuantos || p.origen === 'foto'} onClick={() => correr(1)} title="Recorrer a la derecha">▶</button>
              </>}
            </div>
          </div>
          <div className="fila" style={{ gap: '.4rem' }}>
            <Enlace className="boton claro chico" href="/panel/productos">Inventario</Enlace>
            {!p.publicado && <Enlace className="boton claro chico" href="/panel/vitrina">Ofrecer en la red</Enlace>}
          </div>
        </>
      )}
    </div>
  )
}

// ---------------------------------------------------------------- un mueble

function DetalleMueble({ m, datos, publica, alElegir, alCerrar }: { m: Mueble; datos: DatosBodega; publica: boolean; alElegir: (p: Lugar) => void; alCerrar: () => void }) {
  const xs = datos.productos.filter((p) => p.mueble === m.id)
  const lados = TIPOS[m.tipo].lados
  const anaquel = datos.anaqueles.find((a) => a.id === m.anaquel_id)
  return (
    <div className="columna" style={{ gap: '.6rem' }}>
      <div className="fila entre">
        <h2 style={{ margin: 0 }}>{ICONO[m.tipo]} {m.nombre}</h2>
        <button className="boton claro chico" onClick={alCerrar} aria-label="Cerrar">✕</button>
      </div>
      <div className="secundario chico">{TIPOS[m.tipo].nombre} · {m.ancho.toFixed(1)} × {m.fondo.toFixed(2)} m · {m.alto.toFixed(1)} m de alto</div>
      {anaquel && !publica && (
        <div className="aviso ok chico">📷 Es el anaquel «{anaquel.nombre}»: la foto del día pinta su estado.{' '}
          <Enlace href={`/panel/anaqueles/${anaquel.id}`}>Tomar foto</Enlace></div>
      )}
      {Array.from({ length: lados }, (_, lado) => (
        <div key={lado} className="columna" style={{ gap: '.3rem' }}>
          {lados > 1 && <b className="chico">Lado {LADOS[lado]}</b>}
          {Array.from({ length: m.niveles }, (_, i) => m.niveles - i).map((n) => {
            const ps = xs.filter((p) => p.lado === lado && p.nivel === n).sort((a, b) => a.posicion - b.posicion)
            return (
              <div key={n} className="entrepano3d">
                {m.niveles > 1 && <span className="num">{n}</span>}
                <div className="fila" style={{ gap: '.3rem', flex: 1 }}>
                  {ps.length === 0 && <span className="secundario chico">vacío</span>}
                  {ps.map((p) => {
                    const e = estadoDe(p)
                    return <button key={p.id} className={`pieza3d ${publica ? '' : e}`} onClick={() => alElegir(p)}>{p.nombre}{!publica && p.stock != null && <small> · {numero(p.stock)}</small>}</button>
                  })}
                </div>
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------- editor del plano

function nuevoId(plano: Plano, tipo: string) {
  let i = 1
  while (plano.muebles.some((m) => m.id === `${tipo}-${i}`)) i++
  return `${tipo}-${i}`
}

/** Un hueco libre para un mueble nuevo: barre el local en pasos de 20 cm. */
function lugarLibre(plano: Plano, m: Mueble): [number, number] {
  const h = huella(m)
  for (let z = plano.fondo / 2; z < plano.fondo - h.d; z += 0.2) for (let x = h.w + 0.3; x < plano.ancho - h.w; x += 0.2) {
    if (!plano.muebles.some((o) => { const b = huella(o); return Math.abs(x - o.x) < h.w + b.w + 0.3 && Math.abs(z - o.z) < h.d + b.d + 0.3 })) return [x, z]
  }
  return [plano.ancho / 2, plano.fondo / 2]
}

function Editor({ plano, setPlano, sel, setSel, anaqueles, alCancelar, alGuardar }: {
  plano: Plano; setPlano: (p: Plano) => void; sel: string | null; setSel: (id: string | null) => void
  anaqueles: DatosBodega['anaqueles']; alCancelar: () => void; alGuardar: () => void
}) {
  const m = plano.muebles.find((x) => x.id === sel) ?? null
  const cambiar = (c: Partial<Mueble>) => m && setPlano({ ...plano, muebles: plano.muebles.map((x) => (x.id === m.id ? { ...x, ...c } : x)) })
  const quitar = () => { if (m && confirm(`¿Quitar «${m.nombre}»? Sus productos quedan sin lugar.`)) { setPlano({ ...plano, muebles: plano.muebles.filter((x) => x.id !== m.id) }); setSel(null) } }
  const agregar = (tipo: TipoMueble) => {
    const t = TIPOS[tipo]
    const nm: Mueble = { id: nuevoId(plano, tipo), tipo, nombre: `${t.nombre} ${plano.muebles.filter((x) => x.tipo === tipo).length + 1}`, x: 0, z: 0, rot: 0, ancho: t.ancho, fondo: t.fondo, alto: t.alto, niveles: t.niveles }
    ;[nm.x, nm.z] = lugarLibre(plano, nm)
    setPlano({ ...plano, muebles: [...plano.muebles, nm] })
    setSel(nm.id)
  }
  const duplicar = () => {
    if (!m) return
    const c: Mueble = { ...m, id: nuevoId(plano, m.tipo), nombre: m.nombre + ' (copia)', anaquel_id: null }
    ;[c.x, c.z] = lugarLibre(plano, c)
    setPlano({ ...plano, muebles: [...plano.muebles, c] })
    setSel(c.id)
  }
  // Teclado: flechas mueven, R gira, Supr quita.
  useEffect(() => {
    const f = (e: KeyboardEvent) => {
      if (!m || (e.target as HTMLElement).closest('input, select, textarea')) return
      const paso = e.shiftKey ? 0.5 : 0.1
      const d: Record<string, [number, number]> = { ArrowLeft: [-paso, 0], ArrowRight: [paso, 0], ArrowUp: [0, -paso], ArrowDown: [0, paso] }
      if (d[e.key]) { e.preventDefault(); const h = huella(m); cambiar({ x: Math.min(plano.ancho - h.w, Math.max(h.w, +(m.x + d[e.key][0]).toFixed(2))), z: Math.min(plano.fondo - h.d, Math.max(h.d, +(m.z + d[e.key][1]).toFixed(2))) }) }
      else if (e.key === 'r' || e.key === 'R') cambiar({ rot: (m.rot + 90) % 360 })
      else if (e.key === 'Delete') quitar()
    }
    window.addEventListener('keydown', f)
    return () => window.removeEventListener('keydown', f)
  })
  const num = (v: string, def: number) => (Number.isFinite(Number(v)) && v !== '' ? Number(v) : def)

  return (
    <div className="columna" style={{ gap: '.7rem' }}>
      <div className="fila entre">
        <h2 style={{ margin: 0 }}>Acomodar muebles</h2>
      </div>
      <div className="fila" style={{ gap: '.4rem' }}>
        <button className="boton" onClick={alGuardar}>Guardar plano</button>
        <button className="boton claro" onClick={alCancelar}>Cancelar</button>
      </div>
      {m ? (
        <div className="tarjeta-plana columna" style={{ gap: '.5rem' }}>
          <div className="fila entre"><b>{ICONO[m.tipo]} {TIPOS[m.tipo].nombre}</b><button className="boton claro chico" onClick={() => setSel(null)}>Listo</button></div>
          <label className="campo">Nombre<input value={m.nombre} onChange={(e) => cambiar({ nombre: e.target.value })} /></label>
          <div className="rejilla-campos">
            <label className="campo">Largo (m)<input type="number" step="0.1" value={m.ancho} onChange={(e) => cambiar({ ancho: num(e.target.value, m.ancho) })} /></label>
            <label className="campo">Fondo (m)<input type="number" step="0.05" value={m.fondo} onChange={(e) => cambiar({ fondo: num(e.target.value, m.fondo) })} /></label>
            <label className="campo">Alto (m)<input type="number" step="0.1" value={m.alto} onChange={(e) => cambiar({ alto: num(e.target.value, m.alto) })} /></label>
            {TIPOS[m.tipo].max > 1 && <label className="campo">{m.tipo === 'perchero' ? 'Tubos' : 'Entrepaños'}<input type="number" min={TIPOS[m.tipo].min} max={TIPOS[m.tipo].max} value={m.niveles} onChange={(e) => cambiar({ niveles: Math.max(TIPOS[m.tipo].min, Math.min(TIPOS[m.tipo].max, Math.round(num(e.target.value, m.niveles)))) })} /></label>}
          </div>
          <div className="fila" style={{ gap: '.4rem' }}>
            <button className="boton claro chico" onClick={() => cambiar({ rot: (m.rot + 270) % 360 })}>↺ Girar</button>
            <button className="boton claro chico" onClick={() => cambiar({ rot: (m.rot + 90) % 360 })}>↻ Girar</button>
            <span className="secundario chico">Frente hacia {({ 0: 'la calle', 90: 'la derecha', 180: 'el fondo', 270: 'la izquierda' } as Record<number, string>)[m.rot]}</span>
          </div>
          {(m.tipo === 'anaquel' || m.tipo === 'gondola') && anaqueles.length > 0 && (
            <label className="campo">Foto del anaquel
              <select value={m.anaquel_id ?? ''} onChange={(e) => cambiar({ anaquel_id: e.target.value ? Number(e.target.value) : null })}>
                <option value="">— Ninguna —</option>
                {anaqueles.map((a) => <option key={a.id} value={a.id}>{a.nombre}</option>)}
              </select>
            </label>
          )}
          <div className="fila" style={{ gap: '.4rem' }}>
            <button className="boton claro chico" onClick={duplicar}>Duplicar</button>
            <button className="boton peligro chico" onClick={quitar}>Quitar</button>
          </div>
        </div>
      ) : (
        <p className="secundario chico" style={{ margin: 0 }}>Toca un mueble en el plano para editarlo, o arrástralo para moverlo.</p>
      )}
      <div className="columna" style={{ gap: '.35rem' }}>
        <b className="chico">Agregar mueble</b>
        <div className="paleta3d">
          {(Object.keys(TIPOS) as TipoMueble[]).map((t) => <button key={t} className="boton claro chico" onClick={() => agregar(t)}>{ICONO[t]} {TIPOS[t].nombre}</button>)}
        </div>
      </div>
      <details>
        <summary className="chico"><b>El local</b> ({plano.ancho} × {plano.fondo} m)</summary>
        <div className="rejilla-campos" style={{ marginTop: '.5rem' }}>
          <label className="campo">Ancho (m)<input type="number" step="0.1" value={plano.ancho} onChange={(e) => setPlano({ ...plano, ancho: Math.max(2, Math.min(60, num(e.target.value, plano.ancho))) })} /></label>
          <label className="campo">Fondo (m)<input type="number" step="0.1" value={plano.fondo} onChange={(e) => setPlano({ ...plano, fondo: Math.max(2, Math.min(60, num(e.target.value, plano.fondo))) })} /></label>
          <label className="campo">Alto (m)<input type="number" step="0.1" value={plano.alto} onChange={(e) => setPlano({ ...plano, alto: Math.max(2.2, Math.min(8, num(e.target.value, plano.alto))) })} /></label>
          <label className="campo">Piso
            <select value={plano.piso} onChange={(e) => setPlano({ ...plano, piso: e.target.value as Plano['piso'] })}>
              <option value="mosaico">Mosaico</option><option value="madera">Madera</option><option value="cemento">Cemento</option>
            </select>
          </label>
          <label className="campo">Puerta: centro (m)<input type="number" step="0.1" value={plano.puerta.x} onChange={(e) => setPlano({ ...plano, puerta: { ...plano.puerta, x: num(e.target.value, plano.puerta.x) } })} /></label>
          <label className="campo">Puerta: ancho (m)<input type="number" step="0.1" value={plano.puerta.ancho} onChange={(e) => setPlano({ ...plano, puerta: { ...plano.puerta, ancho: num(e.target.value, plano.puerta.ancho) } })} /></label>
        </div>
      </details>
      <details>
        <summary className="chico"><b>Áreas</b> ({plano.areas.length})</summary>
        <div className="columna" style={{ gap: '.4rem', marginTop: '.5rem' }}>
          {plano.areas.map((a, i) => (
            <div key={i} className="tarjeta-plana columna" style={{ gap: '.3rem' }}>
              <div className="fila" style={{ gap: '.3rem' }}>
                <input value={a.nombre} onChange={(e) => setPlano({ ...plano, areas: plano.areas.map((x, k) => (k === i ? { ...x, nombre: e.target.value } : x)) })} style={{ flex: 1 }} />
                <input type="color" value={a.color} onChange={(e) => setPlano({ ...plano, areas: plano.areas.map((x, k) => (k === i ? { ...x, color: e.target.value } : x)) })} style={{ width: 48, padding: 2 }} />
                <button className="boton peligro chico" onClick={() => setPlano({ ...plano, areas: plano.areas.filter((_, k) => k !== i) })}>✕</button>
              </div>
              <div className="rejilla-campos">
                {(['x', 'z', 'ancho', 'fondo'] as const).map((k) => (
                  <label key={k} className="campo chico">{{ x: 'Desde la izq. (m)', z: 'Desde el fondo (m)', ancho: 'Ancho (m)', fondo: 'Fondo (m)' }[k]}
                    <input type="number" step="0.1" value={a[k]} onChange={(e) => setPlano({ ...plano, areas: plano.areas.map((x, j) => (j === i ? { ...x, [k]: num(e.target.value, a[k]) } : x)) })} />
                  </label>
                ))}
              </div>
            </div>
          ))}
          <button className="boton claro chico" onClick={() => setPlano({ ...plano, areas: [...plano.areas, { nombre: 'Trastienda', x: 0.2, z: 0.2, ancho: 2, fondo: 1.5, color: '#cfd8dc' }] })}>+ Área (trastienda, probadores, andén…)</button>
        </div>
      </details>
      <p className="secundario chico" style={{ margin: 0 }}>Los cambios se ven al momento en el 3D; se guardan al tocar «Guardar plano». Shift + flechas mueve de 50 en 50 cm.</p>
    </div>
  )
}
