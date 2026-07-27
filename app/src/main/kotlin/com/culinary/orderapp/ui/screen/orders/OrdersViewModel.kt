package com.culinary.orderapp.ui.screen.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.Order
import com.culinary.orderapp.domain.model.OrderStatus
import com.culinary.orderapp.domain.usecase.CancelOrderUseCase
import com.culinary.orderapp.domain.usecase.GetOrderByIdUseCase
import com.culinary.orderapp.domain.usecase.ObserveOrdersUseCase
import com.culinary.orderapp.domain.usecase.UpdateOrderStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrdersUiState(
    val orders: List<Order> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedTab: Int = 0
)

data class OrderDetailUiState(
    val order: Order? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isUpdating: Boolean = false
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val observeOrders: ObserveOrdersUseCase,
    private val updateOrderStatus: UpdateOrderStatusUseCase,
    private val cancelOrder: CancelOrderUseCase,
    private val getOrderById: GetOrderByIdUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrdersUiState(isLoading = true))
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    private val _detailState = MutableStateFlow(OrderDetailUiState())
    val detailState: StateFlow<OrderDetailUiState> = _detailState.asStateFlow()

    init {
        loadOrders()
    }

    fun loadOrders(status: OrderStatus? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            observeOrders(status)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Gagal memuat pesanan"
                    )
                }
                .collect { orders ->
                    _uiState.value = _uiState.value.copy(
                        orders = orders,
                        isLoading = false
                    )
                }
        }
    }

    fun loadOrderDetail(orderId: String) {
        viewModelScope.launch {
            _detailState.value = OrderDetailUiState(isLoading = true)
            val order = getOrderById(orderId)
            _detailState.value = if (order != null) {
                OrderDetailUiState(order = order, isLoading = false)
            } else {
                OrderDetailUiState(isLoading = false, errorMessage = "Pesanan tidak ditemukan")
            }
        }
    }

    fun acknowledgeOrder(orderId: String, estimatedMinutes: Int = 15) {
        viewModelScope.launch {
            _detailState.value = _detailState.value.copy(isUpdating = true)
            val result = updateOrderStatus(orderId, OrderStatus.IN_QUEUE, estimatedMinutes)
            _detailState.value = _detailState.value.copy(
                isUpdating = false,
                errorMessage = result.exceptionOrNull()?.message
            )
            if (result.isSuccess) loadOrderDetail(orderId)
        }
    }

    fun advanceOrderStatus(orderId: String, currentStatus: OrderStatus) {
        val nextStatus = when (currentStatus) {
            OrderStatus.IN_QUEUE -> OrderStatus.PREPARING
            OrderStatus.PREPARING -> OrderStatus.READY
            OrderStatus.READY -> OrderStatus.SERVED
            else -> return
        }
        viewModelScope.launch {
            _detailState.value = _detailState.value.copy(isUpdating = true)
            val result = updateOrderStatus(orderId, nextStatus)
            _detailState.value = _detailState.value.copy(
                isUpdating = false,
                errorMessage = result.exceptionOrNull()?.message
            )
            if (result.isSuccess) loadOrderDetail(orderId)
        }
    }

    fun cancelOrder(orderId: String) {
        viewModelScope.launch {
            _detailState.value = _detailState.value.copy(isUpdating = true)
            val result = cancelOrder.invoke(orderId)
            _detailState.value = _detailState.value.copy(
                isUpdating = false,
                errorMessage = result.exceptionOrNull()?.message
            )
            if (result.isSuccess) loadOrderDetail(orderId)
        }
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = index)
        val status = when (index) {
            0 -> null
            1 -> OrderStatus.IN_QUEUE
            2 -> OrderStatus.PREPARING
            3 -> OrderStatus.READY
            else -> null
        }
        loadOrders(status)
    }
}
