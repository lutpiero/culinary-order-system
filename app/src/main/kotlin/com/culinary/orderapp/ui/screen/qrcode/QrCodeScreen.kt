package com.culinary.orderapp.ui.screen.qrcode

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.culinary.orderapp.ui.component.BusinessLogoIcon
import com.culinary.orderapp.util.generateQrCode

/**
 * Screen for generating table QR codes.
 * Each QR code encodes a URL that customers scan to open the web menu.
 *
 * The URL is built from the Web URL configured in Settings: <webUrl>?table=<tableNumber>
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeScreen(viewModel: QrCodeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var tableNumber by remember { mutableStateOf("") }
    var generatedQrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

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
                    Text("QR Code Meja", fontWeight = FontWeight.Bold)
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Generate QR Code untuk setiap meja. Pelanggan dapat scan QR untuk membuka menu pesanan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uiState.webUrl.isBlank()) {
                Text(
                    text = "Web URL belum diatur. Silakan atur Web URL di halaman Pengaturan terlebih dahulu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = tableNumber,
                    onValueChange = { tableNumber = it },
                    label = { Text("Nomor Meja") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (tableNumber.isNotBlank() && uiState.webUrl.isNotBlank()) {
                            val url = "${uiState.webUrl}?table=${tableNumber.trim()}"
                            generatedQrBitmap = generateQrCode(url)
                        }
                    },
                    enabled = tableNumber.isNotBlank() && uiState.webUrl.isNotBlank()
                ) {
                    Text("Generate")
                }
            }

            generatedQrBitmap?.let { bitmap ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (uiState.businessName.isNotBlank()) {
                            Text(
                                text = uiState.businessName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Meja $tableNumber",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code Meja $tableNumber",
                            modifier = Modifier.size(240.dp)
                        )
                        Text(
                            text = "${uiState.webUrl}?table=$tableNumber",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
