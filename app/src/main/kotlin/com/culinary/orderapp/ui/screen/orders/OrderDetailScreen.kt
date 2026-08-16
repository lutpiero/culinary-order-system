package com.culinary.orderapp.ui.screen.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.culinary.orderapp.domain.model.OrderStatus
import com.culinary.orderapp.domain.model.PaymentStatus
import com.culinary.orderapp.ui.component.StatusBadge
import com.culinary.orderapp.ui.theme.OnStatusReady
import com.culinary.orderapp.ui.theme.StatusReady
import com.culinary.orderapp.util.toRupiahFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    orderId: String,
    onBack: () -> Unit,
    viewModel: OrdersViewModel = hiltViewModel()
) {
    LaunchedEffect(orderId) {
        viewModel.loadOrderDetail(orderId)
    }

    val state by viewModel.detailState.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Detail Pesanan", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            state.errorMessage != null && state.order == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(state.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                }
            }
            state.order != null -> {
                val order = state.order!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val tableLabel = when (order.status) {
                                OrderStatus.SERVED -> "Checkout Meja ${order.tableNumber}"
                                else -> "Pesanan Meja ${order.tableNumber}"
                            }
                            Text(
                                text = tableLabel,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (order.customerName.isNotBlank()) {
                                Text(
                                    text = order.customerName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        StatusBadge(status = order.status, large = true)
                    }

                    Text(
                        text = "Pembayaran: ${order.paymentMethod.displayName}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = "Tipe Pesanan: ${order.orderType.displayName}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (order.paymentMethod != com.culinary.orderapp.domain.model.PaymentMethod.QRIS) {
                        OrderPaymentStatus(status = order.paymentStatus)
                    }

                    if (order.estimatedReadyMinutes > 0 &&
                        order.status !in listOf(OrderStatus.SERVED, OrderStatus.CANCELLED)
                    ) {
                        Text(
                            text = "Estimasi siap: ${order.estimatedReadyMinutes} menit",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Divider()

                    // Items
                    Text("Item Pesanan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    order.items.forEach { item ->
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${item.quantity}× ${item.menuItemName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = item.subtotal.toRupiahFormat(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            item.selectedToppings.forEach { topping ->
                                Text(
                                    text = "  + ${topping.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.notes.isNotBlank()) {
                                Text(
                                    text = "  Catatan: ${item.notes}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Divider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            order.totalAmount.toRupiahFormat(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (order.notes.isNotBlank()) {
                        Text(
                            text = "Catatan: ${order.notes}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action buttons
                    if (state.isUpdating) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val awaitingPayment = order.paymentMethod != com.culinary.orderapp.domain.model.PaymentMethod.QRIS
                            && order.paymentStatus != com.culinary.orderapp.domain.model.PaymentStatus.PAID

                        if (awaitingPayment) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Cancel,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Pesanan belum dapat diproses. Konfirmasi pembayaran terlebih dahulu melalui tab Kasir.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        } else {
                        when (order.status) {
                            OrderStatus.PENDING -> {
                                Button(
                                    onClick = { viewModel.acknowledgeOrder(order.id) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                                    Text("  Terima Pesanan (15 menit)")
                                }
                            }
                            OrderStatus.IN_QUEUE -> {
                                Button(
                                    onClick = { viewModel.advanceOrderStatus(order.id, order.status) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Mulai Menyiapkan")
                                }
                            }
                            OrderStatus.PREPARING -> {
                                Button(
                                    onClick = { viewModel.advanceOrderStatus(order.id, order.status) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Tandai Siap")
                                }
                            }
                            OrderStatus.READY -> {
                                Button(
                                    onClick = { viewModel.advanceOrderStatus(order.id, order.status) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Tandai Sudah Disajikan")
                                }
                            }
                            else -> {}
                        }
                        } // end else (payment confirmed)

                        if (order.status !in listOf(OrderStatus.SERVED, OrderStatus.CANCELLED)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showCancelDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Filled.Cancel, contentDescription = null)
                                Text("  Batalkan Pesanan")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Batalkan Pesanan") },
            text = { Text("Apakah Anda yakin ingin membatalkan pesanan ini?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        viewModel.cancelOrder(orderId)
                    }
                ) { Text("Ya, Batalkan", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Tidak") }
            }
        )
    }
}

@Composable
private fun OrderPaymentStatus(status: PaymentStatus) {
    val (container, content) = when (status) {
        PaymentStatus.PAID -> StatusReady to OnStatusReady
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = when (status) {
                PaymentStatus.PAID -> "Lunas"
                PaymentStatus.PENDING -> "Belum Dibayar"
                PaymentStatus.FAILED -> "Pembayaran Gagal"
                PaymentStatus.CANCELLED -> "Pembayaran Dibatalkan"
            },
            color = content,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
