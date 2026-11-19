package com.eter.mitienda.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tipografía. Se usa la sans-serif del sistema (Roboto / SF Pro); para una fuente
 * redondeada (p. ej. Nunito) basta con agregar los .ttf en composeResources/font
 * y sustituir [NeuFontFamily] por `FontFamily(Font(Res.font.nunito_regular), ...)`.
 *
 * Los colores no se fijan aquí: los aporta LocalContentColor (TextPrimary) o
 * cada componente, para que un mismo estilo sirva sobre fondo y sobre acento.
 */
val NeuFontFamily: FontFamily = FontFamily.SansSerif

val NeuTypography = Typography(
    // Título de pantalla ("Inventario")
    headlineMedium = TextStyle(
        fontFamily = NeuFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    // Nombre de producto / precio
    titleMedium = TextStyle(
        fontFamily = NeuFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    // Texto de inputs
    bodyLarge = TextStyle(
        fontFamily = NeuFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    // Subtítulos
    bodyMedium = TextStyle(
        fontFamily = NeuFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // Metadatos (código de barras)
    bodySmall = TextStyle(
        fontFamily = NeuFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    // Texto de botones
    labelLarge = TextStyle(
        fontFamily = NeuFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    // Chips y badges
    labelMedium = TextStyle(
        fontFamily = NeuFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)
