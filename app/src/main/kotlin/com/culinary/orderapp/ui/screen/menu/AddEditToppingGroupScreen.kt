package com.culinary.orderapp.ui.screen.menu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.culinary.orderapp.domain.model.Topping
import com.culinary.orderapp.domain.model.ToppingGroup
import com.culinary.orderapp.domain.model.ToppingType
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditToppingGroupScreen(
    menuItemId: String,
    toppingGroupId: String?,
    onBack: () -> Unit,
    viewModel: MenuViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val menuItem = uiState.menuItems.find { it.id == menuItemId }
    
    val isNew = toppingGroupId?.startsWith("new_") == true
    val existingGroup = if (!isNew) {
        menuItem?.toppingGroups?.find { it.id == toppingGroupId }
    } else null

    var groupName by remember(toppingGroupId) { mutableStateOf(existingGroup?.name ?: "") }
    var isRequired by remember(toppingGroupId) { mutableStateOf(existingGroup?.isRequired ?: false) }
    var toppingType by remember(toppingGroupId) { mutableStateOf(existingGroup?.type ?: ToppingType.SINGLE_SELECT) }
    var toppings by remember(toppingGroupId) { mutableStateOf(existingGroup?.toppings ?: emptyList()) }
    var showAddToppingDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNew) "Tambah Grup Topping" else "Edit Grup Topping",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group Name
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Nama Grup *") },
                placeholder = { Text("Contoh: Level Pedas, Extra Topping") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Type Selection
            Text(
                "Tipe Pilihan *",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = toppingType == ToppingType.SINGLE_SELECT,
                    onClick = { toppingType = ToppingType.SINGLE_SELECT },
                    label = { Text("Pilih Satu") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = toppingType == ToppingType.MULTI_SELECT,
                    onClick = { toppingType = ToppingType.MULTI_SELECT },
                    label = { Text("Pilih Banyak") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Required Checkbox
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = isRequired,
                    onCheckedChange = { isRequired = it }
                )
                Text("Wajib dipilih pelanggan")
            }

            Divider()

            // Toppings Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Pilihan Topping (${toppings.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { showAddToppingDialog = true }) {
                    Icon(Icons.Default.Add, "Tambah Topping")
                }
            }

            if (toppings.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Belum ada topping. Tap + untuk menambah",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                toppings.forEachIndexed { index, topping ->
                    ToppingItemCard(
                        topping = topping,
                        onDelete = {
                            toppings = toppings.filterIndexed { i, _ -> i != index }
                        }
                    )
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (groupName.isBlank()) {
                        errorMessage = "Nama grup harus diisi"
                        return@Button
                    }
                    if (toppings.isEmpty()) {
                        errorMessage = "Minimal harus ada 1 topping"
                        return@Button
                    }

                    val newGroup = ToppingGroup(
                        id = if (isNew) UUID.randomUUID().toString() else (toppingGroupId ?: ""),
                        name = groupName,
                        type = toppingType,
                        isRequired = isRequired,
                        toppings = toppings
                    )

                    viewModel.saveToppingGroup(menuItemId, newGroup, isNew)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = groupName.isNotBlank() && toppings.isNotEmpty()
            ) {
                Text("Simpan Grup Topping")
            }
        }
    }

    // Add Topping Dialog
    if (showAddToppingDialog) {
        AddToppingDialog(
            onDismiss = { showAddToppingDialog = false },
            onAdd = { topping ->
                toppings = toppings + topping
                showAddToppingDialog = false
            }
        )
    }
}

@Composable
private fun ToppingItemCard(
    topping: Topping,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = topping.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (topping.additionalPrice > 0) {
                    Text(
                        text = "+Rp ${topping.additionalPrice}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Hapus",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddToppingDialog(
    onDismiss: () -> Unit,
    onAdd: (Topping) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah Topping") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Topping *") },
                    placeholder = { Text("Contoh: Extra Keju, Pedas Sedang") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { char -> char.isDigit() } },
                    label = { Text("Harga Tambahan (Rp)") },
                    placeholder = { Text("0 jika gratis") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onAdd(
                            Topping(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                additionalPrice = price.toLongOrNull() ?: 0L,
                                type = ToppingType.SINGLE_SELECT,
                                isRequired = false,
                                isAvailable = true
                            )
                        )
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("Tambah")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}
