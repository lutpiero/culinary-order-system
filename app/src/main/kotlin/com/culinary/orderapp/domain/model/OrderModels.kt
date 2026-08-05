package com.culinary.orderapp.domain.model

import java.util.Date
import java.util.UUID

/**
 * Payment method chosen by the customer.
 */
enum class PaymentMethod(val displayName: String) {
    QRIS("QRIS"),
    BANK_TRANSFER("Transfer Bank"),
    CASHIER("Bayar di Kasir")
}

/**
 * Status of an order, progressing through its lifecycle.
 */
enum class OrderStatus(val displayName: String) {
    PENDING("Menunggu Konfirmasi"),
    IN_QUEUE("Dalam Antrian"),
    PREPARING("Sedang Disiapkan"),
    READY("Siap Diambil"),
    SERVED("Sudah Disajikan"),
    CANCELLED("Dibatalkan")
}

/**
 * Payment status for tracking QRIS and other online payment transactions.
 */
enum class PaymentStatus(val displayName: String) {
    PENDING("Menunggu Pembayaran"),
    PAID("Sudah Dibayar"),
    FAILED("Pembayaran Gagal"),
    CANCELLED("Pembayaran Dibatalkan")
}

/**
 * A selected topping that has been added to an order item.
 */
data class SelectedTopping(
    val toppingId: String = "",
    val toppingGroupId: String = "",
    val name: String = "",
    val additionalPrice: Long = 0L
)

/**
 * An item within an order, including quantity and selected customisations.
 */
data class OrderItem(
    val id: String = UUID.randomUUID().toString(),
    val menuItemId: String = "",
    val menuItemName: String = "",
    val quantity: Int = 1,
    val unitPrice: Long = 0L,
    val selectedToppings: List<SelectedTopping> = emptyList(),
    val notes: String = ""
) {
    val toppingsTotalPrice: Long
        get() = selectedToppings.sumOf { it.additionalPrice } * quantity

    val subtotal: Long
        get() = (unitPrice * quantity) + toppingsTotalPrice
}

/**
 * Represents a complete customer order.
 * Includes payment tracking for QRIS and other online payment methods.
 */
data class Order(
     val id: String = UUID.randomUUID().toString(),
     val tableNumber: String = "",
     val sessionId: String = "",    // Unique session ID to identify customer visiting a table
     val customerName: String = "",
     val items: List<OrderItem> = emptyList(),
     val status: OrderStatus = OrderStatus.PENDING,
     val paymentMethod: PaymentMethod = PaymentMethod.CASHIER,
     val paymentStatus: PaymentStatus = PaymentStatus.PENDING,  // For tracking QRIS/online payments
     val paymentRefNo: String = "",  // Reference number for QRIS payment
     val notes: String = "",
     val createdAt: Date = Date(),
     val updatedAt: Date = Date(),
     val estimatedReadyMinutes: Int = 15,
     val restaurantId: String = ""
 ) {
     val totalAmount: Long
         get() = items.sumOf { it.subtotal }
 }

/**
 * Summary data used for the finance dashboard.
 */
data class SalesSummary(
    val periodLabel: String = "",
    val totalRevenue: Long = 0L,
    val totalOrders: Int = 0,
    val qrisRevenue: Long = 0L,
    val bankTransferRevenue: Long = 0L,
    val cashierRevenue: Long = 0L,
    val averageOrderValue: Long = 0L
)
