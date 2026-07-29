package com.culinary.orderapp.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.culinary.orderapp.domain.model.Permission
import com.culinary.orderapp.domain.state.CurrentUserState

@Composable
fun RequirePermission(
    permission: Permission,
    currentUserState: CurrentUserState,
    noPermissionContent: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Anda tidak memiliki akses ke halaman ini",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    },
    content: @Composable () -> Unit
) {
    val currentUser by currentUserState.currentUser.collectAsState()
    val currentRole by currentUserState.currentRole.collectAsState()

    if (currentUser == null) {
        noPermissionContent()
    } else if (currentUserState.hasPermission(permission)) {
        content()
    } else {
        noPermissionContent()
    }
}
