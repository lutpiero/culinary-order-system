package com.culinary.orderapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.culinary.orderapp.domain.state.CurrentUserState
import com.culinary.orderapp.domain.usecase.GetCurrentUserUseCase
import com.culinary.orderapp.domain.usecase.GetRoleByIdUseCase
import com.culinary.orderapp.domain.usecase.ObserveAuthStateUseCase
import com.culinary.orderapp.ui.navigation.AppNavHost
import com.culinary.orderapp.ui.navigation.Screen
import com.culinary.orderapp.ui.navigation.bottomNavItems
import com.culinary.orderapp.ui.screen.auth.LoginScreen
import com.culinary.orderapp.ui.theme.CulinaryOrderTheme
import com.culinary.orderapp.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var observeAuthState: ObserveAuthStateUseCase

    @Inject
    lateinit var getCurrentUser: GetCurrentUserUseCase

    @Inject
    lateinit var getRoleById: GetRoleByIdUseCase

    @Inject
    lateinit var currentUserState: CurrentUserState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CulinaryOrderTheme {
                MainContent(
                    observeAuthState = observeAuthState,
                    getCurrentUser = getCurrentUser,
                    getRoleById = getRoleById,
                    currentUserState = currentUserState
                )
            }
        }
    }
}

@Composable
private fun MainContent(
    observeAuthState: ObserveAuthStateUseCase,
    getCurrentUser: GetCurrentUserUseCase,
    getRoleById: GetRoleByIdUseCase,
    currentUserState: CurrentUserState
) {
    val authState by observeAuthState().collectAsState(initial = null)

    LaunchedEffect(authState) {
        if (authState != null) {
            val userResult = getCurrentUser()
            userResult.onSuccess { user ->
                if (user != null) {
                    val role = getRoleById(user.roleId).getOrNull()
                    currentUserState.update(user, role)
                }
            }
            userResult.onFailure { e ->
                Logger.e("Failed to load current user", e, "MainActivity")
            }
        } else {
            currentUserState.clear()
        }
    }

    if (authState == null) {
        LoginScreen(
            onLoginSuccess = {
                // Auth state observer will trigger navigation automatically
            }
        )
    } else {
        AuthenticatedApp(currentUserState = currentUserState)
    }
}

@Composable
private fun AuthenticatedApp(currentUserState: CurrentUserState) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val topLevelRoutes = setOf(
        Screen.Orders.route,
        Screen.Menu.route,
        Screen.Finance.route,
        Screen.QrCode.route,
        Screen.Settings.route
    )
    val showBottomBar = currentRoute in topLevelRoutes

    val currentUser by currentUserState.currentUser.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val hasAccess = when (item.screen) {
                            is Screen.Finance -> currentUserState.hasPermission(
                                com.culinary.orderapp.domain.model.Permission.VIEW_FINANCE
                            )
                            is Screen.Menu -> currentUserState.hasPermission(
                                com.culinary.orderapp.domain.model.Permission.VIEW_MENU
                            )
                            is Screen.Orders -> currentUserState.hasPermission(
                                com.culinary.orderapp.domain.model.Permission.VIEW_ORDERS
                            )
                            is Screen.QrCode -> currentUserState.hasPermission(
                                com.culinary.orderapp.domain.model.Permission.VIEW_QR_CODE
                            )
                            is Screen.Settings -> currentUserState.hasPermission(
                                com.culinary.orderapp.domain.model.Permission.VIEW_SETTINGS
                            )
                            else -> true
                        }

                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentRoute == item.screen.route,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            enabled = hasAccess,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            currentUserState = currentUserState,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
