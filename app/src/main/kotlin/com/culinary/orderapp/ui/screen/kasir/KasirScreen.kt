package com.culinary.orderapp.ui.screen.kasir

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.culinary.orderapp.domain.model.Order
import com.culinary.orderapp.domain.model.PaymentMethod
import com.culinary.orderapp.ui.component.BusinessLogoIcon
import com.culinary.orderapp.util.toRupiahFormat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KasirScreen(viewModel: KasirViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    if (uiState.businessName.isNotBlank()) {
                        Text(
                            text = uiState.businessName,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    Text("Kasir", fontWeight = FontWeight.Bold)
                }
            },
            navigationIcon = {
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    BusinessLogoIcon(logoUrl = uiState.logoUrl, size = 36.dp)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = Color.White
            )
        )

        // Filter chips
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.filter == null,
                onClick = { viewModel.selectFilter(null) },
                label = { Text("Semua") }
            )
            FilterChip(
                selected = uiState.filter == PaymentMethod.CASHIER,
                onClick = { viewModel.selectFilter(PaymentMethod.CASHIER) },
                label = { Text("Tunai") }
            )
            FilterChip(
                selected = uiState.filter == PaymentMethod.BANK_TRANSFER,
                onClick = { viewModel.selectFilter(PaymentMethod.BANK_TRANSFER) },
                label = { Text("Transfer") }
            )
        }

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.errorMessage != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = uiState.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            viewModel.filteredOrders.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.PointOfSale,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tidak ada tagihan yang belum dibayar",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                    items(viewModel.filteredOrders, key = { it.id }) { order ->
                        KasirOrderCard(
                            order = order,
                            onClick = { viewModel.openPayDialog(order) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    uiState.payOrder?.let { order ->
        PayDialog(
            order = order,
            amountText = uiState.amountText,
            proofUri = uiState.proofUri,
            isPaying = uiState.isPaying,
            errorMessage = uiState.dialogError,
            onAmountChange = viewModel::updateAmountText,
            onProofTaken = viewModel::setProofUri,
            onRemoveProof = viewModel::removeProof,
            onConfirm = viewModel::confirmPayment,
            onDismiss = viewModel::closePayDialog
        )
    }
}

@Composable
private fun KasirOrderCard(order: Order, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Meja ${order.tableNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (order.customerName.isNotBlank()) {
                    Text(
                        text = order.customerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${order.items.sumOf { it.quantity }} item",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                PaymentMethodBadge(method = order.paymentMethod)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = order.totalAmount.toRupiahFormat(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PaymentMethodBadge(method: PaymentMethod) {
    val (container, content) = when (method) {
        PaymentMethod.CASHIER -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        PaymentMethod.BANK_TRANSFER -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        PaymentMethod.QRIS -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = method.displayName,
            color = content,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
private fun PayDialog(
    order: Order,
    amountText: String,
    proofUri: Uri?,
    isPaying: Boolean,
    errorMessage: String?,
    onAmountChange: (String) -> Unit,
    onProofTaken: (Uri) -> Unit,
    onRemoveProof: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var showPermissionDeniedNote by remember { mutableStateOf(false) }

    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingUri != null) {
            onProofTaken(pendingUri!!)
        } else {
            pendingUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
        pendingUri = null
    }

    fun launchCamera() {
        val file = createTempProofFile(context) ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: run { file.delete(); return }
        pendingUri = uri
        cameraLauncher.launch(uri)
    }

    val isCash = order.paymentMethod == PaymentMethod.CASHIER
    val received = amountText.toLongOrNull()
    val isAmountValid = isCash && received != null && received >= order.totalAmount
    val change = if (isCash && received != null && received >= order.totalAmount) {
        received - order.totalAmount
    } else null

    AlertDialog(
        onDismissRequest = { if (!isPaying) onDismiss() },
        title = {
            Column {
                Text("Terima Pembayaran", fontWeight = FontWeight.Bold)
                Text(
                    "Meja ${order.tableNumber} · ${order.paymentMethod.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Tagihan", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        order.totalAmount.toRupiahFormat(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Foto Bukti (Opsional)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Ambil foto bukti pembayaran dari kasir",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (proofUri != null) {
                        Box {
                            AsyncImage(
                                model = proofUri,
                                contentDescription = "Bukti pembayaran",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            IconButton(
                                onClick = { if (!isPaying) onRemoveProof() },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Hapus foto",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            OutlinedButton(
                                onClick = {
                                    showPermissionDeniedNote = false
                                    when {
                                        cameraPermission.status.isGranted -> launchCamera()
                                        cameraPermission.status.shouldShowRationale -> {
                                            showPermissionDeniedNote = true
                                            cameraPermission.launchPermissionRequest()
                                        }
                                        else -> cameraPermission.launchPermissionRequest()
                                    }
                                },
                                enabled = !isPaying
                            ) {
                                Icon(
                                    Icons.Filled.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ambil Foto")
                            }
                            if (showPermissionDeniedNote) {
                                Text(
                                    "Izin kamera diperlukan",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (isCash) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = onAmountChange,
                        label = { Text("Uang Diterima (Rp)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isPaying,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Kembalian", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            change?.toRupiahFormat() ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isPaying && (!isCash || isAmountValid)
            ) {
                if (isPaying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Memproses…")
                } else {
                    Text("Tandai Sudah Dibayar")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isPaying) { Text("Batal") }
        }
    )
}

private fun createTempProofFile(context: Context): File? {
    return try {
        val dir = File(context.cacheDir, "payment_proofs").apply { mkdirs() }
        File.createTempFile("proof_${UUID.randomUUID().toString().substring(0, 8)}_", ".jpg", dir)
    } catch (e: Exception) {
        null
    }
}