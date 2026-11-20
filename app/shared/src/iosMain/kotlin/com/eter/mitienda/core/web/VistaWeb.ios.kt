package com.eter.mitienda.core.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

private class Vigia(var alNavegar: (String) -> Boolean) : NSObject(), WKNavigationDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val u = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
        val propia = alNavegar(u) || !(u.startsWith("http://") || u.startsWith("https://"))
        decisionHandler(if (propia) WKNavigationActionPolicy.WKNavigationActionPolicyCancel else WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
    }
}

@OptIn(ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun VistaWeb(url: String, modifier: Modifier, alNavegar: (String) -> Boolean) {
    val navegar by rememberUpdatedState(alNavegar)
    val vigia = remember { Vigia { navegar(it) } }
    val web = remember {
        WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration()).apply { navigationDelegate = vigia }
    }
    val cargada = remember { arrayOfNulls<String>(1) }
    UIKitView(
        factory = { web },
        modifier = modifier,
        update = { w -> if (cargada[0] != url) { cargada[0] = url; NSURL.URLWithString(url)?.let { w.loadRequest(NSURLRequest.requestWithURL(it)) } } },
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
    )
}
