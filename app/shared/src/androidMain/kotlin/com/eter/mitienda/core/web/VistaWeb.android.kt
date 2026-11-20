package com.eter.mitienda.core.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
actual fun VistaWeb(url: String, modifier: Modifier, alNavegar: (String) -> Boolean) {
    val ctx = LocalContext.current
    val navegar by rememberUpdatedState(alNavegar)
    val web = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url.toString()
                    if (navegar(u)) return true
                    // Otros esquemas (intent:, mailto:…) no los abre un WebView.
                    return !(u.startsWith("http://") || u.startsWith("https://"))
                }

                // Las redirecciones después de un POST (pago) no pasan por arriba: se avisan aquí.
                override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                    if (navegar(u)) view.stopLoading()
                }
            }
            // El mapa se arrastra con el dedo: que no mueva la pantalla de atrás.
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    // Se carga una vez por cada url pedida (la página puede cambiar su propia dirección).
    val cargada = remember { arrayOfNulls<String>(1) }
    AndroidView(factory = { web }, modifier = modifier, update = { w -> if (cargada[0] != url) { cargada[0] = url; w.loadUrl(url) } })
}
