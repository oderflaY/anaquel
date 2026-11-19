import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { guardarToken } from './api'

// La app móvil abre la bodega 3D con la sesión en el #t=… (no viaja al servidor).
const hash = new URLSearchParams(location.hash.slice(1))
if (hash.get('t')) {
  guardarToken(hash.get('t'))
  if (hash.get('tema') === 'dark' || hash.get('tema') === 'light') document.documentElement.dataset.theme = hash.get('tema')!
  history.replaceState(null, '', location.pathname + location.search)
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
