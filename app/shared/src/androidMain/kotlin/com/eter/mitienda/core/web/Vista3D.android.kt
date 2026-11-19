package com.eter.mitienda.core.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

actual val hay3D: Boolean = true

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
actual fun Vista3D(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val web = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient = WebViewClient()
            // Girar y pellizcar la escena no debe mover la lista de la pantalla.
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    AndroidView(factory = { web }, modifier = modifier, update = { w ->
        // Mismo documento y solo cambia el hash: el navegador no recarga, dispara «hashchange».
        if (w.url != url) w.loadUrl(url)
    })
}
