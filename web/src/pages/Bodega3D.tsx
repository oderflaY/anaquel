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
import type { Hover, Modo, Tema, Tienda3D } from '../tienda3d/escena'
import { encerrados, trazar, type Ruta } from '../tienda3d/ruta'
import { ICONO_2D, Plano2D, vistaInicial, type Herramienta } from '../tienda3d/Plano2D'
import {
  alturaHablada, calcularHuecos, describirLugar, estadoDe, huella, LADOS, normalizar, pesos, singular, TIPOS, unidades,
  type DatosBodega, type Lugar, type Mueble, type Pared, type Plano, type TipoMueble,
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
  const [borrador, setBorradorCrudo] = useState<Plano | null>(null)
  const [vista2d, setVista2d] = useState<'2d' | '3d'>(vistaInicial)
  const [herr, setHerr] = useState<Herramienta>('mover')
  const [previa3d, setPrevia3d] = useState(false)
  const [listo3d, setListo3d] = useState(0) // sube cuando la escena 3D ya cargó
  const deshacer = useRef<Plano[]>([])
  const rehacer = useRef<Plano[]>([])
  const rec = useRef<Reconocedor | null>(null)
  // Cada cambio del armador se puede deshacer (Ctrl+Z) y rehacer (Ctrl+Y).
  const setBorrador = useCallback((v: Plano | null | ((b: Plano | null) => Plano | null)) => {
    setBorradorCrudo((b) => {
      const n = typeof v === 'function' ? v(b) : v
      if (b && n && n !== b) { deshacer.current = [...deshacer.current.slice(-60), b]; rehacer.current = [] }
      if (!n) { deshacer.current = []; rehacer.current = [] }
      return n
    })
  }, [])
  const atras = () => setBorradorCrudo((b) => { const u = deshacer.current.pop(); if (!u || !b) return b; rehacer.current.push(b); return u })
  const adelante = () => setBorradorCrudo((b) => { const u = rehacer.current.pop(); if (!u || !b) return b; deshacer.current.push(b); return u })
  useEffect(() => { try { localStorage.setItem('bodega-vista', vista2d) } catch { /* sin almacenamiento */ } }, [vista2d])
  // El 3D solo se crea si se va a ver (en 2D no se gasta memoria ni batería).
  const usar3d = vista2d === '3d' && (!editor || previa3d)

  const plano = editor && borrador ? borrador : datos?.plano
  const vista = useMemo(() => (datos && plano ? { ...datos, plano } : null), [datos, plano])

  // La escena se crea al usar el 3D y se libera al pasar al 2D.
  useEffect(() => {
    if (!usar3d || !caja.current) return
    let vivo = true
    // three.js se baja solo si se usa el 3D (el 2D ligero no lo necesita).
    import('../tienda3d/escena').then(({ Tienda3D }) => {
      if (!vivo || !caja.current) return
      try {
        const e = new Tienda3D(caja.current, tema)
        e.onPick = (id, mueble) => { setElegido(id); setMuebleSel(id == null ? mueble : null); if (id != null) e.resaltar([id], '', false) }
        e.onHover = setHover
        escena.current = e
        setListo3d((n) => n + 1)
      } catch {
        setWebgl(false)
        setVista2d('2d') // sin WebGL: el plano ligero
      }
    }).catch(() => setVista2d('2d'))
    return () => { vivo = false; escena.current?.dispose(); escena.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usar3d])
  useEffect(() => () => callar(), [])
  useEffect(() => { escena.current?.setTema(tema) }, [tema, usar3d, listo3d])
  useEffect(() => { if (vista) escena.current?.setDatos(vista) }, [vista, usar3d, listo3d])
  useEffect(() => { escena.current?.setModo(modo) }, [modo, vista, usar3d, listo3d])

  // Editor: mover muebles arrastrando.
  useEffect(() => {
    const e = escena.current
    if (!e) return
    e.setEditor(editor)
    e.onMover = (id, x, z) => setBorrador((b) => b && { ...b, muebles: b.muebles.map((m) => (m.id === id ? { ...m, x, z } : m)) })
    e.onSeleccion = setMuebleSel
  }, [editor, usar3d, listo3d, setBorrador])
  useEffect(() => { if (editor) escena.current?.setSeleccion(muebleSel) }, [muebleSel, editor, vista, usar3d, listo3d])
  // Atajos del armador: Ctrl+Z / Ctrl+Y y teclas de herramienta.
  useEffect(() => {
    if (!editor) return
    const f = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).closest('input, select, textarea')) return
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); if (e.shiftKey) adelante(); else atras() }
      else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') { e.preventDefault(); adelante() }
      else if (!e.ctrlKey && !e.metaKey) {
        const h = ({ m: 'mover', p: 'muro', b: 'media', c: 'columna', x: 'borrar' } as Record<string, Herramienta>)[e.key.toLowerCase()]
        if (h) setHerr(h)
      }
    }
    window.addEventListener('keydown', f)
    return () => window.removeEventListener('keydown', f)
  }, [editor])

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
    if (!datos || preguntado.current || (usar3d && !escena.current)) return
    preguntado.current = true
    const ps = new URLSearchParams(location.search)
    const m = ps.get('modo') as Modo | null
    if (m && MODOS.some(([k]) => k === m)) setModo(m)
    const pq = ps.get('q')
    if (pq) setTimeout(() => preguntar(pq), 300)
  }, [datos, preguntar, usar3d, listo3d])

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
              <button className="boton" onClick={() => { setEditor(true); setBorrador(structuredClone(datos.plano)); setHerr('mover'); setPrevia3d(false); setElegido(null); setResp(null); escena.current?.setRuta(null); escena.current?.vistaPlano() }}>🧱 Armar mi almacén</button>
            ) : null}
            {datos.tienda.slug && <Enlace className="boton claro" href={`/t/${datos.tienda.slug}/3d`}>👁 Como lo ven tus clientes</Enlace>}
          </div>
        )}
        <div className="interruptor-vista" role="radiogroup" aria-label="Tipo de vista">
          {(['3d', '2d'] as const).map((v) => (
            <button key={v} role="radio" aria-checked={vista2d === v} className={vista2d === v ? 'on' : ''} onClick={() => setVista2d(v)}
              title={v === '2d' ? 'Plano ligero: para teléfonos sencillos o con poca batería' : 'Tienda en 3D'}>
              {v === '3d' ? '🧊 3D' : '🗺️ 2D ligero'}
            </button>
          ))}
        </div>
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
        {!usar3d && vista && (
          <div className="lienzo3d lienzo2d">
            <Plano2D plano={vista.plano} productos={vista.productos} publica={publica} modo={modo}
              resaltados={resp?.ids ?? (elegido != null ? [elegido] : [])} ruta={editor ? null : resp?.ruta ?? null}
              editor={editor} herramienta={herr} sel={muebleSel}
              onSel={(id) => { setMuebleSel(id); if (!editor) setElegido(null) }}
              onMover={(id, x, z) => setBorrador((b) => b && { ...b, muebles: b.muebles.map((m) => (m.id === id ? { ...m, x, z } : m)) })}
              onPlano={(p) => setBorrador(p)} onProducto={(id) => { setElegido(id); setMuebleSel(null) }} />
            {!editor && (
              <div className="barra3d">
                {(publica ? MODOS.filter(([m]) => m === 'normal' || m === 'existencia') : MODOS).map(([m, t]) => (
                  <button key={m} className={`boton chico ${modo === m ? '' : 'claro'}`} onClick={() => setModo(m)}>{publica && m === 'existencia' ? 'Qué hay' : t}</button>
                ))}
              </div>
            )}
            {editor && <Herramientas herr={herr} setHerr={setHerr} atras={atras} adelante={adelante}
              puedeAtras={deshacer.current.length > 0} puedeAdelante={rehacer.current.length > 0}
              previa={vista2d === '3d' ? () => setPrevia3d(true) : undefined} />}
            {modo !== 'normal' && !editor && (
              <div className="leyenda3d">
                {LEYENDA[modo].map(([c, t]) => <span key={t}><i style={{ background: c }} />{t}</span>)}
              </div>
            )}
          </div>
        )}
        {usar3d && <div className="lienzo3d" ref={caja}>
          {!webgl && <p className="vacio-lista">Tu navegador no tiene WebGL: usa la vista 🗺️ 2D ligero.</p>}
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
          {editor && <div className="aviso-editor">Vista previa 3D: arrastra un mueble para moverlo · flechas: ajustar · R: girar
            <button className="boton chico" style={{ marginLeft: '.5rem' }} onClick={() => setPrevia3d(false)}>🧱 Volver a armar</button></div>}
        </div>}

        <aside className="lado3d">
          {editor && borrador && datos ? (
            <Editor plano={borrador} setPlano={(p) => setBorrador(p)} sel={muebleSel} setSel={setMuebleSel} anaqueles={datos.anaqueles} giro={datos.tienda.giro}
              alCancelar={() => { setEditor(false); setBorrador(null); setMuebleSel(null) }}
              alGuardar={async () => {
                try {
                  let d = await api.put<DatosBodega>('/bodega/plano', borrador)
                  // Lo que se quedó sin mueble (por quitar o cambiar muebles) se acomoda solo.
                  const sueltos = d.productos.filter((p) => !p.mueble || p.nivel < 1).length
                  if (sueltos) d = await api.post<DatosBodega>('/bodega/acomodar', {})
                  nuevaInfo(d)
                  setEditor(false); setBorrador(null); setMuebleSel(null)
                  avisar(sueltos ? `Almacén guardado · ${sueltos} productos acomodados en sus nuevos muebles` : 'Almacén guardado')
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
  vitrina: '💎', ganchera: '🪝', cajas: '🧺',
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

// ---------------------------------------------------------------- armador: herramientas, modelos y plantillas

const HERRAMIENTAS: [Herramienta, string, string, string][] = [
  ['mover', '✋', 'Mover', 'M · arrastra muebles'],
  ['muro', '🧱', 'Pared', 'P · clic, clic… doble clic termina'],
  ['media', '🚧', 'Barda', 'B · pared a media altura'],
  ['cristal', '🪟', 'Cristal', 'pared de vidrio'],
  ['columna', '⬛', 'Columna', 'C · clic para ponerla'],
  ['borrar', '🧽', 'Borrar', 'X · toca lo que quieras quitar'],
]

function Herramientas({ herr, setHerr, atras, adelante, puedeAtras, puedeAdelante, previa }: {
  herr: Herramienta; setHerr: (h: Herramienta) => void; atras: () => void; adelante: () => void
  puedeAtras: boolean; puedeAdelante: boolean; previa?: () => void
}) {
  return (
    <div className="herramientas2d" role="toolbar" aria-label="Herramientas del armador">
      {HERRAMIENTAS.map(([h, i, t, d]) => (
        <button key={h} className={herr === h ? 'on' : ''} onClick={() => setHerr(h)} title={d} aria-pressed={herr === h}>
          <span aria-hidden>{i}</span><small>{t}</small>
        </button>
      ))}
      <span className="sep" />
      <button onClick={atras} disabled={!puedeAtras} title="Deshacer (Ctrl+Z)"><span aria-hidden>↶</span><small>Deshacer</small></button>
      <button onClick={adelante} disabled={!puedeAdelante} title="Rehacer (Ctrl+Y)"><span aria-hidden>↷</span><small>Rehacer</small></button>
      {previa && <button onClick={previa} title="Ver cómo queda en 3D"><span aria-hidden>🧊</span><small>Ver 3D</small></button>}
    </div>
  )
}

/** Dibujo de frente de cada modelo de mueble para el catálogo. */
function MiniMueble({ tipo }: { tipo: TipoMueble }) {
  const t = TIPOS[tipo]
  const W = 84, H = 64, piso = 58
  const esc = Math.min((W - 10) / t.ancho, (piso - 6) / Math.max(t.alto, 1))
  const w = t.ancho * esc, h = t.alto * esc, x = (W - w) / 2, y = piso - h
  const lineas = Array.from({ length: t.niveles }, (_, i) => y + h - ((i + 1) * h) / (t.niveles + (tipo === 'refri' ? 0.6 : 0)))
  const colores = ['#2f80ed', '#eb5757', '#f2c94c', '#27ae60', '#9b51e0']
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="mini-mueble" aria-hidden>
      <line x1={2} y1={piso} x2={W - 2} y2={piso} stroke="currentColor" strokeOpacity=".25" />
      {tipo === 'refri' ? <>
        <rect x={x} y={y} width={w} height={h} rx={2} fill="#c8221c" />
        <rect x={x + 3} y={y + 7} width={w - 6} height={h - 12} fill="#cfeaff" opacity=".9" />
        {lineas.map((ly, i) => <line key={i} x1={x + 3} x2={x + w - 3} y1={ly} y2={ly} stroke="#8aa" />)}
        <rect x={x + 3} y={y + 1} width={w - 6} height={5} fill="#fff" opacity=".85" />
      </> : tipo === 'vitrina' ? <>
        <rect x={x} y={piso - 6} width={w} height={6} fill="#6d4c33" />
        <rect x={x} y={y} width={w} height={h - 6} fill="#cfeaff" opacity=".6" stroke="#9fb7c9" />
        {lineas.map((ly, i) => <line key={i} x1={x + 2} x2={x + w - 2} y1={ly} y2={ly} stroke="#9fb7c9" />)}
      </> : tipo === 'ganchera' ? <>
        <rect x={x} y={y} width={w} height={h} fill="#d8c3a0" />
        {lineas.map((ly, i) => Array.from({ length: 6 }, (_, k) => <circle key={i + '-' + k} cx={x + 5 + k * ((w - 10) / 5)} cy={ly} r={1.6} fill="#7a6a55" />))}
      </> : tipo === 'cajas' ? <>
        <rect x={x} y={y} width={w} height={h} fill="none" stroke="#5b6470" strokeWidth={2} />
        {lineas.map((ly, i) => Array.from({ length: 4 }, (_, k) => <rect key={i + '-' + k} x={x + 2 + k * ((w - 4) / 4)} y={ly - 6} width={(w - 4) / 4 - 2} height={6} fill={colores[(i + k) % 5]} />))}
      </> : tipo === 'perchero' ? <>
        <line x1={x} x2={x + w} y1={y + 4} y2={y + 4} stroke="#555" strokeWidth={2} />
        <line x1={x} x2={x} y1={y + 4} y2={piso} stroke="#555" strokeWidth={2} /><line x1={x + w} x2={x + w} y1={y + 4} y2={piso} stroke="#555" strokeWidth={2} />
        {Array.from({ length: 6 }, (_, k) => <path key={k} d={`M${x + 6 + k * ((w - 12) / 5) - 4} ${y + 22} l4 -16 l4 16z`} fill={colores[k % 5]} opacity=".8" />)}
      </> : tipo === 'mesa' || tipo === 'mostrador' || tipo === 'congelador' ? <>
        <rect x={x} y={y} width={w} height={h} rx={2} fill={tipo === 'mesa' ? '#efe6d8' : tipo === 'mostrador' ? '#a0703f' : '#e8f4fb'} stroke="#888" />
        <rect x={x - 1} y={y - 2} width={w + 2} height={3} fill={tipo === 'congelador' ? '#7fb7d9' : '#2d3436'} />
      </> : tipo === 'tarima' ? <>
        <rect x={x} y={piso - 5} width={w} height={5} fill="#b07d48" />
        <rect x={x + 2} y={y} width={w - 4} height={h - 5} fill="#c9a26b" stroke="#8a6a3d" />
      </> : <>
        <rect x={x} y={y} width={w} height={h} rx={1.5} fill={tipo === 'rack' ? 'none' : '#f4f6f8'} stroke={tipo === 'rack' ? '#e67e22' : '#9aa5ad'} strokeWidth={tipo === 'rack' ? 2 : 1} />
        {lineas.map((ly, i) => <line key={i} x1={x + 1} x2={x + w - 1} y1={ly} y2={ly} stroke={tipo === 'rack' ? '#e67e22' : '#7d8a93'} strokeWidth={1.5} />)}
        {tipo !== 'rack' && lineas.map((ly, i) => Array.from({ length: Math.max(2, Math.round(w / 9)) }, (_, k) => <rect key={i + '-' + k} x={x + 2 + k * 9} y={ly - 5} width={6} height={5} fill={colores[(i * 3 + k) % 5]} opacity=".85" />))}
        {tipo === 'rack' && <rect x={x + 4} y={lineas[0] - 10} width={w - 8} height={10} fill="#c9a26b" />}
      </>}
    </svg>
  )
}

/** Plantillas para arrancar el armado (después se ajusta todo a mano). */
function plantilla(nombre: 'vacio' | 'tiendita' | 'trastienda' | 'mayoreo' | 'ferreteria', base: Plano): Plano {
  const A = base.ancho, F = base.fondo
  const m = (id: string, tipo: TipoMueble, nombre: string, x: number, z: number, rot = 0, ancho?: number): Mueble => {
    const t = TIPOS[tipo]
    return { id, tipo, nombre, x, z, rot, ancho: ancho ?? t.ancho, fondo: t.fondo, alto: t.alto, niveles: t.niveles }
  }
  const p: Plano = { ...base, muebles: [], paredes: [], columnas: [], areas: [] }
  if (nombre === 'vacio') return p
  if (nombre === 'tiendita' || nombre === 'trastienda') {
    const fondoVenta = nombre === 'trastienda' ? 2.2 : 0
    // Con trastienda la puerta queda al centro: el anaquel del fondo se parte en dos para no taparla.
    const zVenta = fondoVenta + 0.25, libreZ = F - 2.4 - (fondoVenta + 0.5) // de frente del fondo a la caja
    const largoG = Math.max(1.2, Math.min(3, libreZ - 2.2)), zG = fondoVenta + 0.5 + 1.1 + largoG / 2
    const anchoFondo = fondoVenta ? Math.max(1, A / 2 - 1.1) : Math.min(A - 1, 4.8)
    p.muebles = [
      ...(fondoVenta
        ? [m('anaquel-1', 'anaquel', 'Anaquel del fondo izq.', 0.1 + anchoFondo / 2, zVenta, 0, anchoFondo), m('anaquel-3', 'anaquel', 'Anaquel del fondo der.', A - 0.1 - anchoFondo / 2, zVenta, 0, anchoFondo)]
        : [m('anaquel-1', 'anaquel', 'Anaquel del fondo', A / 2, zVenta, 0, anchoFondo)]),
      m('refri-1', 'refri', 'Refrigerador', 0.4, zG, 90),
      m('gondola-1', 'gondola', 'Góndola 1', A / 2, zG, 90, largoG),
      m('anaquel-2', 'anaquel', 'Anaquel de la derecha', A - 0.25, zG, 270, largoG),
      m('mostrador-1', 'mostrador', 'Mostrador y caja', Math.max(1.4, A - 1.4), F - 1.1, 0),
      m('botanero-1', 'botanero', 'Botanas', 1.2, F - 1.1, 0),
    ]
    if (nombre === 'trastienda') {
      p.paredes = [{ x1: 0, z1: fondoVenta, x2: A, z2: fondoVenta, alto: base.alto, puerta: 0.9, tipo: 'muro' }]
      p.muebles.push(m('rack-1', 'rack', 'Rack de bodega', A / 2, 0.55, 0, Math.min(A - 1.4, 4)))
      p.areas = [{ nombre: 'Trastienda', x: 0, z: 0, ancho: A, fondo: fondoVenta, color: '#cfd8dc' }]
    }
  } else if (nombre === 'mayoreo') {
    const filas = Math.max(1, Math.floor((F - 4) / 2.6))
    for (let i = 0; i < filas; i++) p.muebles.push(m(`rack-${i + 1}`, 'rack', `Rack ${i + 1}`, A / 2, 1 + i * 2.6, 0, Math.min(A - 2, 8)))
    p.muebles.push(m('tarima-1', 'tarima', 'Tarima de entrada', 1.2, F - 2, 0), m('mostrador-1', 'mostrador', 'Oficina y caja', A - 1.4, F - 1, 0))
    p.paredes = [{ x1: A - 3, z1: F - 2.2, x2: A, z2: F - 2.2, alto: 1.2, puerta: 0, tipo: 'media' }]
    p.columnas = A > 8 ? [{ x: A / 3, z: F / 2, lado: 0.4 }, { x: (2 * A) / 3, z: F / 2, lado: 0.4 }] : []
  } else if (nombre === 'ferreteria') {
    p.muebles = [
      m('ganchera-1', 'ganchera', 'Ganchos de tornillería', 0.2, F / 2, 90, Math.min(F - 3, 3.6)),
      m('cajas-1', 'cajas', 'Cajas de pijas y clavos', A / 2, 0.3, 0, Math.min(A - 1, 3.6)),
      m('gondola-1', 'gondola', 'Góndola de herramienta', A / 2, F / 2, 90, Math.min(F - 3.5, 3)),
      m('vitrina-1', 'vitrina', 'Vitrina de herramienta fina', A - 1.4, F - 1.1, 0),
      m('rack-1', 'rack', 'Rack de material', A - 0.55, F / 2 - 0.5, 270, Math.min(F - 3, 3)),
    ]
  }
  return p
}

const PLANTILLAS: ['vacio' | 'tiendita' | 'trastienda' | 'mayoreo' | 'ferreteria', string, string][] = [
  ['vacio', '⬜', 'Local vacío'], ['tiendita', '🏪', 'Tiendita'], ['trastienda', '🚪', 'Con trastienda'],
  ['mayoreo', '🏭', 'Bodega de mayoreo'], ['ferreteria', '🔩', 'Ferretería'],
]

function Editor({ plano, setPlano, sel, setSel, anaqueles, alCancelar, alGuardar, giro }: {
  plano: Plano; setPlano: (p: Plano) => void; sel: string | null; setSel: (id: string | null) => void
  anaqueles: DatosBodega['anaqueles']; alCancelar: () => void; alGuardar: () => void; giro?: string
}) {
  const paredes = plano.paredes ?? []
  const sinPaso = useMemo(() => plano.muebles.filter((x) => encerrados(plano).has(x.id)), [plano])
  const cambiarPared = (i: number, c: Partial<Pared>) => setPlano({ ...plano, paredes: paredes.map((w, k) => (k === i ? { ...w, ...c } : w)) })
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
        <h2 style={{ margin: 0 }}>🧱 Armar mi almacén</h2>
        <span className="chip enviado">{plano.muebles.length} muebles · {paredes.length} paredes</span>
      </div>
      <div className="fila" style={{ gap: '.4rem' }}>
        <button className="boton" onClick={alGuardar}>💾 Guardar</button>
        <button className="boton claro" onClick={alCancelar}>Cancelar</button>
      </div>
      {sinPaso.length > 0 && (
        <div className="aviso-encerrado" role="alert">
          🚷 <b>{sinPaso.length === 1 ? 'Un mueble quedó' : `${sinPaso.length} muebles quedaron`} sin pasillo:</b>{' '}
          {sinPaso.map((x, i) => <button key={x.id} className="enlace" onClick={() => setSel(x.id)}>{i ? ', ' : ''}{x.nombre}</button>)}.
          {' '}Deja al menos 60 cm libres para que tú y tus clientes lleguen.
        </div>
      )}
      <details className="armador-paso" open={plano.muebles.length === 0}>
        <summary><b>1 · Empieza con una plantilla</b></summary>
        <div className="plantillas">
          {PLANTILLAS.map(([k, i, t]) => (
            <button key={k} className="plantilla" onClick={() => { if (plano.muebles.length && !confirm(`¿Cambiar todo por la plantilla «${t}»? Puedes deshacer con Ctrl+Z.`)) return; setPlano(plantilla(k, plano)); setSel(null) }}>
              <span aria-hidden>{i}</span>{t}{giro && ((k === 'mayoreo' && giro === 'mayoreo') || (k === 'tiendita' && giro === 'abarrotes')) && <small>para tu giro</small>}
            </button>
          ))}
        </div>
      </details>
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
        <b className="chico">2 · Pon tus estantes (toca un modelo)</b>
        <div className="catalogo-muebles">
          {(Object.keys(TIPOS) as TipoMueble[]).map((t, i) => (
            <button key={t} className="modelo" style={{ animationDelay: `${i * 35}ms` }} onClick={() => agregar(t)} aria-label={`Agregar ${TIPOS[t].nombre}`}>
              <MiniMueble tipo={t} />
              <b>{ICONO_2D[t]} {TIPOS[t].nombre}</b>
              <small>{TIPOS[t].ancho} × {TIPOS[t].fondo} m · {TIPOS[t].niveles} {t === 'rack' ? 'niveles' : t === 'perchero' ? 'tubo' : 'entrepaños'}</small>
            </button>
          ))}
        </div>
      </div>
      <details className="armador-paso" open={paredes.length > 0}>
        <summary><b>3 · Paredes y columnas</b> ({paredes.length} / {(plano.columnas ?? []).length})</summary>
        <p className="secundario chico" style={{ margin: '.4rem 0' }}>Elige 🧱 Pared en la barra del plano y toca dónde empieza y dónde termina; sigue tocando para encadenar. Doble clic o Esc para terminar. Se pega sola a las esquinas y a escuadra.</p>
        <div className="columna" style={{ gap: '.35rem' }}>
          {paredes.map((w, i) => (
            <div key={i} className="tarjeta-plana fila pared-fila" style={{ gap: '.35rem' }}>
              <b className="chico">{({ muro: '🧱', media: '🚧', cristal: '🪟' } as Record<string, string>)[w.tipo ?? 'muro']} {Math.hypot(w.x2 - w.x1, w.z2 - w.z1).toFixed(1)} m</b>
              <select value={w.tipo ?? 'muro'} onChange={(e) => cambiarPared(i, { tipo: e.target.value as Pared['tipo'], alto: e.target.value === 'media' ? 1.2 : plano.alto })} aria-label="Tipo de pared">
                <option value="muro">Muro</option><option value="media">Barda</option><option value="cristal">Cristal</option>
              </select>
              <label className="chico">Puerta <input type="number" min={0} step={0.1} value={w.puerta} style={{ width: 64 }} onChange={(e) => cambiarPared(i, { puerta: Math.max(0, num(e.target.value, w.puerta)) })} /> m</label>
              <button className="boton claro chico" onClick={() => cambiarPared(i, { puerta: w.puerta ? 0 : 0.9 })}>{w.puerta ? 'Cerrar' : '+ Puerta'}</button>
              <button className="boton peligro chico" onClick={() => setPlano({ ...plano, paredes: paredes.filter((_, k) => k !== i) })} aria-label="Quitar pared">✕</button>
            </div>
          ))}
          {(plano.columnas ?? []).length > 0 && <button className="boton claro chico" onClick={() => setPlano({ ...plano, columnas: [] })}>Quitar las {(plano.columnas ?? []).length} columnas</button>}
        </div>
      </details>
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
