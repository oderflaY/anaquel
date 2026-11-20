package com.eter.mitienda.feature.mercado

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** El simulador de iOS ve a la computadora como localhost. */
actual val SERVIDOR_MERCADO_DEFECTO: String = "http://localhost:8080/anaquel"

/**
 * Llavero de iOS (kSecClassGenericPassword), accesible solo en este equipo y
 * después del primer desbloqueo. Nota: escrito para iOS pero no compilado en
 * esta máquina (Windows no compila objetivos de Apple); revisar en Xcode.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class AlmacenSeguro actual constructor() {
    private val servicio = "mx.anaquel.mercado"

    private fun consulta(clave: String, extra: Map<Any?, Any?> = emptyMap()): CFDictionaryRef? =
        CFBridgingRetain(
            mapOf<Any?, Any?>(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to servicio,
                kSecAttrAccount to clave,
            ) + extra,
        ) as CFDictionaryRef?

    actual fun guardar(clave: String, valor: String?) {
        SecItemDelete(consulta(clave))
        if (valor == null) return
        val datos = (valor as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        SecItemAdd(consulta(clave, mapOf(kSecValueData to datos, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)), null)
    }

    actual fun leer(clave: String): String? = memScoped {
        val salida = alloc<CFTypeRefVar>()
        val estado = SecItemCopyMatching(consulta(clave, mapOf(kSecReturnData to true, kSecMatchLimit to kSecMatchLimitOne)), salida.ptr)
        if (estado != errSecSuccess) return null
        val datos = CFBridgingRelease(salida.value) as? NSData ?: return null
        NSString.create(data = datos, encoding = NSUTF8StringEncoding)?.toString()
    }
}
