package com.eter.mitienda.domain.model

/**
 * Errores de datos que la UI sabe explicar. La capa de datos traduce las
 * excepciones de SQLite a estos tipos para que ninguna excepción cruda llegue
 * a la pantalla ni tumbe la app.
 */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Violación del índice UNIQUE de `codigo_barras`. */
    class DuplicateBarcode(val barcode: String) :
        DataError("Ya existe un producto con el código $barcode")

    /** Ya existe una categoría con ese nombre (sin distinguir mayúsculas). */
    class DuplicateCategory(val name: String) :
        DataError("Ya existe la categoría \"$name\"")

    /** SQLITE_BUSY / SQLITE_LOCKED tras agotar el busy_timeout. */
    class DatabaseBusy(cause: Throwable) :
        DataError("La base de datos está ocupada. Intenta de nuevo en unos segundos.", cause)

    /** Violación de un CHECK (p. ej. stock o precio negativo). */
    class InvalidData(cause: Throwable) :
        DataError("Los datos no son válidos (revisa precios y stock).", cause)

    class NotFound(id: Long) :
        DataError("El producto $id ya no existe.")

    class Unknown(cause: Throwable) :
        DataError("Error inesperado de la base de datos.", cause)
}
