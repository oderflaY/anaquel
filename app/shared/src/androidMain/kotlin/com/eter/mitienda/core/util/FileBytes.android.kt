package com.eter.mitienda.core.util

import java.io.File

actual fun readFileBytes(path: String): ByteArray = File(path).readBytes()
