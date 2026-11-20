package com.eter.mitienda.feature.mercado

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Contexto de la app, puesto por [com.eter.mitienda.MiTiendaApplication] al arrancar.
 */
object ContextoMercado {
    lateinit var app: Context
}

/** El emulador de Android ve a la computadora como 10.0.2.2. */
actual val SERVIDOR_MERCADO_DEFECTO: String = "http://10.0.2.2:8080/anaquel"

/**
 * AES-256-GCM con una llave del Android Keystore (no exportable). En las
 * preferencias solo queda el texto cifrado: IV(12) | cifrado | etiqueta.
 */
actual class AlmacenSeguro actual constructor() {
    private val alias = "anaquel_mercado_v1"
    private val prefs = ContextoMercado.app.getSharedPreferences("mercado_seguro", Context.MODE_PRIVATE)

    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    actual fun guardar(clave: String, valor: String?) {
        if (valor == null) {
            prefs.edit().remove(clave).apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, llave())
        val cifrado = c.iv + c.doFinal(valor.encodeToByteArray())
        prefs.edit().putString(clave, Base64.encodeToString(cifrado, Base64.NO_WRAP)).apply()
    }

    actual fun leer(clave: String): String? {
        val b64 = prefs.getString(clave, null) ?: return null
        return runCatching {
            val datos = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(128, datos, 0, 12))
            c.doFinal(datos, 12, datos.size - 12).decodeToString()
        }.getOrElse {
            // Llave perdida o dato alterado: se descarta y se vuelve a pedir el login.
            prefs.edit().remove(clave).apply()
            null
        }
    }
}
