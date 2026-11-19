package com.eter.mitienda.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun parseMoneyToCents_acceptsDotCommaAndPartialDecimals() {
        assertEquals(4_550, parseMoneyToCents("45.5"))
        assertEquals(4_550, parseMoneyToCents("45,50"))
        assertEquals(4_500, parseMoneyToCents("45"))
        assertEquals(4_500, parseMoneyToCents("45."))
        assertEquals(50, parseMoneyToCents(".5"))
        assertNull(parseMoneyToCents(""))
        assertNull(parseMoneyToCents("."))
        assertNull(parseMoneyToCents("1.234"))
        assertNull(parseMoneyToCents("abc"))
    }

    @Test
    fun isValidMoneyInput_allowsTypingStatesOnly() {
        assertTrue(isValidMoneyInput(""))
        assertTrue(isValidMoneyInput("12."))
        assertFalse(isValidMoneyInput("12.345"))
        assertFalse(isValidMoneyInput("1-2"))
    }

    @Test
    fun formatting_roundTrips() {
        assertEquals("$1,290.00", formatPrice(129_000))
        assertEquals("45.50", centsToInput(4_550))
        assertEquals(4_550, parseMoneyToCents(centsToInput(4_550)))
    }
}
