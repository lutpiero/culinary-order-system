package com.culinary.orderapp.domain.usecase

import com.culinary.orderapp.domain.model.Order
import com.culinary.orderapp.domain.model.OrderStatus
import com.culinary.orderapp.domain.model.PaymentMethod
import com.culinary.orderapp.domain.model.SalesSummary
import com.culinary.orderapp.domain.repository.OrderRepository
import kotlinx.coroutines.flow.Flow
import java.util.Date
import javax.inject.Inject

class ObserveOrdersUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    operator fun invoke(status: OrderStatus? = null): Flow<List<Order>> =
        orderRepository.observeOrders(status)
}

class ObserveUnpaidPaymentsUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    operator fun invoke(paymentMethods: List<PaymentMethod>): Flow<List<Order>> =
        orderRepository.observeUnpaidPayments(paymentMethods)
}

class MarkOrderPaidUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    /**
     * Records an offline payment (cashier / bank transfer).
     * QRIS orders are intentionally rejected — QRIS confirmation is handled
     * exclusively by the Netlify backend after DSP verification.
     */
    suspend operator fun invoke(
        order: Order,
        amountReceived: Long? = null,
        paymentProofUrl: String? = null
    ): Result<Unit> {
        if (order.paymentMethod == PaymentMethod.QRIS) {
            return Result.failure(IllegalStateException("Pembayaran QRIS tidak dapat dikonfirmasi dari Kasir"))
        }
        return orderRepository.markOrderPaid(order.id, amountReceived, paymentProofUrl)
    }
}

class GetOrderByIdUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    suspend operator fun invoke(orderId: String): Order? =
        orderRepository.getOrderById(orderId)
}

class UpdateOrderStatusUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    suspend operator fun invoke(
        orderId: String,
        newStatus: OrderStatus,
        estimatedMinutes: Int? = null
    ): Result<Unit> = orderRepository.updateOrderStatus(orderId, newStatus, estimatedMinutes)
}

class CancelOrderUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    suspend operator fun invoke(orderId: String): Result<Unit> =
        orderRepository.cancelOrder(orderId)
}

class GetSalesSummaryUseCase @Inject constructor(
    private val orderRepository: OrderRepository
) {
    suspend operator fun invoke(from: Date, to: Date): Result<SalesSummary> =
        orderRepository.getSalesSummary(from, to)
}
