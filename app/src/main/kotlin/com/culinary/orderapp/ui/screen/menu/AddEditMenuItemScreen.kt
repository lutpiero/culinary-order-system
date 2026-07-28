package com.culinary.orderapp.ui.screen.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditMenuItemScreen(
    itemId: String?,
    onBack: () -> Unit,
    viewModel: MenuViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val formState by viewModel.formState.collectAsState()

    LaunchedEffect(itemId, uiState.menuItems) {
        viewModel.loadItemForEdit(itemId, uiState.menuItems)
    }

    val item = formState.item
    var categoryExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (itemId == null) "Tambah Menu" else "Edit Menu",
                        fontWeight = FontWeight.Bold
                    )
                },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = item.name,
                onValueChange = { viewModel.updateFormItem(item.copy(name = it)) },
                label = { Text("Nama Menu *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = item.description,
                onValueChange = { viewModel.updateFormItem(item.copy(description = it)) },
                label = { Text("Deskripsi") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )

            OutlinedTextField(
                value = if (item.price == 0L) "" else item.price.toString(),
                onValueChange = { value ->
                    viewModel.updateFormItem(item.copy(price = value.toLongOrNull() ?: 0L))
                },
                label = { Text("Harga (Rp) *") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            // Category dropdown
            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it }
            ) {
                OutlinedTextField(
                    value = item.categoryName.ifBlank { "Pilih Kategori" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Kategori") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    uiState.categories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category.name) },
                            onClick = {
                                viewModel.updateFormItem(
                                    item.copy(
                                        categoryId = category.id,
                                        categoryName = category.name
                                    )
                                )
                                categoryExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = item.imageUrl,
                onValueChange = { viewModel.updateFormItem(item.copy(imageUrl = it)) },
                label = { Text("URL Gambar") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = if (item.preparationTimeMinutes == 0) "" else item.preparationTimeMinutes.toString(),
                onValueChange = { value ->
                    viewModel.updateFormItem(
                        item.copy(preparationTimeMinutes = value.toIntOrNull() ?: 10)
                    )
                },
                label = { Text("Waktu Persiapan (menit)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            OutlinedTextField(
                value = item.stock?.toString() ?: "",
                onValueChange = { value ->
                    viewModel.updateFormItem(
                        item.copy(stock = if (value.isBlank()) null else value.toIntOrNull())
                    )
                },
                label = { Text("Stok (kosongkan untuk unlimited)") },
                placeholder = { Text("Unlimited") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = item.isAvailable,
                    onCheckedChange = { viewModel.updateFormItem(item.copy(isAvailable = it)) }
                )
                Text("Tersedia")
            }

            if (formState.errorMessage != null) {
                Text(
                    text = formState.errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (formState.isSaving) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = {
                        viewModel.saveItem()
                        if (formState.errorMessage == null) onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = item.name.isNotBlank() && item.price > 0
                ) {
                    Text("Simpan Menu")
                }
            }
        }
    }
}
