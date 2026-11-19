package com.eter.mitienda.di

/**
 * Lo implementa la Application de Android para que los componentes que el
 * sistema crea por su cuenta (p. ej. los Workers de recordatorios) lleguen al
 * contenedor único sin abrir una segunda conexión a la BD.
 */
interface AppContainerProvider {
    val container: AppContainer
}
