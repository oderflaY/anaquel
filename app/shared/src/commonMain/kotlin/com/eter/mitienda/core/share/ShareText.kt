package com.eter.mitienda.core.share

import androidx.compose.runtime.Composable

/**
 * Abre el menú "Compartir" del sistema con un texto (WhatsApp, correo, SMS…).
 * No requiere internet ni permisos: la app de destino decide cómo enviarlo.
 */
@Composable
expect fun rememberTextSharer(): (subject: String, text: String) -> Unit
