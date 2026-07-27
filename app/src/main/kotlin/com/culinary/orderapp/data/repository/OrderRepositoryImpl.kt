package com.culinary.orderapp.data.repository

import com.culinary.orderapp.data.model.OrderDto
import com.culinary.orderapp.domain.model.Order
import com.culinary.orderapp.domain.model.OrderStatus
import com.culinary.orderapp.domain.model.PaymentMethod
import com.culinary.orderapp.domain.model.SalesSummary
import com.culinary.orderapp.domain.repository.OrderRepository
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrderRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore
) : OrderRepository {

    private val ordersCollection get() = firestore.collection("orders")

    override fun observeOrders(status: OrderStatus?): Flow<List<Order>> = callbackFlow {
        val query = if (status != null) {
            ordersCollection
                .whereEqualTo("status", status.name)
                .orderBy("createdAt", Query.Direction.DESCENDING)
        } else {
            ordersCollection
                .orderBy("createdAt", Query.Direction.DESCENDING)
        }
        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val orders = snapshot?.documents?.mapNotNull { doc ->
                runCatching {
                    doc.toObject(OrderDto::class.java)?.copy(id = doc.id)?.toDomain()
                }.getOrNull()
            } ?: emptyList()
            trySend(orders)
        }
        awaitClose { listener.remove() }
    }

    override suspend fun getOrderById(id: String): Order? {
        return runCatching {
            ordersCollection.document(id).get().await()
                .toObject(OrderDto::class.java)?.copy(id = id)?.toDomain()
        }.getOrNull()
    }

    override suspend fun createOrder(order: Order): Result<Order> {
        return runCatching {
            val dto = OrderDto.fromDomain(order)
            val docRef = ordersCollection.add(dto).await()
            order.copy(id = docRef.id)
        }
    }

    override suspend fun updateOrderStatus(
        orderId: String,
        newStatus: OrderStatus,
        estimatedMinutes: Int?
    ): Result<Unit> {
        return runCatching {
            val updates = mutableMapOf<String, Any>(
                "status" to newStatus.name,
                "updatedAt" to Timestamp.now()
            )
            estimatedMinutes?.let { updates["estimatedReadyMinutes"] = it }
            ordersCollection.document(orderId).update(updates).await()
        }
    }

    override suspend fun updateOrder(order: Order): Result<Order> {
        return runCatching {
            val dto = OrderDto.fromDomain(order)
            ordersCollection.document(order.id).set(dto).await()
            order
        }
    }

    override suspend fun cancelOrder(orderId: String): Result<Unit> {
        return updateOrderStatus(orderId, OrderStatus.CANCELLED)
    }

    override suspend fun getSalesSummary(from: Date, to: Date): Result<SalesSummary> {
        return runCatching {
            val snapshot = ordersCollection
                .whereGreaterThanOrEqualTo("createdAt", Timestamp(from))
                .whereLessThanOrEqualTo("createdAt", Timestamp(to))
                .whereNotEqualTo("status", OrderStatus.CANCELLED.name)
                .get()
                .await()

            val orders = snapshot.documents.mapNotNull { doc ->
                runCatching {
                    doc.toObject(OrderDto::class.java)?.copy(id = doc.id)?.toDomain()
                }.getOrNull()
            }

            val totalRevenue = orders.sumOf { it.totalAmount }
            val qrisRevenue = orders
                .filter { it.paymentMethod == PaymentMethod.QRIS }
                .sumOf { it.totalAmount }
            val bankRevenue = orders
                .filter { it.paymentMethod == PaymentMethod.BANK_TRANSFER }
                .sumOf { it.totalAmount }
            val cashierRevenue = orders
                .filter { it.paymentMethod == PaymentMethod.CASHIER }
                .sumOf { it.totalAmount }

            SalesSummary(
                totalRevenue = totalRevenue,
                totalOrders = orders.size,
                qrisRevenue = qrisRevenue,
                bankTransferRevenue = bankRevenue,
                cashierRevenue = cashierRevenue,
                averageOrderValue = if (orders.isEmpty()) 0L else totalRevenue / orders.size
            )
        }
    }
}
