package com.eter.mitienda.feature.anaquel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

// Íconos de la app (antes eran emojis): un solo estilo, redondeado.
object Ico {
    val Cobrar = Icons.Rounded.PointOfSale
    val Nuevo = Icons.Rounded.AddBox
    val Inventario = Icons.Rounded.Inventory2
    val Encargos = Icons.Rounded.Assignment
    val PedirHoy = Icons.Rounded.ShoppingCart
    val Foto = Icons.Rounded.PhotoCamera
    val Camion = Icons.Rounded.LocalShipping
    val Bodega = Icons.Rounded.ViewInAr
    val Buscar = Icons.Rounded.TravelExplore
    val Chat = Icons.Rounded.Forum
    val Trato = Icons.Rounded.Handshake
    val Asesor = Icons.Rounded.AutoAwesome
    val Graficas = Icons.Rounded.Insights
    val Categorias = Icons.Rounded.Category
    val Nube = Icons.Rounded.CloudUpload
    val Bluetooth = Icons.Rounded.Bluetooth
    val Fabrica = Icons.Rounded.Factory
    val Tienda = Icons.Rounded.Storefront
    val Oferta = Icons.Rounded.LocalOffer
    val Fuego = Icons.Rounded.LocalFireDepartment
    val Alerta = Icons.Rounded.WarningAmber
    val Ubicacion = Icons.Rounded.Place
    val Verificado = Icons.Rounded.Verified
    val Paquete = Icons.Rounded.Inventory
    val Mayoreo = Icons.Rounded.Sell
    val Candado = Icons.Rounded.Lock
    val Mundo = Icons.Rounded.Public
    val Mapa = Icons.Rounded.Map
}

/** Ícono para un producto según su nombre (la red aún no tiene fotos). */
fun iconoDe(t: String, tipo: String = "producto"): ImageVector {
    val n = t.lowercase()
    val mapa: List<Pair<List<String>, ImageVector>> = listOf(
        listOf("leche", "yogurt", "agua", "refresco", "jugo", "bebida") to Icons.Rounded.LocalDrink,
        listOf("cerveza", "vino") to Icons.Rounded.SportsBar,
        listOf("café") to Icons.Rounded.LocalCafe,
        listOf("pan", "galleta", "pastel") to Icons.Rounded.BakeryDining,
        listOf("huevo") to Icons.Rounded.Egg,
        listOf("atún", "sardina", "pescado") to Icons.Rounded.SetMeal,
        listOf("jamón", "salchicha", "carne", "pollo") to Icons.Rounded.KebabDining,
        listOf("arroz", "frijol", "azúcar", "harina", "maíz", "sopa", "pasta", "sal ", "aceite") to Icons.Rounded.RiceBowl,
        listOf("papas", "botana", "cacahuate", "chocolate", "dulce", "paleta") to Icons.Rounded.Cookie,
        listOf("papel higiénico", "jabón", "cloro", "detergente", "limpi") to Icons.Rounded.CleaningServices,
        listOf("cuaderno", "pluma", "lápiz", "papel", "hojas", "cinta", "tijera", "mochila") to Icons.Rounded.EditNote,
        listOf("gorra", "playera", "sudadera", "pantalón", "vestido", "calcet") to Icons.Rounded.Checkroom,
        listOf("zapato", "tenis") to Icons.Rounded.IceSkating,
        listOf("piñata", "fiesta") to Icons.Rounded.Celebration,
        listOf("flete", "envío") to Icons.Rounded.LocalShipping,
        listOf("impres") to Icons.Rounded.Print,
        listOf("foco", "luz") to Icons.Rounded.Lightbulb,
        listOf("pintura") to Icons.Rounded.FormatPaint,
        listOf("martillo", "herramienta", "tornillo", "clavo") to Icons.Rounded.Handyman,
        listOf("medicina", "farmacia", "pastilla") to Icons.Rounded.Medication,
    )
    return mapa.firstOrNull { (ks, _) -> ks.any { n.contains(it) } }?.second ?: when (tipo) {
        "solicitud" -> Icons.Rounded.ManageSearch; "anuncio" -> Icons.Rounded.Campaign; "productor" -> Icons.Rounded.Agriculture
        else -> Icons.Rounded.Inventory2
    }
}
