package com.eter.mitienda.core.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.eter.mitienda.core.image.topViewController
import platform.UIKit.UIActivityViewController

@Composable
actual fun rememberTextSharer(): (subject: String, text: String) -> Unit = remember {
    { _, text ->
        val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        topViewController()?.presentViewController(sheet, animated = true, completion = null)
    }
}
