// La tienda en 3D (three.js), hecha a partir de la Bodega 3D de MatObra:
// el local a escala con su fachada, piso, vitrinas, muebles y cada producto
// en su entrepaño con su forma y su existencia real. Resalta, traza la ruta a
// pie con una persona que la camina, hace el recorrido en primera persona,
// pinta mapas de calor (existencia, rotación, foto del día, valor) y tiene un
// modo editor para acomodar los muebles arrastrándolos.
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { CSS2DObject, CSS2DRenderer } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import {
  construirMueble, dibujarProducto, type ProductoDibujado, esCompartida, etiquetaPrecio, mat, persona, texturaLetrero, texturaPiso, texturaToldo,
} from './modelos'
import type { Ruta } from './ruta'
import { calcularHuecos, estadoDe, huecoEntero, huella, tramosPared, type DatosBodega, type Hueco, type Lugar, type Mueble, type Plano } from './tipos'

export type Modo = 'normal' | 'existencia' | 'rotacion' | 'foto' | 'valor'
export type Tema = 'light' | 'dark'

const TEMAS: Record<Tema, { fondo: number; tierra: number; banqueta: number; calle: number; muro: number; techo: number }> = {
  light: { fondo: 0xe6ece8, tierra: 0xc9d1cb, banqueta: 0xcfccc4, calle: 0x55595e, muro: 0xf2efe7, techo: 0xffffff },
  dark: { fondo: 0x0f1412, tierra: 0x1f2622, banqueta: 0x3a3a36, calle: 0x26292c, muro: 0x6c7570, techo: 0xdde6e1 },
}
const MARCA = 0x0e7c5a
const ACENTO = 0xf2b233

function etiqueta(texto: string, clase: string) {
  const el = document.createElement('div')
  el.className = clase
  el.textContent = texto
  return new CSS2DObject(el)
}

function texturaFlechas() {
  const cv = document.createElement('canvas')
  cv.width = 128
  cv.height = 64
  const c = cv.getContext('2d')!
  c.fillStyle = '#ffffff'
  c.beginPath()
  c.moveTo(34, 10); c.lineTo(74, 32); c.lineTo(34, 54); c.lineTo(48, 54); c.lineTo(88, 32); c.lineTo(48, 10)
  c.closePath(); c.fill()
  const t = new THREE.CanvasTexture(cv)
  t.wrapS = THREE.RepeatWrapping
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

export type Hover = { id: number; x: number; y: number } | null

export class Tienda3D {
  private el: HTMLElement
  private renderer: THREE.WebGLRenderer
  private labels: CSS2DRenderer
  private scene = new THREE.Scene()
  private camera: THREE.PerspectiveCamera
  private controls: OrbitControls
  private sitio = new THREE.Group()
  private mueblesG = new THREE.Group()
  private productosG = new THREE.Group()
  private capa = new THREE.Group()
  private rutaG = new THREE.Group()
  private editorG = new THREE.Group()
  private marcador = new THREE.Group()
  private marcadorLabel: CSS2DObject
  private temaMats: { m: THREE.MeshStandardMaterial; k: keyof (typeof TEMAS)['light'] }[] = []
  private tema: Tema
  private frame = 0
  private obs: ResizeObserver
  private datos: DatosBodega | null = null
  private huecos = new Map<number, Hueco>()
  private dibujos = new Map<number, ProductoDibujado>()
  private muebles = new Map<string, THREE.Group>()
  private resaltados: THREE.MeshStandardMaterial[] = []
  private modo: Modo = 'normal'
  private centro = new THREE.Vector3()
  private medio = { x: 5, z: 5 }
  private vuelo: { desde: THREE.Vector3; hasta: THREE.Vector3; tDesde: THREE.Vector3; tHasta: THREE.Vector3; t0: number; dur: number } | null = null
  private movida = false
  private flechas: THREE.Texture[] = []
  private caminante: THREE.Group | null = null
  private camino: { pts: THREE.Vector3[]; largo: number; t0: number } | null = null
  private recorrido: { pts: THREE.Vector3[]; largo: number; t0: number; mira: THREE.Vector3; fin: () => void } | null = null
  private editor = false
  private seleccion: string | null = null
  private arrastre: { id: string; dx: number; dz: number } | null = null
  private suelo = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0)
  private ray = new THREE.Raycaster()
  private abajo = { x: 0, y: 0, t: 0 }
  private ultimoHover = 0
  private hoverId: number | null = null
  onPick: (productoId: number | null, muebleId: string | null) => void = () => {}
  onHover: (h: Hover) => void = () => {}
  onMover: (id: string, x: number, z: number) => void = () => {}
  onSeleccion: (id: string | null) => void = () => {}

  constructor(el: HTMLElement, tema: Tema) {
    this.el = el
    this.tema = tema
    this.renderer = new THREE.WebGLRenderer({ antialias: true })
    this.renderer.setPixelRatio(Math.min(2, window.devicePixelRatio))
    this.renderer.shadowMap.enabled = true
    this.renderer.shadowMap.type = THREE.PCFShadowMap
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 1.05
    el.appendChild(this.renderer.domElement)
    this.labels = new CSS2DRenderer()
    Object.assign(this.labels.domElement.style, { position: 'absolute', inset: '0', pointerEvents: 'none', zIndex: '1', overflow: 'hidden' })
    el.appendChild(this.labels.domElement)

    this.camera = new THREE.PerspectiveCamera(50, 1, 0.05, 300)
    this.camera.position.set(4, 12, 20)
    this.controls = new OrbitControls(this.camera, this.renderer.domElement)
    this.controls.enableDamping = true
    this.controls.maxPolarAngle = Math.PI * 0.49
    this.controls.minDistance = 0.8
    this.controls.maxDistance = 80
    this.controls.addEventListener('start', () => { this.movida = true; this.vuelo = null })

    this.scene.add(new THREE.HemisphereLight(0xffffff, 0x9a8f7a, 1.25))
    const sol = new THREE.DirectionalLight(0xffffff, 1.6)
    sol.position.set(14, 22, 18)
    sol.castShadow = true
    sol.shadow.mapSize.set(2048, 2048)
    sol.shadow.bias = -0.0004
    Object.assign(sol.shadow.camera, { left: -16, right: 16, top: 16, bottom: -16, near: 1, far: 80 })
    this.scene.add(sol, sol.target)
    this.sol = sol
    this.scene.add(this.sitio, this.mueblesG, this.productosG, this.capa, this.rutaG, this.editorG)

    // Marcador: flecha que flota sobre lo encontrado.
    const flecha = new THREE.Mesh(new THREE.ConeGeometry(0.16, 0.38, 4), mat(ACENTO, { emissive: ACENTO, emissiveIntensity: 0.6 }))
    flecha.rotation.x = Math.PI
    const aro = new THREE.Mesh(new THREE.TorusGeometry(0.32, 0.025, 8, 40), mat(MARCA, { emissive: MARCA, emissiveIntensity: 0.8 }))
    aro.rotation.x = Math.PI / 2
    aro.name = 'aro'
    this.marcador.add(flecha, aro)
    this.marcadorLabel = etiqueta('', 't3d-tag t3d-hit')
    this.marcadorLabel.position.y = 0.45
    flecha.add(this.marcadorLabel)
    this.marcador.visible = false
    this.scene.add(this.marcador)

    this.aplicarTema()
    this.obs = new ResizeObserver(() => this.ajustar())
    this.obs.observe(el)
    this.ajustar()
    const c = this.renderer.domElement
    c.addEventListener('pointerdown', this.down)
    c.addEventListener('pointermove', this.move)
    c.addEventListener('pointerup', this.up)
    c.addEventListener('pointerleave', this.salir)
    const loop = (t: number) => {
      this.frame = requestAnimationFrame(loop)
      this.animar(t)
    }
    this.frame = requestAnimationFrame(loop)
  }
  private sol: THREE.DirectionalLight

  // ------------------------------------------------------------ tema

  setTema(t: Tema) { this.tema = t; this.aplicarTema() }

  // La fachada (murete, vitrinas, rótulo, toldo) se vuelve casi transparente
  // cuando la cámara está arriba o adentro, para que no tape la tienda.
  private fachada: { m: THREE.Material; base: number }[] = []
  private transFachada = 1
  private desvanecible<T extends THREE.Material>(m: T): T {
    this.fachada.push({ m, base: m.transparent ? m.opacity : 1 })
    return m
  }
  private aplicarFachada() {
    const p = this.datos?.plano
    if (!p) return
    const c = this.camera.position
    const afuera = c.z > p.fondo + 0.3 && c.y < 3.2
    const meta = afuera ? 1 : 0.14
    if (Math.abs(meta - this.transFachada) < 0.005) return
    this.transFachada += (meta - this.transFachada) * 0.15
    for (const { m, base } of this.fachada) {
      m.transparent = true
      m.opacity = base * this.transFachada
      m.depthWrite = this.transFachada > 0.98 && base === 1
      m.needsUpdate = true
    }
  }
  private aplicarTema() {
    const t = TEMAS[this.tema]
    this.scene.background = new THREE.Color(t.fondo)
    this.scene.fog = new THREE.Fog(t.fondo, 45, 110)
    for (const { m, k } of this.temaMats) m.color.setHex(t[k])
  }
  private matTema(k: keyof (typeof TEMAS)['light'], extra: THREE.MeshStandardMaterialParameters = {}) {
    const m = mat(TEMAS[this.tema][k], extra)
    this.temaMats.push({ m, k })
    return m
  }

  // ------------------------------------------------------------ construcción

  private vaciar(g: THREE.Object3D) {
    g.traverse((o) => {
      if (o instanceof THREE.Mesh || o instanceof THREE.InstancedMesh || o instanceof THREE.LineSegments) {
        if (!esCompartida(o.geometry)) o.geometry.dispose()
        ;(Array.isArray(o.material) ? o.material : [o.material]).forEach((m: THREE.Material & { map?: THREE.Texture | null }) => { m.map?.dispose(); m.dispose() })
      }
      if (o instanceof CSS2DObject) o.element.remove()
    })
    g.clear()
  }

  private bloque(w: number, h: number, d: number, m: THREE.Material, x: number, y: number, z: number, grupo: THREE.Group = this.sitio) {
    const o = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), m)
    o.position.set(x, y, z)
    o.receiveShadow = true
    o.castShadow = m.transparent ? false : true
    grupo.add(o)
    return o
  }

  /** Piso, paredes, vitrinas, puerta, fachada con rótulo y toldo, banqueta y calle, lámparas y áreas. */
  private construirLocal(p: Plano, nombre: string) {
    this.temaMats = []
    this.fachada = []
    this.transFachada = 1
    const { ancho: A, fondo: F, alto: H } = p
    this.centro.set(A / 2, 0, F / 2)
    this.medio = { x: A / 2 + 0.8, z: F / 2 + 1.6 }
    Object.assign(this.sol.shadow.camera, { left: -Math.max(A, F), right: Math.max(A, F), top: Math.max(A, F), bottom: -Math.max(A, F) })
    this.sol.shadow.camera.updateProjectionMatrix()
    this.sol.position.set(A / 2 + 10, 22, F / 2 + 14)
    this.sol.target.position.set(A / 2, 0, F / 2)

    const tierra = new THREE.Mesh(new THREE.PlaneGeometry(160, 160), this.matTema('tierra', { roughness: 1 }))
    tierra.rotation.x = -Math.PI / 2
    tierra.position.set(A / 2, -0.04, F / 2)
    tierra.receiveShadow = true
    this.sitio.add(tierra)
    // Banqueta y calle frente a la tienda.
    this.bloque(A + 12, 0.15, 2.2, this.matTema('banqueta', { roughness: 0.95 }), A / 2, -0.04, F + 1.25)
    this.bloque(A + 12, 0.02, 0.18, mat(0xb9b5ab), A / 2, 0.04, F + 2.3)
    this.bloque(A + 40, 0.02, 7, this.matTema('calle', { roughness: 0.9 }), A / 2, -0.05, F + 5.9)
    for (let x = -14; x < A + 14; x += 3) this.bloque(1.5, 0.01, 0.12, mat(0xf2f2f2, { roughness: 0.6 }), x, -0.035, F + 5.9)

    // Piso del local.
    const tex = texturaPiso(p.piso)
    tex.repeat.set(A / (p.piso === 'madera' ? 2 : 1.2), F / (p.piso === 'madera' ? 2 : 1.2))
    const piso = new THREE.Mesh(new THREE.PlaneGeometry(A, F), new THREE.MeshStandardMaterial({ map: tex, roughness: p.piso === 'cemento' ? 0.9 : 0.55 }))
    piso.rotation.x = -Math.PI / 2
    piso.position.set(A / 2, 0.001, F / 2)
    piso.receiveShadow = true
    this.sitio.add(piso)

    // Paredes del fondo y laterales: translúcidas para ver adentro; zoclo sólido.
    const muro = this.matTema('muro', { transparent: true, opacity: 0.32, depthWrite: false, side: THREE.DoubleSide })
    const zoclo = mat(0x5d4b3a, { roughness: 0.7 })
    const E = 0.15
    this.bloque(A + 2 * E, H, E, muro, A / 2, H / 2, -E / 2)
    this.bloque(E, H, F, muro, -E / 2, H / 2, F / 2)
    this.bloque(E, H, F, muro, A + E / 2, H / 2, F / 2)
    this.bloque(A, 0.1, 0.02, zoclo, A / 2, 0.05, 0.01)
    this.bloque(0.02, 0.1, F, zoclo, 0.01, 0.05, F / 2)
    this.bloque(0.02, 0.1, F, zoclo, A - 0.01, 0.05, F / 2)
    // Frente: murete, vitrinas de vidrio y fascia; hueco de la puerta.
    const px0 = p.puerta.x - p.puerta.ancho / 2, px1 = p.puerta.x + p.puerta.ancho / 2
    const fachada = this.desvanecible(mat(0xf0e6d2, { roughness: 0.9 }))
    const vidrio = this.desvanecible(new THREE.MeshStandardMaterial({ color: 0xbfe3ff, transparent: true, opacity: 0.18, roughness: 0.05, depthWrite: false }))
    const marco = this.desvanecible(mat(0x3a3f47, { metalness: 0.6, roughness: 0.4 }))
    const hVent = Math.min(2.3, H - 0.3)
    for (const [a, b] of [[-E, px0], [px1, A + E]]) {
      const w = b - a
      if (w < 0.05) continue
      this.bloque(w, 0.6, E, fachada, (a + b) / 2, 0.3, F + E / 2)
      this.bloque(w, hVent - 0.6, 0.02, vidrio, (a + b) / 2, 0.6 + (hVent - 0.6) / 2, F + E / 2)
      for (let x = a; x <= b + 0.01; x += Math.max(0.8, w / Math.max(1, Math.round(w / 1.4)))) this.bloque(0.05, hVent - 0.6, 0.06, marco, Math.min(x, b), 0.6 + (hVent - 0.6) / 2, F + E / 2)
      this.bloque(w, 0.05, 0.08, marco, (a + b) / 2, hVent, F + E / 2)
    }
    this.bloque(A + 2 * E, H - hVent, E, fachada, A / 2, hVent + (H - hVent) / 2, F + E / 2)
    // Puerta de vidrio abierta hacia adentro.
    const hoja = new THREE.Group()
    hoja.add(new THREE.Mesh(new THREE.BoxGeometry(p.puerta.ancho / 2, 2.1, 0.03), vidrio))
    for (const x of [-p.puerta.ancho / 4, p.puerta.ancho / 4]) hoja.add(new THREE.Mesh(new THREE.BoxGeometry(0.04, 2.1, 0.05), marco).translateX(x))
    hoja.children.forEach((c) => (c.position.y = 1.05))
    hoja.position.set(px0 + Math.cos(Math.PI / 3) * p.puerta.ancho / 4, 0, F - Math.sin(Math.PI / 3) * p.puerta.ancho / 4)
    hoja.rotation.y = Math.PI / 3
    this.sitio.add(hoja)
    // Rótulo de la tienda y toldo.
    const rotulo = new THREE.Mesh(new THREE.PlaneGeometry(Math.min(A * 0.85, 6.5), 0.6),
      this.desvanecible(new THREE.MeshStandardMaterial({ map: texturaLetrero(nombre.replace(/\s*\(demo\)/i, ''), '#0e7c5a', '#ffffff', 1024, 140), emissive: 0xffffff, emissiveIntensity: 0.12 })))
    rotulo.position.set(A / 2, H - 0.36, F + E + 0.01)
    this.sitio.add(rotulo)
    const toldo = texturaToldo('#0e7c5a')
    toldo.repeat.set(A / 0.6, 1)
    const lona = new THREE.Mesh(new THREE.PlaneGeometry(A + 0.2, 0.75), this.desvanecible(new THREE.MeshStandardMaterial({ map: toldo, side: THREE.DoubleSide, roughness: 0.9 })))
    lona.position.set(A / 2, hVent - 0.12, F + E + 0.32)
    lona.rotation.x = 0.95
    lona.castShadow = true
    this.sitio.add(lona)

    // Lámparas de tubo colgadas.
    const luz = new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xfff8e8, emissiveIntensity: 1.1 })
    const carcasa = mat(0xd9dcdf, { metalness: 0.4 })
    for (let x = 1.5; x < A - 0.6; x += 2.5) for (let z = 1.5; z < F - 0.6; z += 2.6) {
      this.bloque(1.24, 0.05, 0.18, carcasa, x, H - 0.25, z)
      this.bloque(1.18, 0.02, 0.12, luz, x, H - 0.28, z)
      const cable = new THREE.Mesh(new THREE.CylinderGeometry(0.004, 0.004, 0.25, 4), mat(0x333333))
      cable.position.set(x, H - 0.12, z)
      this.sitio.add(cable)
    }

    // Paredes interiores y columnas del armador.
    const muroInt = this.matTema('muro', { transparent: true, opacity: 0.55, side: THREE.DoubleSide })
    const cristal = new THREE.MeshStandardMaterial({ color: 0xcfeaff, transparent: true, opacity: 0.25, roughness: 0.05, depthWrite: false })
    const remate = mat(0x5d4b3a, { roughness: 0.7 })
    for (const w of p.paredes ?? []) {
      const alto = w.tipo === 'media' ? Math.min(w.alto || 1.2, 1.5) : Math.min(w.alto || H, H)
      for (const [x1, z1, x2, z2] of tramosPared(w)) {
        const L = Math.hypot(x2 - x1, z2 - z1)
        if (L < 0.02) continue
        const ang = -Math.atan2(z2 - z1, x2 - x1)
        const muro = new THREE.Mesh(new THREE.BoxGeometry(L, alto, 0.12), w.tipo === 'cristal' ? cristal : muroInt)
        muro.position.set((x1 + x2) / 2, alto / 2, (z1 + z2) / 2)
        muro.rotation.y = ang
        muro.castShadow = w.tipo !== 'cristal'
        this.sitio.add(muro)
        const tapa = new THREE.Mesh(new THREE.BoxGeometry(L, 0.04, 0.14), remate)
        tapa.position.set((x1 + x2) / 2, alto, (z1 + z2) / 2)
        tapa.rotation.y = ang
        this.sitio.add(tapa)
      }
      // Marco de la puerta interior.
      if (w.puerta > 0) {
        const L = Math.hypot(w.x2 - w.x1, w.z2 - w.z1)
        const cx = (w.x1 + w.x2) / 2, cz = (w.z1 + w.z2) / 2, ang = -Math.atan2(w.z2 - w.z1, w.x2 - w.x1)
        if (L > w.puerta && alto > 2.2 && w.tipo !== 'media') {
          const dintel = new THREE.Mesh(new THREE.BoxGeometry(w.puerta, alto - 2.1, 0.12), w.tipo === 'cristal' ? cristal : muroInt)
          dintel.position.set(cx, 2.1 + (alto - 2.1) / 2, cz)
          dintel.rotation.y = ang
          this.sitio.add(dintel)
        }
      }
    }
    const concreto = mat(0xbdb6aa, { roughness: 0.9 })
    for (const c of p.columnas ?? []) this.bloque(c.lado, H, c.lado, concreto, c.x, H / 2, c.z)

    // Áreas en el piso (probadores, andén…).
    for (const ar of p.areas) {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(ar.ancho, ar.fondo), new THREE.MeshStandardMaterial({ color: new THREE.Color(ar.color), transparent: true, opacity: 0.55, roughness: 0.9, depthWrite: false }))
      m.rotation.x = -Math.PI / 2
      m.position.set(ar.x + ar.ancho / 2, 0.006, ar.z + ar.fondo / 2)
      this.sitio.add(m)
      const t = etiqueta(ar.nombre, 't3d-area')
      t.position.set(ar.x + ar.ancho / 2, 0.15, ar.z + ar.fondo / 2)
      this.sitio.add(t)
      if (/probador/i.test(ar.nombre)) {
        // Cubículos con mampara y cortina.
        const n = Math.max(1, Math.floor(ar.ancho / 0.95))
        const mampara = mat(0xf5f1ea, { roughness: 0.8 })
        const cortina = mat(0x7d5a9e, { roughness: 1, side: THREE.DoubleSide })
        for (let i = 0; i <= n; i++) this.bloque(0.04, 2.1, ar.fondo, mampara, ar.x + (i * ar.ancho) / n, 1.05, ar.z + ar.fondo / 2)
        for (let i = 0; i < n; i++) {
          const w = ar.ancho / n
          const c = new THREE.Mesh(new THREE.BoxGeometry(w * 0.7, 1.9, 0.03, 12, 1, 1), cortina)
          const pos = c.geometry.attributes.position
          for (let k = 0; k < pos.count; k++) pos.setZ(k, pos.getZ(k) + Math.sin(pos.getX(k) * 40) * 0.025)
          c.geometry.computeVertexNormals()
          c.position.set(ar.x + (i + 0.35) * w, 1.1, ar.z + ar.fondo)
          c.castShadow = true
          this.sitio.add(c)
          this.bloque(w, 0.02, 0.02, mat(0xb0b6bc, { metalness: 0.8 }), ar.x + (i + 0.5) * w, 2.08, ar.z + ar.fondo)
        }
      }
    }
    // Punto de entrada.
    const disco = new THREE.Mesh(new THREE.CircleGeometry(0.28, 32), new THREE.MeshBasicMaterial({ color: MARCA }))
    disco.rotation.x = -Math.PI / 2
    disco.position.set(p.puerta.x, 0.012, F - 0.45)
    this.sitio.add(disco)
    const ent = etiqueta('📍 Entrada', 't3d-tag t3d-inicio')
    ent.position.set(p.puerta.x, 0.5, F - 0.3)
    this.sitio.add(ent)
    this.aplicarTema()
  }

  /** Texto del copete: las categorías que tiene el mueble (o su nombre). */
  private copete(m: Mueble, productos: Lugar[]) {
    const cats = new Map<string, number>()
    for (const p of productos) if (p.mueble === m.id && p.categoria) cats.set(p.categoria, (cats.get(p.categoria) ?? 0) + 1)
    const top = [...cats.entries()].sort((a, b) => b[1] - a[1]).slice(0, 2).map(([c]) => c.toUpperCase())
    return top.length ? top.join(' · ') : m.nombre.toUpperCase()
  }

  setDatos(d: DatosBodega) {
    this.datos = d
    this.limpiarResaltado()
    this.setRuta(null)
    for (const g of [this.sitio, this.mueblesG, this.productosG, this.capa, this.editorG]) this.vaciar(g)
    this.muebles.clear()
    this.dibujos.clear()
    this.construirLocal(d.plano, d.tienda.nombre)
    this.huecos = calcularHuecos(d.plano, d.productos)

    for (const m of d.plano.muebles) {
      const g = construirMueble(m, m.tipo === 'gondola' || m.tipo === 'anaquel' ? this.copete(m, d.productos) : '', MARCA)
      g.position.set(m.x, 0, m.z)
      g.rotation.y = (m.rot * Math.PI) / 180
      g.userData.muebleId = m.id
      g.traverse((o) => { o.userData.muebleId = m.id })
      const t = etiqueta(m.nombre, 't3d-mueble')
      t.position.set(0, m.alto + (m.tipo === 'gondola' || m.tipo === 'anaquel' ? 0.45 : 0.3), 0)
      g.add(t)
      this.muebles.set(m.id, g)
      this.mueblesG.add(g)
    }
    for (const p of d.productos) {
      const h = this.huecos.get(p.id)
      if (!h) continue
      const dib = dibujarProducto(p, h)
      dib.grupo.add(etiquetaPrecio(p, h))
      dib.grupo.traverse((o) => { o.userData.productoId = p.id })
      this.dibujos.set(p.id, dib)
      this.productosG.add(dib.grupo)
    }
    this.reserva(d)
    this.pintarCapa()
    this.pintarEditor()
    if (!this.movida) this.camera.position.copy(this.posGeneral())
    if (!this.movida && !this.vuelo) this.controls.target.copy(this.centro)
  }

  huecoDe(id: number) { return this.huecos.get(id) }

  /**
   * Mayoreo: lo que no cabe en su nivel del rack (1,200 gorras) se estiba en los
   * niveles y tarimas que están libres, primero en el mismo rack y luego en el
   * más cercano. Así el 3D muestra el volumen real de la mercancía.
   */
  private reserva(d: DatosBodega) {
    const ocupado = new Set<string>()
    for (const h of this.huecos.values()) ocupado.add(`${h.mueble.id}|${h.nivel}`)
    const libres = d.plano.muebles.filter((m) => m.tipo === 'rack' || m.tipo === 'tarima')
      .flatMap((m) => Array.from({ length: m.niveles }, (_, i) => ({ m, nivel: i + 1 })))
      .filter((x) => !ocupado.has(`${x.m.id}|${x.nivel}`))
    const grandes = d.productos
      .map((p) => ({ p, h: this.huecos.get(p.id), dib: this.dibujos.get(p.id) }))
      .filter((x) => x.h && x.dib && (x.h.mueble.tipo === 'rack' || x.h.mueble.tipo === 'tarima') && (x.p.stock ?? 0) > 0)
      .sort((a, b) => (b.p.stock ?? 0) - (a.p.stock ?? 0))
    for (const { p, h, dib } of grandes) {
      const porCajaN = Math.max(1, dib!.porCaja)
      let faltan = Math.ceil((p.stock ?? 0) / porCajaN) - dib!.visibles
      while (faltan > 0 && libres.length) {
        // El lugar libre más cercano (mismo mueble primero).
        libres.sort((a, b) => (a.m.id === h!.mueble.id ? -1 : 0) - (b.m.id === h!.mueble.id ? -1 : 0) ||
          Math.hypot(a.m.x - h!.cx, a.m.z - h!.cz) - Math.hypot(b.m.x - h!.cx, b.m.z - h!.cz))
        const l = libres.shift()!
        const extra = dibujarProducto({ ...p, stock: faltan * porCajaN }, huecoEntero(l.m, l.nivel, p.id))
        extra.grupo.traverse((o) => { o.userData.productoId = p.id })
        dib!.materiales.push(...extra.materiales)
        this.productosG.add(extra.grupo)
        faltan -= extra.visibles
        if (extra.visibles === 0) break
      }
    }
  }

  // ------------------------------------------------------------ mapa de calor

  setModo(m: Modo) { this.modo = m; this.pintarCapa() }

  private pintarCapa() {
    this.vaciar(this.capa)
    if (this.modo === 'normal' || !this.datos) return
    const ps = this.datos.productos
    const maxV = Math.max(1, ...ps.map((p) => p.vendidos30))
    const maxValor = Math.max(1, ...ps.map((p) => (p.stock ?? 0) * p.precio_c))
    const grad = (k: number, a: THREE.Color, b: THREE.Color) => a.clone().lerp(b, Math.max(0, Math.min(1, k)))
    for (const p of ps) {
      const h = this.huecos.get(p.id)
      if (!h) continue
      let c: THREE.Color
      if (this.modo === 'existencia') {
        c = new THREE.Color({ agotado: 0xd64532, bajo: 0xf2b233, bien: 0x18a06e, sin: 0x8a958f }[estadoDe(p)])
      } else if (this.modo === 'foto') {
        c = new THREE.Color(p.foto === 'lleno' ? 0x18a06e : p.foto === 'poco' ? 0xf2b233 : p.foto === 'vacio' ? 0xd64532 : 0x8a958f)
      } else if (this.modo === 'rotacion') {
        c = p.vendidos30 === 0 ? new THREE.Color(0x6b7b8c) : grad(Math.sqrt(p.vendidos30 / maxV), new THREE.Color(0x3b82f6), new THREE.Color(0xef4444))
      } else {
        c = grad(Math.sqrt(((p.stock ?? 0) * p.precio_c) / maxValor), new THREE.Color(0xe9d5ff), new THREE.Color(0x6d28d9))
      }
      const alto = Math.max(0.12, Math.min(h.alto - 0.04, 0.6))
      const caja = new THREE.Mesh(new THREE.BoxGeometry(h.ancho - 0.02, alto, h.fondo), new THREE.MeshBasicMaterial({ color: c, transparent: true, opacity: 0.42, depthWrite: false }))
      caja.position.set(h.cx, h.y + alto / 2 + 0.01, h.cz)
      caja.rotation.y = h.rot
      caja.renderOrder = 2
      caja.userData.productoId = p.id
      this.capa.add(caja)
    }
  }

  // ------------------------------------------------------------ resaltar y volar

  private limpiarResaltado() {
    for (const m of this.resaltados) { m.emissive.setHex(0x000000); m.emissiveIntensity = 1 }
    this.resaltados = []
    this.marcador.visible = false
  }

  /** Resalta productos (parpadeo + flecha) y vuela la cámara al primero. */
  resaltar(ids: number[], texto = '', volar = true) {
    this.limpiarResaltado()
    const caja = new THREE.Box3()
    let primero: Hueco | null = null
    for (const id of ids) {
      const d = this.dibujos.get(id)
      const h = this.huecos.get(id)
      if (!d || !h) continue
      this.resaltados.push(...d.materiales)
      caja.expandByPoint(new THREE.Vector3(h.cx, h.y, h.cz)).expandByPoint(new THREE.Vector3(h.cx, h.y + h.alto, h.cz))
      primero ??= h
    }
    if (!primero) return
    this.marcador.position.set(primero.cx, primero.y + Math.min(primero.alto, 0.5) + 0.45, primero.cz)
    this.marcadorLabel.element.textContent = texto
    this.marcadorLabel.visible = !!texto
    this.marcador.visible = true
    if (!volar) return
    this.movida = true
    const objetivo = caja.getCenter(new THREE.Vector3())
    const tam = Math.max(2.2, caja.getSize(new THREE.Vector3()).length())
    // Desde el frente del hueco, un poco arriba.
    const f = new THREE.Vector3(Math.sin(primero.rot), 0, Math.cos(primero.rot))
    const desde = objetivo.clone().add(f.multiplyScalar(tam * 0.9 + 1.2)).add(new THREE.Vector3(0, tam * 0.55 + 0.8, 0))
    this.volar(desde, objetivo)
  }

  /** Encuadra un mueble completo. */
  verMueble(id: string) {
    const m = this.datos?.plano.muebles.find((x) => x.id === id)
    if (!m) return
    this.movida = true
    const t = (m.rot * Math.PI) / 180
    const c = new THREE.Vector3(m.x, m.alto / 2, m.z)
    const dist = Math.max(m.ancho, m.alto) * 1.25 + 1
    this.volar(c.clone().add(new THREE.Vector3(Math.sin(t) * dist, dist * 0.55, Math.cos(t) * dist)), c)
  }

  private volar(hasta: THREE.Vector3, mira: THREE.Vector3, dur = 1100) {
    this.recorrido = null
    this.controls.enabled = true
    this.vuelo = { desde: this.camera.position.clone(), hasta, tDesde: this.controls.target.clone(), tHasta: mira, t0: performance.now(), dur }
  }

  private posGeneral() {
    const vfov = THREE.MathUtils.degToRad(this.camera.fov)
    const hfov = 2 * Math.atan(Math.tan(vfov / 2) * Math.max(0.5, this.camera.aspect))
    const dist = Math.max(this.medio.x / Math.tan(hfov / 2), this.medio.z / Math.tan(vfov / 2)) * 1.05
    return this.centro.clone().add(new THREE.Vector3(0.18, 0.78, 0.6).normalize().multiplyScalar(dist))
  }

  vistaGeneral() {
    this.movida = false
    this.limpiarResaltado()
    this.volar(this.posGeneral(), this.centro.clone())
  }

  vistaPlano() {
    this.movida = true
    const vfov = THREE.MathUtils.degToRad(this.camera.fov)
    const hfov = 2 * Math.atan(Math.tan(vfov / 2) * this.camera.aspect)
    const dist = Math.max(this.medio.x / Math.tan(hfov / 2), this.medio.z / Math.tan(vfov / 2)) * 1.02
    this.volar(new THREE.Vector3(this.centro.x, dist, this.centro.z + 0.01), this.centro.clone())
  }

  /** Vista desde la banqueta, como la ve un cliente que llega. */
  vistaCalle() {
    if (!this.datos) return
    const p = this.datos.plano
    this.movida = true
    this.volar(new THREE.Vector3(p.puerta.x, 1.7, p.fondo + 5.2), new THREE.Vector3(p.ancho / 2, 1.2, p.fondo / 2))
  }

  // ------------------------------------------------------------ ruta a pie

  setRuta(r: Ruta | null, encuadrar = true) {
    this.vaciar(this.rutaG)
    this.flechas.forEach((t) => t.dispose())
    this.flechas = []
    this.caminante = null
    this.camino = null
    if (!r || r.puntos.length < 2) return
    const pts = r.puntos.map(([x, z]) => new THREE.Vector3(x, 0.02, z))
    const base = new THREE.MeshBasicMaterial({ color: ACENTO, transparent: true, opacity: 0.85, depthWrite: false })
    const tex0 = texturaFlechas()
    let largo = 0
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1], b = pts[i]
      const L = a.distanceTo(b)
      if (L < 0.01) continue
      largo += L
      const ang = Math.atan2(-(b.z - a.z), b.x - a.x)
      const banda = new THREE.Mesh(new THREE.PlaneGeometry(L, 0.4), base)
      banda.rotation.set(-Math.PI / 2, 0, ang)
      banda.position.set((a.x + b.x) / 2, 0.015, (a.z + b.z) / 2)
      const t = tex0.clone()
      t.repeat.set(L / 0.45, 1)
      t.needsUpdate = true
      this.flechas.push(t)
      const fl = new THREE.Mesh(new THREE.PlaneGeometry(L, 0.32), new THREE.MeshBasicMaterial({ map: t, transparent: true, depthWrite: false }))
      fl.rotation.set(-Math.PI / 2, 0, ang)
      fl.position.set((a.x + b.x) / 2, 0.02, (a.z + b.z) / 2)
      const codo = new THREE.Mesh(new THREE.CircleGeometry(0.2, 24), base)
      codo.rotation.x = -Math.PI / 2
      codo.position.set(b.x, 0.015, b.z)
      this.rutaG.add(banda, fl, codo)
    }
    tex0.dispose()
    const fin = pts[pts.length - 1]
    const meta = new THREE.Mesh(new THREE.RingGeometry(0.22, 0.34, 32), new THREE.MeshBasicMaterial({ color: MARCA, side: THREE.DoubleSide }))
    meta.rotation.x = -Math.PI / 2
    meta.position.set(fin.x, 0.025, fin.z)
    meta.name = 'meta'
    this.rutaG.add(meta)
    const et = etiqueta(`${r.metros} m · ${r.segundos} s a pie`, 't3d-tag t3d-ruta')
    et.position.set(fin.x, 2.1, fin.z)
    this.rutaG.add(et)
    this.caminante = persona(0x2f7fd0, false)
    this.rutaG.add(this.caminante)
    this.camino = { pts, largo, t0: performance.now() }
    if (encuadrar) this.verRuta()
  }

  verRuta() {
    if (!this.camino) return
    this.movida = true
    const b = new THREE.Box3().setFromPoints(this.camino.pts)
    if (this.marcador.visible) b.expandByPoint(this.marcador.position)
    b.expandByScalar(1)
    const c = b.getCenter(new THREE.Vector3())
    const s = b.getSize(new THREE.Vector3())
    const vfov = THREE.MathUtils.degToRad(this.camera.fov)
    const hfov = 2 * Math.atan(Math.tan(vfov / 2) * this.camera.aspect)
    const dist = Math.max(5, (s.x / 2) / Math.tan(hfov / 2), (s.z / 2) / Math.tan(vfov / 2)) * 1.25
    c.y = 0
    this.volar(c.clone().add(new THREE.Vector3(0.1, 1.3, 0.8).normalize().multiplyScalar(dist)), c)
  }

  /** Recorrido en primera persona: la cámara camina la ruta a la altura de los ojos y voltea al producto. */
  recorrer(r: Ruta, productoId: number, alTerminar: () => void = () => {}) {
    const h = this.huecos.get(productoId)
    if (!h || r.puntos.length < 2) return
    const pts = r.puntos.map(([x, z]) => new THREE.Vector3(x, 1.6, z))
    let largo = 0
    for (let i = 1; i < pts.length; i++) largo += pts[i - 1].distanceTo(pts[i])
    this.vuelo = null
    this.movida = true
    this.controls.enabled = false
    if (this.caminante) this.caminante.visible = false
    this.recorrido = { pts, largo, t0: performance.now(), mira: new THREE.Vector3(h.cx, h.y + 0.15, h.cz), fin: alTerminar }
  }

  detenerRecorrido() {
    if (!this.recorrido) return
    this.recorrido = null
    this.controls.enabled = true
  }

  // ------------------------------------------------------------ editor de muebles

  setEditor(on: boolean) {
    this.editor = on
    if (!on) { this.seleccion = null; this.arrastre = null; this.controls.enabled = true }
    this.pintarEditor()
  }

  setSeleccion(id: string | null) {
    this.seleccion = id
    this.pintarEditor()
  }

  private pintarEditor() {
    this.vaciar(this.editorG)
    if (!this.editor || !this.datos) return
    const p = this.datos.plano
    // Cuadrícula de 50 cm del tamaño exacto del local (la de cada metro, más marcada).
    const finas: number[] = [], gruesas: number[] = []
    for (let x = 0; x <= p.ancho + 0.001; x += 0.5) (Math.abs(x % 1) < 0.01 ? gruesas : finas).push(x, 0.004, 0, x, 0.004, p.fondo)
    for (let z = 0; z <= p.fondo + 0.001; z += 0.5) (Math.abs(z % 1) < 0.01 ? gruesas : finas).push(0, 0.004, z, p.ancho, 0.004, z)
    for (const [pts, color, op] of [[finas, 0x9aa79f, 0.35], [gruesas, 0x0e7c5a, 0.7]] as const) {
      const g = new THREE.BufferGeometry()
      g.setAttribute('position', new THREE.Float32BufferAttribute(pts, 3))
      this.editorG.add(new THREE.LineSegments(g, new THREE.LineBasicMaterial({ color, transparent: true, opacity: op })))
    }
    for (const m of p.muebles) this.editorG.add(this.contorno(m))
  }

  private seEncima(m: Mueble, x = m.x, z = m.z) {
    const a = huella(m)
    return (this.datos?.plano.muebles ?? []).some((o) => {
      if (o.id === m.id) return false
      const b = huella(o)
      return Math.abs(x - o.x) < a.w + b.w - 0.01 && Math.abs(z - o.z) < a.d + b.d - 0.01
    })
  }

  private contorno(m: Mueble, x = m.x, z = m.z) {
    const h = huella(m)
    const color = this.seEncima(m, x, z) ? 0xd64532 : m.id === this.seleccion ? ACENTO : 0x0e7c5a
    const l = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(h.w * 2 + 0.04, 0.02, h.d * 2 + 0.04)), new THREE.LineBasicMaterial({ color, linewidth: 2 }))
    l.position.set(x, 0.02, z)
    l.name = 'contorno-' + m.id
    if (m.id === this.seleccion) {
      const relleno = new THREE.Mesh(new THREE.PlaneGeometry(h.w * 2, h.d * 2), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.25, depthWrite: false }))
      relleno.rotation.x = -Math.PI / 2
      l.add(relleno)
      // Flecha del frente del mueble.
      const t = (m.rot * Math.PI) / 180
      const fl = new THREE.Mesh(new THREE.ConeGeometry(0.12, 0.3, 3), new THREE.MeshBasicMaterial({ color }))
      fl.rotation.set(Math.PI / 2, 0, 0)
      const g = new THREE.Group()
      g.add(fl)
      g.rotation.y = t
      fl.position.z = (m.fondo / 2 + 0.3)
      l.add(g)
    }
    return l
  }

  // ------------------------------------------------------------ eventos

  private punto(e: PointerEvent) {
    const r = this.renderer.domElement.getBoundingClientRect()
    return new THREE.Vector2(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1)
  }

  private down = (e: PointerEvent) => {
    this.abajo = { x: e.clientX, y: e.clientY, t: performance.now() }
    if (!this.editor || !this.datos) return
    this.ray.setFromCamera(this.punto(e), this.camera)
    const hit = this.ray.intersectObjects(this.mueblesG.children, true)[0]
    let o: THREE.Object3D | null = hit?.object ?? null
    while (o && o.userData.muebleId == null) o = o.parent
    const id = (o?.userData.muebleId as string | undefined) ?? null
    if (!id) return
    const m = this.datos.plano.muebles.find((x) => x.id === id)!
    const pt = new THREE.Vector3()
    this.ray.ray.intersectPlane(this.suelo, pt)
    this.arrastre = { id, dx: m.x - pt.x, dz: m.z - pt.z }
    this.controls.enabled = false
    this.seleccion = id
    this.onSeleccion(id)
    this.pintarEditor()
    this.renderer.domElement.setPointerCapture(e.pointerId)
  }

  private move = (e: PointerEvent) => {
    if (this.arrastre && this.datos) {
      this.ray.setFromCamera(this.punto(e), this.camera)
      const pt = new THREE.Vector3()
      if (!this.ray.ray.intersectPlane(this.suelo, pt)) return
      const m = this.datos.plano.muebles.find((x) => x.id === this.arrastre!.id)!
      const h = huella(m)
      const snap = (v: number) => Math.round(v / 0.05) * 0.05
      const x = Math.min(this.datos.plano.ancho - h.w, Math.max(h.w, snap(pt.x + this.arrastre.dx)))
      const z = Math.min(this.datos.plano.fondo - h.d, Math.max(h.d, snap(pt.z + this.arrastre.dz)))
      const g = this.muebles.get(m.id)!
      const dx = x - g.position.x, dz = z - g.position.z
      g.position.set(x, 0, z)
      // Los productos del mueble viajan con él.
      for (const [pid, hu] of this.huecos) if (hu.mueble.id === m.id) this.dibujos.get(pid)?.grupo.position.add(new THREE.Vector3(dx, 0, dz))
      const viejo = this.editorG.getObjectByName('contorno-' + m.id)
      if (viejo) { this.editorG.remove(viejo); this.vaciar(viejo) }
      this.editorG.add(this.contorno(m, x, z))
      return
    }
    if (this.recorrido || e.buttons) return
    const ahora = performance.now()
    if (ahora - this.ultimoHover < 70) return
    this.ultimoHover = ahora
    this.ray.setFromCamera(this.punto(e), this.camera)
    const hit = this.ray.intersectObjects(this.productosG.children, true)[0]
    const id = (hit?.object.userData.productoId as number | undefined) ?? null
    if (id !== this.hoverId || id != null) {
      this.hoverId = id
      const r = this.renderer.domElement.getBoundingClientRect()
      this.onHover(id == null ? null : { id, x: e.clientX - r.left, y: e.clientY - r.top })
    }
  }

  private salir = () => { if (this.hoverId != null) { this.hoverId = null; this.onHover(null) } }

  private up = (e: PointerEvent) => {
    if (this.arrastre && this.datos) {
      const g = this.muebles.get(this.arrastre.id)!
      const id = this.arrastre.id
      this.arrastre = null
      this.controls.enabled = true
      const m = this.datos.plano.muebles.find((x) => x.id === id)!
      if (Math.abs(g.position.x - m.x) > 0.001 || Math.abs(g.position.z - m.z) > 0.001) this.onMover(id, Math.round(g.position.x * 100) / 100, Math.round(g.position.z * 100) / 100)
      return
    }
    if (Math.hypot(e.clientX - this.abajo.x, e.clientY - this.abajo.y) > 5 || this.recorrido) return
    this.ray.setFromCamera(this.punto(e), this.camera)
    const hits = this.ray.intersectObjects([...this.productosG.children, ...this.mueblesG.children], true)
    const h = hits[0]
    if (!h) { if (this.editor) { this.seleccion = null; this.onSeleccion(null); this.pintarEditor() } return this.onPick(null, null) }
    let o: THREE.Object3D | null = h.object
    while (o && o.userData.productoId == null && o.userData.muebleId == null) o = o.parent
    if (this.editor) {
      const id = (o?.userData.muebleId as string | undefined) ?? null
      this.seleccion = id
      this.onSeleccion(id)
      this.pintarEditor()
      return
    }
    this.onPick((o?.userData.productoId as number | undefined) ?? null, (o?.userData.muebleId as string | undefined) ?? null)
  }

  // ------------------------------------------------------------ animación

  private animar(t: number) {
    if (this.vuelo) {
      const k = Math.min(1, (t - this.vuelo.t0) / this.vuelo.dur)
      const e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2
      this.camera.position.lerpVectors(this.vuelo.desde, this.vuelo.hasta, e)
      this.controls.target.lerpVectors(this.vuelo.tDesde, this.vuelo.tHasta, e)
      if (k >= 1) this.vuelo = null
    }
    if (this.resaltados.length) {
      const s = 0.35 + 0.35 * Math.sin(t / 200)
      for (const m of this.resaltados) { m.emissive.setHex(ACENTO); m.emissiveIntensity = s }
    }
    if (this.marcador.visible) {
      this.marcador.children[0].position.y = Math.sin(t / 300) * 0.08
      this.marcador.getObjectByName('aro')!.scale.setScalar(1 + 0.15 * Math.sin(t / 250))
    }
    for (const f of this.flechas) f.offset.x = -((t / 1000) * 1.2) % 1
    if (this.camino && this.caminante) {
      const { pts, largo, t0 } = this.camino
      const vel = 1.2
      const dur = largo / vel + 1.6
      let s = (((t - t0) / 1000) % dur) * vel
      const quieto = s >= largo
      s = Math.min(s, largo)
      for (let i = 1; i < pts.length; i++) {
        const L = pts[i - 1].distanceTo(pts[i])
        if (s <= L || i === pts.length - 1) {
          const p = pts[i - 1].clone().lerp(pts[i], Math.min(1, s / Math.max(L, 0.001)))
          this.caminante.position.set(p.x, 0, p.z)
          this.caminante.rotation.y = Math.atan2(pts[i].x - pts[i - 1].x, pts[i].z - pts[i - 1].z)
          break
        }
        s -= L
      }
      const sw = quieto ? 0 : Math.sin(t / 130) * 0.45
      const [p0, p1] = this.caminante.userData.piernas as THREE.Mesh[]
      const [b0] = this.caminante.userData.brazos as THREE.Mesh[]
      p0.rotation.x = sw; p1.rotation.x = -sw; b0.rotation.x = -sw * 0.8
      this.rutaG.getObjectByName('meta')?.scale.setScalar(1 + 0.2 * Math.sin(t / 200))
    }
    if (this.recorrido) {
      const { pts, largo, t0, mira } = this.recorrido
      const vel = 1.1
      const s0 = ((t - t0) / 1000) * vel
      const s = Math.min(s0, largo)
      let pos = pts[0].clone(), dir = pts[1].clone().sub(pts[0])
      let acc = s
      for (let i = 1; i < pts.length; i++) {
        const L = pts[i - 1].distanceTo(pts[i])
        if (acc <= L || i === pts.length - 1) {
          pos = pts[i - 1].clone().lerp(pts[i], Math.min(1, acc / Math.max(L, 0.001)))
          dir = pts[i].clone().sub(pts[i - 1]).normalize()
          break
        }
        acc -= L
      }
      // Balanceo leve de la cabeza al caminar.
      pos.y += s < largo ? Math.sin(t / 160) * 0.015 : 0
      this.camera.position.copy(pos)
      const adelante = pos.clone().add(dir.multiplyScalar(1.5)).setY(1.45)
      // Al final voltea hacia el producto.
      const k = Math.max(0, Math.min(1, (s0 - largo + 1.2) / 1.6))
      const objetivo = adelante.lerp(mira, k)
      this.controls.target.copy(objetivo)
      this.camera.lookAt(objetivo)
      if (s0 > largo + 1.4) {
        const fin = this.recorrido.fin
        this.recorrido = null
        this.controls.enabled = true
        fin()
      }
    } else this.controls.update()
    this.aplicarFachada()
    this.renderer.render(this.scene, this.camera)
    this.labels.render(this.scene, this.camera)
  }

  private ajustar() {
    const w = this.el.clientWidth, h = this.el.clientHeight
    if (!w || !h) return
    this.renderer.setSize(w, h)
    this.labels.setSize(w, h)
    this.camera.aspect = w / h
    this.camera.updateProjectionMatrix()
    if (!this.movida) this.camera.position.copy(this.posGeneral())
  }

  /** Imagen de la vista actual (para compartir o imprimir). */
  foto(): string {
    this.renderer.render(this.scene, this.camera)
    return this.renderer.domElement.toDataURL('image/png')
  }

  dispose() {
    cancelAnimationFrame(this.frame)
    this.obs.disconnect()
    this.controls.dispose()
    const c = this.renderer.domElement
    c.removeEventListener('pointerdown', this.down)
    c.removeEventListener('pointermove', this.move)
    c.removeEventListener('pointerup', this.up)
    c.removeEventListener('pointerleave', this.salir)
    for (const g of [this.sitio, this.mueblesG, this.productosG, this.capa, this.rutaG, this.editorG]) this.vaciar(g)
    this.renderer.dispose()
    this.el.replaceChildren()
  }
}
