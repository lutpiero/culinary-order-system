package com.culinary.orderapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.culinary.orderapp.domain.model.Permission
import com.culinary.orderapp.domain.state.CurrentUserState
import com.culinary.orderapp.ui.component.RequirePermission
import com.culinary.orderapp.ui.screen.finance.FinanceScreen
import com.culinary.orderapp.ui.screen.menu.AddEditMenuItemScreen
import com.culinary.orderapp.ui.screen.menu.AddEditToppingGroupScreen
import com.culinary.orderapp.ui.screen.menu.CategoryManagementScreen
import com.culinary.orderapp.ui.screen.menu.MenuManagementScreen
import com.culinary.orderapp.ui.screen.menu.ToppingManagementScreen
import com.culinary.orderapp.ui.screen.orders.OrderDetailScreen
import com.culinary.orderapp.ui.screen.orders.OrdersScreen
import com.culinary.orderapp.ui.screen.qrcode.QrCodeScreen
import com.culinary.orderapp.ui.screen.settings.SettingsScreen
import com.culinary.orderapp.ui.screen.users.UserManagementScreen
import com.culinary.orderapp.ui.screen.users.AddEditUserScreen
import com.culinary.orderapp.ui.screen.roles.RoleManagementScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    currentUserState: CurrentUserState,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Orders.route,
        modifier = modifier
    ) {
        composable(Screen.Orders.route) {
            RequirePermission(Permission.VIEW_ORDERS, currentUserState) {
                OrdersScreen(
                    onOrderClick = { orderId ->
                        navController.navigate(Screen.OrderDetail.createRoute(orderId))
                    }
                )
            }
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
            RequirePermission(Permission.VIEW_MENU, currentUserState) {
                MenuManagementScreen(
                    onAddItem = { navController.navigate(Screen.AddEditMenuItem.createRoute()) },
                    onEditItem = { itemId ->
                        navController.navigate(Screen.AddEditMenuItem.createRoute(itemId))
                    },
                    onManageCategories = {
                        navController.navigate(Screen.CategoryManagement.route)
                    },
                    onManageToppings = { menuItemId, menuItemName ->
                        navController.navigate(Screen.ToppingManagement.createRoute(menuItemId, menuItemName))
                    }
                )
            }
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

        composable(
            route = Screen.ToppingManagement.route,
            arguments = listOf(
                navArgument("menuItemId") { type = NavType.StringType },
                navArgument("menuItemName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val menuItemId = backStackEntry.arguments?.getString("menuItemId") ?: return@composable
            val menuItemName = backStackEntry.arguments?.getString("menuItemName") ?: ""
            ToppingManagementScreen(
                menuItemId = menuItemId,
                menuItemName = menuItemName,
                onBack = { navController.popBackStack() },
                onEditToppingGroup = { toppingGroupId ->
                    navController.navigate(Screen.AddEditToppingGroup.createRoute(menuItemId, toppingGroupId))
                }
            )
        }

        composable(
            route = Screen.AddEditToppingGroup.route,
            arguments = listOf(
                navArgument("menuItemId") { type = NavType.StringType },
                navArgument("toppingGroupId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val menuItemId = backStackEntry.arguments?.getString("menuItemId") ?: return@composable
            val toppingGroupId = backStackEntry.arguments?.getString("toppingGroupId")
            AddEditToppingGroupScreen(
                menuItemId = menuItemId,
                toppingGroupId = toppingGroupId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Finance.route) {
            RequirePermission(Permission.VIEW_FINANCE, currentUserState) {
                FinanceScreen()
            }
        }

        composable(Screen.QrCode.route) {
            RequirePermission(Permission.VIEW_QR_CODE, currentUserState) {
                QrCodeScreen()
            }
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                currentUserState = currentUserState,
                onNavigateToUserManagement = {
                    navController.navigate(Screen.UserManagement.route)
                },
                onNavigateToRoleManagement = {
                    navController.navigate(Screen.RoleManagement.route)
                }
            )
        }

        composable(Screen.UserManagement.route) {
            UserManagementScreen(
                onAddUser = {
                    navController.navigate(Screen.AddEditUser.createRoute())
                },
                onEditUser = { userId ->
                    navController.navigate(Screen.AddEditUser.createRoute(userId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AddEditUser.route,
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: "new"
            AddEditUserScreen(
                userId = if (userId == "new") null else userId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.RoleManagement.route) {
            RoleManagementScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
