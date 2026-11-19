package com.eter.mitienda.core.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Si esta plataforma puede mostrar la bodega 3D (WebGL en un WebView). */
expect val hay3D: Boolean

/**
 * Bodega 3D (la misma del panel) dentro de la app. [url] es la página con todo en el hash: al cambiar
 * solo el hash no se recarga, solo se mueve la escena.
 */
@Composable
expect fun Vista3D(url: String, modifier: Modifier = Modifier)
