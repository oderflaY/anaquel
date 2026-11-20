# Servicios de Anaquel Mercado

Mercado local de Durango, exclusivo para negocios verificados. Vive junto al
Anaquel actual (panel, bodega 3D) sin modificarlo: una **pasarela** recibe todo
el tráfico, manda `/api/acceso|mercado|reels|pagos` a estos servicios y lo
demás al núcleo de siempre.

```
navegador ──HTTPS──► proxy del servidor ──► pasarela :8080
                                              ├─► núcleo  (backend/ actual, panel + 3D + web)
                                              ├─► acceso  :8101  cuentas, sesiones, invitaciones
                                              ├─► mercado :8102  publicaciones, fotos, órdenes
                                              ├─► reels   :8103  videos cortos
                                              └─► pagos   :8104  terminales, cobros, registro de cobros
                                                    ▲
                     Mercado Pago / Clip / terminal virtual ──webhook firmado──┘
Postgres: un esquema y un usuario por servicio (acceso_app, mercado_app, reels_app, pagos_app).
```

## Correr en local

```bash
cd web && npm ci && npm run build && cd ..
./services/correr-local.sh          # Postgres en Docker + núcleo + 5 servicios
# http://localhost:8080/anaquel/mercado   (cuentas demo, contraseña demo1234)
./services/correr-local.sh parar
```

Para desarrollar la web con recarga: `API_TARGET=http://127.0.0.1:8080 npm run dev` (en `web/`).

## Pruebas

```bash
cd services
go test ./internal/...                                   # unitarias (37)
CONFIAR_PROXY=1 ./correr-local.sh                        # entorno de ataque
set -a; source .env.local; set +a
ANAQUEL_URL=http://localhost:8080 go test ./pruebas/ -v  # 57 ataques contra el sistema en marcha
```

## Estructura

| Carpeta | Qué hay |
|---|---|
| `cmd/<servicio>` | `main` de cada servicio (un binario y una imagen por servicio) |
| `internal/plataforma` | Respuestas JSON, errores, identidad firmada, BD y migraciones, cifrado |
| `internal/pasarela` | Ruteo, cabeceras de seguridad, CSRF, límites, sesión → identidad |
| `internal/acceso` | Login (extraído del panel), argon2id, sesiones, invitaciones, admin |
| `internal/mercado` | Catálogo, búsqueda, fotos (re-codificadas), órdenes con RLS |
| `internal/reels` | Subida y validación estructural de MP4, feed |
| `internal/pagos` | Interfaz `Terminal` + Mercado Pago Point, Clip y simulador; registro de cobros |
| `internal/*/migraciones` | SQL versionado por servicio (se aplica solo al arrancar) |
| `pruebas/` | Suite de ataques de punta a punta |

## Modelo de seguridad (resumen)

- **Sesión**: token aleatorio de 256 bits en cookie `__Host-` `HttpOnly; Secure; SameSite=Strict`; en BD solo su SHA-256. 12 h sin uso / 7 días máximo.
- **Identidad interna**: la pasarela convierte la cookie en una identidad firmada (HMAC, 60 s). Los servicios rechazan lo que no venga firmado; la pasarela borra cualquier `X-Anaquel-*` de fuera.
- **Protocolo de acceso**: registro solo con invitación de un negocio verificado + ubicación en Durango → cuenta `pendiente` → un admin la verifica.
- **Contraseñas**: argon2id (OWASP), mínimo 10, sin comunes ni el correo; bloqueo progresivo por correo y por IP (aunque el correo no exista).
- **Datos personales**: correo y teléfono cifrados con AES-256-GCM; el correo se busca por índice ciego (HMAC).
- **BD**: un rol por servicio sin acceso a los demás esquemas; RLS en órdenes; bitácora y registro de cobros inmutables por trigger.
- **Archivos**: fotos re-codificadas (sin EXIF/GPS ni contenido escondido), MP4 validado por estructura, todo confinado con `os.Root`.
- **Pagos**: monto siempre desde la orden; idempotencia; un cobro activo/aprobado por orden (índices únicos); webhooks con firma, ventana de 10 min y deduplicación; se consulta el estado real a la marca.

## Pagos con terminal

`internal/pagos/terminales.go` define la interfaz `Terminal`. Para agregar otra
marca, implementa `Cobrar`, `Consultar`, `Cancelar` y `Webhook`.

- **Mercado Pago Point**: Orders API (`POST /v1/orders`, `type: point`). La terminal debe estar en **modo PDV** y ligada a la cuenta del `MP_ACCESS_TOKEN`. Webhook: `https://<dominio>/anaquel/api/pagos/webhooks/mercadopago` con el secreto en `MP_WEBHOOK_SECRET`.
- **Clip**: PinPad API; los campos siguen documentación de terceros y deben confirmarse en developer.clip.mx. Sin webhook firmado: el conciliador consulta cada 5 s.
- **Cobro directo**: si cobras en el aparato sin usar la app, la notificación llega igual y el pago se registra a nombre del dueño de la terminal; después se puede ligar a una orden del mismo monto.

## Desplegar

`deploy/docker-compose.servicios.yml` + `deploy/.env` (plantilla en `deploy/.env.ejemplo`).
Para producción: `DEMO=0`, `COOKIE_INSEGURA` sin definir, `HSTS=1`, `CONFIAR_PROXY=1` solo detrás del proxy HTTPS.
