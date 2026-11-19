package com.eter.mitienda.core.util

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun formatDateTime(epochMillis: Long): String = NSDateFormatter().apply {
    dateFormat = "dd/MM/yyyy HH:mm"
    locale = NSLocale(localeIdentifier = "es_MX")
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
