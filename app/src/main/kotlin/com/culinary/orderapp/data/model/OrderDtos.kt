package com.culinary.orderapp.data.model

import com.culinary.orderapp.domain.model.Order
import com.culinary.orderapp.domain.model.OrderItem
import com.culinary.orderapp.domain.model.OrderStatus
import com.culinary.orderapp.domain.model.PaymentMethod
import com.culinary.orderapp.domain.model.SelectedTopping
import com.google.firebase.Timestamp
import java.util.Date

/**
 * Firestore DTO for a SelectedTopping.
 */
data class SelectedToppingDto(
    val toppingId: String = "",
    val toppingGroupId: String = "",
    val name: String = "",
    val additionalPrice: Long = 0L
) {
    fun toDomain() = SelectedTopping(
        toppingId = toppingId,
        toppingGroupId = toppingGroupId,
        name = name,
        additionalPrice = additionalPrice
    )

    companion object {
        fun fromDomain(st: SelectedTopping) = SelectedToppingDto(
            toppingId = st.toppingId,
            toppingGroupId = st.toppingGroupId,
            name = st.name,
            additionalPrice = st.additionalPrice
        )
    }
}

/**
 * Firestore DTO for an OrderItem.
 */
data class OrderItemDto(
    val id: String = "",
    val menuItemId: String = "",
    val menuItemName: String = "",
    val quantity: Int = 1,
    val unitPrice: Long = 0L,
    val selectedToppings: List<SelectedToppingDto> = emptyList(),
    val notes: String = ""
) {
    fun toDomain() = OrderItem(
        id = id,
        menuItemId = menuItemId,
        menuItemName = menuItemName,
        quantity = quantity,
        unitPrice = unitPrice,
        selectedToppings = selectedToppings.map { it.toDomain() },
        notes = notes
    )

    companion object {
        fun fromDomain(item: OrderItem) = OrderItemDto(
            id = item.id,
            menuItemId = item.menuItemId,
            menuItemName = item.menuItemName,
            quantity = item.quantity,
            unitPrice = item.unitPrice,
            selectedToppings = item.selectedToppings.map { SelectedToppingDto.fromDomain(it) },
            notes = item.notes
        )
    }
}

/**
 * Firestore DTO for an Order document.
 */
data class OrderDto(
    val id: String = "",
    val tableNumber: String = "",
    val customerName: String = "",
    val items: List<OrderItemDto> = emptyList(),
    val status: String = OrderStatus.PENDING.name,
    val paymentMethod: String = PaymentMethod.CASHIER.name,
    val notes: String = "",
    val createdAt: Timestamp = Timestamp.now(),
    val updatedAt: Timestamp = Timestamp.now(),
    val estimatedReadyMinutes: Int = 15,
    val restaurantId: String = ""
) {
    fun toDomain() = Order(
        id = id,
        tableNumber = tableNumber,
        customerName = customerName,
        items = items.map { it.toDomain() },
        status = runCatching { OrderStatus.valueOf(status) }.getOrDefault(OrderStatus.PENDING),
        paymentMethod = runCatching { PaymentMethod.valueOf(paymentMethod) }.getOrDefault(PaymentMethod.CASHIER),
        notes = notes,
        createdAt = createdAt.toDate(),
        updatedAt = updatedAt.toDate(),
        estimatedReadyMinutes = estimatedReadyMinutes,
        restaurantId = restaurantId
    )

    companion object {
        fun fromDomain(order: Order) = OrderDto(
            id = order.id,
            tableNumber = order.tableNumber,
            customerName = order.customerName,
            items = order.items.map { OrderItemDto.fromDomain(it) },
            status = order.status.name,
            paymentMethod = order.paymentMethod.name,
            notes = order.notes,
            createdAt = Timestamp(order.createdAt),
            updatedAt = Timestamp(Date()),
            estimatedReadyMinutes = order.estimatedReadyMinutes,
            restaurantId = order.restaurantId
        )
    }
}
