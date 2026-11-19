package com.eter.mitienda.domain.repository

import com.eter.mitienda.domain.model.CartItem
import com.eter.mitienda.domain.model.Sale

interface SaleRepository {
    /**
     * Registra la venta y descuenta el stock de cada producto, todo en UNA
     * transacción: o se guarda completa o no se guarda nada.
     * @return la venta guardada (con folio y fecha) para mostrar el ticket.
     */
    suspend fun registerSale(items: List<CartItem>, receivedCents: Long?): Result<Sale>

    suspend fun getSale(id: Long): Result<Sale?>
}
