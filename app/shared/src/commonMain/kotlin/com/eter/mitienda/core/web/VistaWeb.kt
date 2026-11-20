package com.eter.mitienda.core.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Página web dentro de la app (mapa de la red, pago de Stripe). [alNavegar] ve cada
 * dirección a la que la página quiere ir (también anaquel://… y tel:) y la página
 * que empieza a cargar; si devuelve true, la app se encarga y el WebView no la abre.
 */
@Composable
expect fun VistaWeb(url: String, modifier: Modifier = Modifier, alNavegar: (String) -> Boolean = { false })
