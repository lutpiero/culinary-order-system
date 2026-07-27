package com.culinary.orderapp

import com.culinary.orderapp.domain.model.MenuItem
import com.culinary.orderapp.domain.model.Order
import com.culinary.orderapp.domain.model.OrderItem
import com.culinary.orderapp.domain.model.OrderStatus
import com.culinary.orderapp.domain.model.PaymentMethod
import com.culinary.orderapp.domain.model.SelectedTopping
import com.culinary.orderapp.util.toRupiahFormat
import com.culinary.orderapp.util.toMinutesDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the domain model logic and utility functions.
 */
class DomainModelTest {

    // ---- OrderItem subtotal ----

    @Test
    fun `orderItem subtotal with no toppings equals unitPrice times quantity`() {
        val item = OrderItem(
            menuItemId = "item1",
            menuItemName = "Nasi Goreng",
            quantity = 2,
            unitPrice = 25_000L
        )
        assertEquals(50_000L, item.subtotal)
    }

    @Test
    fun `orderItem subtotal includes toppings price`() {
        val item = OrderItem(
            menuItemId = "item1",
            menuItemName = "Nasi Goreng",
            quantity = 2,
            unitPrice = 25_000L,
            selectedToppings = listOf(
                SelectedTopping(name = "Extra Telur", additionalPrice = 5_000L)
            )
        )
        // (25000 * 2) + (5000 * 2) = 60000
        assertEquals(60_000L, item.subtotal)
    }

    // ---- Order totalAmount ----

    @Test
    fun `order totalAmount sums all item subtotals`() {
        val order = Order(
            tableNumber = "5",
            items = listOf(
                OrderItem(menuItemId = "i1", menuItemName = "Mie Goreng", quantity = 1, unitPrice = 20_000L),
                OrderItem(menuItemId = "i2", menuItemName = "Es Teh", quantity = 2, unitPrice = 5_000L)
            )
        )
        // 20000 + (5000*2) = 30000
        assertEquals(30_000L, order.totalAmount)
    }

    @Test
    fun `order with no items has zero totalAmount`() {
        val order = Order(tableNumber = "1", items = emptyList())
        assertEquals(0L, order.totalAmount)
    }

    // ---- Format utilities ----

    @Test
    fun `toRupiahFormat formats zero correctly`() {
        assertEquals("Rp 0", 0L.toRupiahFormat())
    }

    @Test
    fun `toRupiahFormat formats thousands correctly`() {
        val result = 15_000L.toRupiahFormat()
        assertTrue("Expected 'Rp 15.000' but got '$result'", result.contains("15"))
    }

    @Test
    fun `toMinutesDisplay for less than 60 minutes`() {
        assertEquals("45 menit", 45.toMinutesDisplay())
    }

    @Test
    fun `toMinutesDisplay for exact hours`() {
        assertEquals("2 jam", 120.toMinutesDisplay())
    }

    @Test
    fun `toMinutesDisplay for hours and minutes`() {
        assertEquals("1 jam 30 menit", 90.toMinutesDisplay())
    }

    // ---- OrderStatus ----

    @Test
    fun `all order statuses have display names in Bahasa Indonesia`() {
        OrderStatus.values().forEach { status ->
            assertTrue(
                "Status ${status.name} should have a non-empty display name",
                status.displayName.isNotBlank()
            )
        }
    }

    @Test
    fun `all payment methods have display names in Bahasa Indonesia`() {
        PaymentMethod.values().forEach { method ->
            assertTrue(
                "PaymentMethod ${method.name} should have a non-empty display name",
                method.displayName.isNotBlank()
            )
        }
    }

    // ---- MenuItem defaults ----

    @Test
    fun `new MenuItem has isAvailable true by default`() {
        val item = MenuItem(name = "Test Item", price = 10_000L)
        assertTrue(item.isAvailable)
    }
}
