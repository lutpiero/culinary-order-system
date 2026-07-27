package com.culinary.orderapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.culinary.orderapp.ui.screen.finance.FinanceScreen
import com.culinary.orderapp.ui.screen.menu.AddEditMenuItemScreen
import com.culinary.orderapp.ui.screen.menu.CategoryManagementScreen
import com.culinary.orderapp.ui.screen.menu.MenuManagementScreen
import com.culinary.orderapp.ui.screen.orders.OrderDetailScreen
import com.culinary.orderapp.ui.screen.orders.OrdersScreen
import com.culinary.orderapp.ui.screen.qrcode.QrCodeScreen

@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Screen.Orders.route,
        modifier = modifier
    ) {
        composable(Screen.Orders.route) {
            OrdersScreen(
                onOrderClick = { orderId ->
                    navController.navigate(Screen.OrderDetail.createRoute(orderId))
                }
            )
        }

        composable(
            route = Screen.OrderDetail.route,
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString("orderId") ?: return@composable
            OrderDetailScreen(
                orderId = orderId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Menu.route) {
            MenuManagementScreen(
                onAddItem = { navController.navigate(Screen.AddEditMenuItem.createRoute()) },
                onEditItem = { itemId ->
                    navController.navigate(Screen.AddEditMenuItem.createRoute(itemId))
                },
                onManageCategories = {
                    navController.navigate(Screen.CategoryManagement.route)
                }
            )
        }

        composable(
            route = Screen.AddEditMenuItem.route,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType })
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString("itemId") ?: "new"
            AddEditMenuItemScreen(
                itemId = if (itemId == "new") null else itemId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.CategoryManagement.route) {
            CategoryManagementScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Finance.route) {
            FinanceScreen()
        }

        composable(Screen.QrCode.route) {
            QrCodeScreen()
        }
    }
}
