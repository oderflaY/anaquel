package com.eter.mitienda.core.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIColor
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

actual val hay3D: Boolean = true

/** Bodega 3D en un WKWebView. Los dedos se quedan en la escena (girar y pellizcar), no mueven la lista. */
@OptIn(ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun Vista3D(url: String, modifier: Modifier) {
    val web = remember {
        WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration()).apply {
            setOpaque(false)
            backgroundColor = UIColor.clearColor
            scrollView.scrollEnabled = false
            scrollView.bounces = false
        }
    }
    UIKitView(
        factory = { web },
        modifier = modifier,
        update = { w ->
            // Si solo cambia el hash, WebKit no recarga: la página recibe «hashchange».
            if (w.URL?.absoluteString != url) NSURL.URLWithString(url)?.let { w.loadRequest(NSURLRequest.requestWithURL(it)) }
        },
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
    )
}
