package com.eter.mitienda.feature.mercado

/**
 * Guarda el token de sesión del mercado cifrado por el sistema operativo:
 * Android Keystore (AES-GCM, la llave nunca sale del hardware seguro) o el
 * Llavero de iOS. Si el teléfono se respalda o se roba el almacenamiento de la
 * app, el token no se puede leer.
 */
expect class AlmacenSeguro() {
    fun guardar(clave: String, valor: String?)
    fun leer(clave: String): String?
}

/** Dirección por defecto del mercado en esta plataforma (desarrollo local). */
expect val SERVIDOR_MERCADO_DEFECTO: String
