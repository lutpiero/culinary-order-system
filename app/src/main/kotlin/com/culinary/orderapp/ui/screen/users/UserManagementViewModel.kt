package com.culinary.orderapp.ui.screen.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.User
import com.culinary.orderapp.domain.usecase.DeleteUserUseCase
import com.culinary.orderapp.domain.usecase.ObserveUsersUseCase
import com.culinary.orderapp.domain.usecase.ToggleUserStatusUseCase
import com.culinary.orderapp.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserManagementUiState(
    val users: List<User> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

@HiltViewModel
class UserManagementViewModel @Inject constructor(
    private val observeUsers: ObserveUsersUseCase,
    private val toggleUserStatus: ToggleUserStatusUseCase,
    private val deleteUser: DeleteUserUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserManagementUiState())
    val uiState: StateFlow<UserManagementUiState> = _uiState.asStateFlow()

    init {
        loadUsers()
    }

    private fun loadUsers() {
        viewModelScope.launch {
            observeUsers().collect { users ->
                _uiState.value = UserManagementUiState(
                    users = users,
                    isLoading = false
                )
            }
        }
    }

    fun toggleStatus(userId: String, isActive: Boolean) {
        viewModelScope.launch {
            toggleUserStatus(userId, isActive).onFailure { e ->
                Logger.e("Failed to toggle user status", e, TAG)
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Gagal mengubah status")
            }
        }
    }

    fun deleteUser(userId: String) {
        viewModelScope.launch {
            deleteUser(userId).onFailure { e ->
                Logger.e("Failed to delete user", e, TAG)
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Gagal menghapus pengguna")
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    companion object {
        private const val TAG = "UserManagementViewModel"
    }
}
