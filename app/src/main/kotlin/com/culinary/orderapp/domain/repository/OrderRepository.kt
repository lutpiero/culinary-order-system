package com.culinary.orderapp.domain.repository

import com.culinary.orderapp.domain.model.Order
import com.culinary.orderapp.domain.model.OrderStatus
import com.culinary.orderapp.domain.model.SalesSummary
import kotlinx.coroutines.flow.Flow
import java.util.Date

/**
 * Repository contract for order-related operations.
 */
interface OrderRepository {

    /** Emits a live list of orders, optionally filtered by status. */
    fun observeOrders(status: OrderStatus? = null): Flow<List<Order>>

    /** Fetches a single order by its ID. */
    suspend fun getOrderById(id: String): Order?

    /** Creates a new order in the backend. */
    suspend fun createOrder(order: Order): Result<Order>

    /** Updates an existing order's status and optionally sets estimated ready time. */
    suspend fun updateOrderStatus(
        orderId: String,
        newStatus: OrderStatus,
        estimatedMinutes: Int? = null
    ): Result<Unit>

    /** Updates an order. */
    suspend fun updateOrder(order: Order): Result<Order>

    /** Cancels an order. */
    suspend fun cancelOrder(orderId: String): Result<Unit>

    /**
     * Generates a financial summary for orders within the given date range.
     */
    suspend fun getSalesSummary(from: Date, to: Date): Result<SalesSummary>
}
