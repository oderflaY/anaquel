package com.eter.mitienda.core.util

/** Lee un archivo local completo (fotos temporales ya comprimidas, < 200 KB). */
expect fun readFileBytes(path: String): ByteArray
