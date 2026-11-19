package com.eter.mitienda.domain.model

/**
 * Producto del inventario. Modelo de dominio puro: no conoce SQLite ni Compose.
 *
 * Los importes viven en centavos (`Long`) dentro de la app para evitar errores
 * de redondeo de punto flotante en sumas y reportes. La capa de datos los
 * convierte a/desde las columnas REAL del esquema.
 */
data class Product(
    /** 0 = producto nuevo (aún sin fila en la BD). */
    val id: Long = 0,
    val barcode: String,
    val name: String,
    val description: String? = null,
    val priceCents: Long,
    val costCents: Long,
    val stock: Int,
    /** Por debajo o igual a este número el producto se marca como "Stock bajo". */
    val minStock: Int = DEFAULT_MIN_STOCK,
    val category: Category?,
    /** Dónde está físicamente en la tienda ("Pasillo 2 · Estante B"). */
    val location: String? = null,
    /** Ruta local del archivo de imagen (nunca bytes). `null` = placeholder. */
    val imagePath: String? = null,
    /** Última modificación en milisegundos desde epoch. */
    val updatedAt: Long = 0,
) {
    val isLowStock: Boolean get() = stock <= minStock

    companion object {
        const val DEFAULT_MIN_STOCK = 5
    }
}

/** Categoría creada por el usuario. "Todos" no es una categoría: es la ausencia de filtro. */
data class Category(
    val id: Long,
    val name: String,
)

/** Categoría con su número de productos, para la pantalla de gestión. */
data class CategoryWithCount(
    val category: Category,
    val productCount: Int,
)
