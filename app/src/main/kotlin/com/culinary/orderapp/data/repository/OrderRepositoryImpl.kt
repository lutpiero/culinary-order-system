package com.culinary.orderapp.data.repository

import com.culinary.orderapp.data.model.OrderDto
import com.culinary.orderapp.domain.model.Order
import com.culinary.orderapp.domain.model.OrderStatus
import com.culinary.orderapp.domain.model.PaymentMethod
import com.culinary.orderapp.domain.model.SalesSummary
import com.culinary.orderapp.domain.repository.OrderRepository
import com.culinary.orderapp.util.Logger
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
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
                Logger.e("Error observing orders", error, TAG)
                close(error)
                return@addSnapshotListener
            }
            val orders = snapshot?.documents?.mapNotNull { doc ->
                runCatching {
                    doc.toObject(OrderDto::class.java)?.copy(id = doc.id)?.toDomain()
                }.onFailure { e ->
                    Logger.e("Error parsing order document ${doc.id}", e, TAG)
                }.getOrNull()
            } ?: emptyList()
            Logger.d("Loaded ${orders.size} orders", TAG)
            trySend(orders)
        }
        awaitClose { listener.remove() }
    }

    override suspend fun getOrderById(id: String): Order? {
        return try {
            Logger.d("Fetching order: $id", TAG)
            val order = ordersCollection.document(id).get().await()
                .toObject(OrderDto::class.java)?.copy(id = id)?.toDomain()
            if (order == null) {
                Logger.w("Order not found: $id", TAG)
            }
            order
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error fetching order $id", e, TAG)
            null
        } catch (e: Exception) {
            Logger.e("Unexpected error fetching order $id", e, TAG)
            null
        }
    }

    override suspend fun createOrder(order: Order): Result<Order> {
        return try {
            Logger.d("Creating order for table ${order.tableNumber}", TAG)
            
            // Use transaction to ensure atomic stock updates
            val result = firestore.runTransaction { transaction ->
                val menuItemsCollection = firestore.collection("menuItems")
                
                // Check and update stock for each item
                for (item in order.items) {
                    val menuItemRef = menuItemsCollection.document(item.menuItemId)
                    val menuItemSnapshot = transaction.get(menuItemRef)
                    
                    if (!menuItemSnapshot.exists()) {
                        throw Exception("Menu item ${item.menuItemId} not found")
                    }
                    
                    val currentStock = menuItemSnapshot.getLong("stock")?.toInt()
                    
                    // If stock is tracked (not null), check and decrease it
                    if (currentStock != null) {
                        if (currentStock < item.quantity) {
                            throw Exception("Insufficient stock for ${item.menuItemName}. Available: $currentStock, Requested: ${item.quantity}")
                        }
                        
                        val newStock = currentStock - item.quantity
                        transaction.update(menuItemRef, "stock", newStock)
                        Logger.d("Updated stock for ${item.menuItemName}: $currentStock -> $newStock", TAG)
                    }
                }
                
                // Create the order
                val dto = OrderDto.fromDomain(order)
                val docRef = ordersCollection.document()
                transaction.set(docRef, dto)
                docRef.id
            }.await()
            
            Logger.i("Order created successfully: $result", TAG)
            Result.success(order.copy(id = result))
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error creating order", e, TAG)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e("Error creating order: ${e.message}", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun updateOrderStatus(
        orderId: String,
        newStatus: OrderStatus,
        estimatedMinutes: Int?
    ): Result<Unit> {
        return try {
            Logger.d("Updating order $orderId status to $newStatus", TAG)
            val updates = mutableMapOf<String, Any>(
                "status" to newStatus.name,
                "updatedAt" to Timestamp.now()
            )
            estimatedMinutes?.let { updates["estimatedReadyMinutes"] = it }
            ordersCollection.document(orderId).update(updates).await()
            Logger.i("Order status updated successfully", TAG)
            Result.success(Unit)
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error updating order status", e, TAG)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e("Unexpected error updating order status", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun updateOrder(order: Order): Result<Order> {
        return try {
            Logger.d("Updating order: ${order.id}", TAG)
            val dto = OrderDto.fromDomain(order)
            ordersCollection.document(order.id).set(dto).await()
            Logger.i("Order updated successfully", TAG)
            Result.success(order)
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error updating order", e, TAG)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e("Unexpected error updating order", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun cancelOrder(orderId: String): Result<Unit> {
        Logger.d("Cancelling order: $orderId", TAG)
        return updateOrderStatus(orderId, OrderStatus.CANCELLED)
    }

    override suspend fun getSalesSummary(from: Date, to: Date): Result<SalesSummary> {
        return try {
            Logger.d("Fetching sales summary from $from to $to", TAG)
            val snapshot = ordersCollection
                .whereGreaterThanOrEqualTo("createdAt", Timestamp(from))
                .whereLessThanOrEqualTo("createdAt", Timestamp(to))
                .whereNotEqualTo("status", OrderStatus.CANCELLED.name)
                .get()
                .await()

            val orders = snapshot.documents.mapNotNull { doc ->
                runCatching {
                    doc.toObject(OrderDto::class.java)?.copy(id = doc.id)?.toDomain()
                }.onFailure { e ->
                    Logger.e("Error parsing order in summary: ${doc.id}", e, TAG)
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

            val summary = SalesSummary(
                totalRevenue = totalRevenue,
                totalOrders = orders.size,
                qrisRevenue = qrisRevenue,
                bankTransferRevenue = bankRevenue,
                cashierRevenue = cashierRevenue,
                averageOrderValue = if (orders.isEmpty()) 0L else totalRevenue / orders.size
            )
            Logger.i("Sales summary loaded: ${orders.size} orders, revenue: $totalRevenue", TAG)
            Result.success(summary)
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error fetching sales summary", e, TAG)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e("Unexpected error fetching sales summary", e, TAG)
            Result.failure(e)
        }
    }
    
    companion object {
        private const val TAG = "OrderRepository"
    }
}
