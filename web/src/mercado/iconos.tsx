// Íconos de trazo propios (24×24, trazo 2), sin librerías. Son decorativos:
// el nombre accesible lo pone el botón o el texto que los acompaña.
const trazos: Record<string, string> = {
  ojo: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Zm10 3a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z',
  ojoNo: 'M3 3l18 18M10.6 5.1A10.6 10.6 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4.2M6.6 6.6A17.6 17.6 0 0 0 2 12s3.5 7 10 7c1.8 0 3.4-.5 4.8-1.3M9.9 9.9a3 3 0 0 0 4.2 4.2',
  buscar: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Zm5-2 4 4',
  tienda: 'M4 9.5 5.5 4h13L20 9.5M4 9.5V20h16V9.5M4 9.5c0 1.4 1.1 2.5 2.7 2.5S9.3 10.9 9.3 9.5c0 1.4 1.2 2.5 2.7 2.5s2.7-1.1 2.7-2.5c0 1.4 1.1 2.5 2.6 2.5S20 10.9 20 9.5M10 20v-4.5h4V20',
  video: 'M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v11a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 17.5v-11ZM10 9v6l5-3-5-3Z',
  recibo: 'M6 3h12v18l-3-2-3 2-3-2-3 2V3Zm3 5h6m-6 4h6m-6 4h3',
  etiqueta: 'M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9-9-9Zm5-4.5h.01',
  mas: 'M12 5v14M5 12h14',
  menos: 'M5 12h14',
  usuario: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 8a7 7 0 0 1 14 0',
  salir: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 16l-4-4 4-4M6 12h10',
  atras: 'M15 18l-6-6 6-6',
  adelante: 'M9 6l6 6-6 6',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  alerta: 'M12 8v5m0 3.5h.01M10.3 3.9 2.6 17.3A2 2 0 0 0 4.3 20.3h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-10v6m0-9.5h.01',
  cerrar: 'M6 6l12 12M18 6 6 18',
  camara: 'M4 8a2 2 0 0 1 2-2h2l1.5-2h5L16 6h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8Zm8 9a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z',
  ubicacion: 'M12 21s-7-6.2-7-11.5a7 7 0 1 1 14 0C19 14.8 12 21 12 21Zm0-9a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z',
  sol: 'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm0-14v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  luna: 'M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z',
  terminal: 'M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Zm1 3h8v5H8V6Zm0 8h.01M12 14h.01M16 14h.01M8 17.5h.01M12 17.5h.01M16 17.5h.01',
  tarjeta: 'M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Zm0 3h18M7 15h3',
  volumen: 'M11 5 6 9H3v6h3l5 4V5Zm4.5 3.5a5 5 0 0 1 0 7M18 6a8.5 8.5 0 0 1 0 12',
  silencio: 'M11 5 6 9H3v6h3l5 4V5Zm5 4.5 5 5m0-5-5 5',
  play: 'M7 4.5v15l13-7.5L7 4.5Z',
  pausa: 'M8 5v14M16 5v14',
  basura: 'M4 7h16M10 11v6m4-6v6M6 7l1 13h10l1-13M9 7V4h6v3',
  editar: 'M4 20h4L19 9l-4-4L4 16v4Zm9-13 4 4',
  invitar: 'M15 19a6 6 0 0 0-12 0m6-8a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm10-3v6m3-3h-6',
  copiar: 'M9 9h10v10H9V9Zm-4 6H4V4h11v1',
  reloj: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-13v4.5l3 2',
  escudo: 'M12 3 5 6v5.5c0 4.4 3 8.2 7 9.5 4-1.3 7-5.1 7-9.5V6l-7-3Zm-3 9 2 2 4-4',
  // Categorías
  canasta: 'M3 10h18l-2 10H5L3 10Zm5 0 4-6 4 6M9 14v3m3-3v3m3-3v3',
  hoja: 'M5 19C5 10 10 5 20 4c-1 10-6 15-15 15Zm0 0 8-8',
  camisa: 'M8 3 3 6l2 5 2-1v11h10V10l2 1 2-5-5-3a4 4 0 0 1-8 0Z',
  caja: 'M3 7.5 12 3l9 4.5v9L12 21l-9-4.5v-9Zm0 0 9 4.5m0 0 9-4.5M12 12v9',
  lapiz: 'M4 20l1-4L16 5l3 3L8 19l-4 1Zm10-13 3 3',
  casa: 'M3 11 12 4l9 7M5 9.5V20h5v-5h4v5h5V9.5',
  herramienta: 'M14.5 6.5a4 4 0 0 1 5-5L17 4l.5 2.5L20 7l2.5-2.5a4 4 0 0 1-5 5L8 19a2.1 2.1 0 0 1-3-3l9.5-9.5Z',
  todo: 'M4 4h7v7H4V4Zm9 0h7v7h-7V4ZM4 13h7v7H4v-7Zm9 0h7v7h-7v-7Z',
}

export type NombreIcono = keyof typeof trazos

export function Icono({ nombre, className = 'm-icono' }: { nombre: string; className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d={trazos[nombre] ?? trazos.etiqueta} />
    </svg>
  )
}

/** Tono por categoría para las tarjetas sin foto (dentro de la paleta). */
export const TONOS: Record<string, string> = {
  abarrotes: '#0e7c5a', 'hecho-en-durango': '#a3461f', ropa: '#8a4f7d', mayoreo: '#6b5a2e',
  papeleria: '#2d6a7a', hogar: '#7a5b3a', servicios: '#4c5d73', otros: '#5c564d',
}
export const ICONO_CATEGORIA: Record<string, string> = {
  abarrotes: 'canasta', 'hecho-en-durango': 'hoja', ropa: 'camisa', mayoreo: 'caja',
  papeleria: 'lapiz', hogar: 'casa', servicios: 'herramienta', otros: 'etiqueta',
}
