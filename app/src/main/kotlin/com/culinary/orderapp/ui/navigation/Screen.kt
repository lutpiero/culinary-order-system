package com.culinary.orderapp.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Sealed class representing all navigation destinations in the app.
 */
sealed class Screen(val route: String) {
    // Bottom nav destinations
    data object Orders : Screen("orders")
    data object Menu : Screen("menu")
    data object Finance : Screen("finance")
    data object QrCode : Screen("qrcode")

    // Detail/sub screens
    data object OrderDetail : Screen("order_detail/{orderId}") {
        fun createRoute(orderId: String) = "order_detail/$orderId"
    }
    data object AddEditMenuItem : Screen("menu_item/{itemId}") {
        fun createRoute(itemId: String = "new") = "menu_item/$itemId"
    }
    data object CategoryManagement : Screen("categories")
}

/**
 * Bottom navigation items shown in the main Seller app.
 */
data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Orders, "Pesanan", Icons.Filled.Receipt),
    BottomNavItem(Screen.Menu, "Menu", Icons.Filled.MenuBook),
    BottomNavItem(Screen.Finance, "Keuangan", Icons.Filled.AttachMoney),
    BottomNavItem(Screen.QrCode, "QR Meja", Icons.Filled.QrCode)
)
