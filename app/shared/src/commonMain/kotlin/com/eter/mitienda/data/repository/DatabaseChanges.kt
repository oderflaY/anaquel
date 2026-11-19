package com.eter.mitienda.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reactividad sin ORM: cada escritura exitosa incrementa [version] y todo el
 * que observe tablas vuelve a leer. Una sola instancia compartida por los
 * repositorios, porque las tablas se cruzan: renombrar una categoría cambia
 * lo que muestra cada producto.
 */
class DatabaseChanges {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun notifyChanged() = _version.update { it + 1 }
}
