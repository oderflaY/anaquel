package com.eter.mitienda.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun formatDateTime(epochMillis: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX")).format(Date(epochMillis))
