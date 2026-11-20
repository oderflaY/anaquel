package com.eter.mitienda.feature.anaquel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.eter.mitienda.core.web.VistaWeb

/**
 * Mapa de los negocios asociados: la página /panel/mapa del servidor (Leaflet)
 * dentro de la app. Sus botones mandan anaquel://chat|pedir|perfil/{tienda} y
 * aquí se abren el chat, el catálogo para pedir o la hoja de perfil nativos.
 */
@Composable
fun MapaRoute(vm: AnaquelViewModel, onVolver: () -> Unit) {
    val st by vm.state.collectAsState()
    val uri = LocalUriHandler.current
    val url = remember(st.tienda?.id) { vm.urlMapa() }
    Box(Modifier.fillMaxSize()) {
        if (url != null) VistaWeb(url, Modifier.fillMaxSize()) { u ->
            when {
                u.startsWith("anaquel://") -> {
                    val partes = u.removePrefix("anaquel://").split('/')
                    val id = partes.getOrNull(1)?.toLongOrNull()
                    if (id != null) when (partes[0]) {
                        "chat" -> vm.abrirChatCon(id)
                        "pedir" -> vm.abrirChatCon(id, pedir = true)
                        "perfil" -> vm.verPerfil(id)
                    }
                    true
                }
                u.startsWith("tel:") -> { runCatching { uri.openUri(u) }; true }
                else -> false
            }
        } else Txt("Entra a tu cuenta de Anaquel para ver el mapa.", secundario = true, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.statusBarsPadding().padding(start = 12.dp, top = 58.dp)) {
            BotonCircular(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", 44) { onVolver() }
        }
    }
    st.perfil?.let { p ->
        HojaPerfil(p, onCerrar = vm::cerrarPerfil,
            onChat = { vm.cerrarPerfil(); vm.abrirChatCon(p.id) },
            onPedir = { vm.cerrarPerfil(); vm.abrirChatCon(p.id, pedir = true) },
            onProducto = { vm.cerrarPerfil(); vm.abrirChatCon(p.id, true, it.productoId, it.minimoMayoreo.coerceAtLeast(1)) })
    }
}
