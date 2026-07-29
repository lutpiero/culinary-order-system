package com.culinary.orderapp.ui.screen.roles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.Permission
import com.culinary.orderapp.domain.model.Role
import com.culinary.orderapp.domain.usecase.CreateRoleUseCase
import com.culinary.orderapp.domain.usecase.DeleteRoleUseCase
import com.culinary.orderapp.domain.usecase.ObserveRolesUseCase
import com.culinary.orderapp.domain.usecase.UpdateRoleUseCase
import com.culinary.orderapp.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID
import javax.inject.Inject

data class RoleManagementUiState(
    val roles: List<Role> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val showDialog: Boolean = false,
    val editingRole: Role? = null,
    val dialogName: String = "",
    val dialogDescription: String = "",
    val dialogPermissions: Set<Permission> = emptySet(),
    val isSaving: Boolean = false
)

@HiltViewModel
class RoleManagementViewModel @Inject constructor(
    private val observeRoles: ObserveRolesUseCase,
    private val createRole: CreateRoleUseCase,
    private val updateRole: UpdateRoleUseCase,
    private val deleteRole: DeleteRoleUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoleManagementUiState())
    val uiState: StateFlow<RoleManagementUiState> = _uiState.asStateFlow()

    init {
        observeRoles()
    }

    private fun observeRoles() {
        viewModelScope.launch {
            observeRoles().collect { roles ->
                _uiState.value = _uiState.value.copy(roles = roles, isLoading = false)
            }
        }
    }

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(
            showDialog = true,
            editingRole = null,
            dialogName = "",
            dialogDescription = "",
            dialogPermissions = emptySet()
        )
    }

    fun showEditDialog(role: Role) {
        _uiState.value = _uiState.value.copy(
            showDialog = true,
            editingRole = role,
            dialogName = role.name,
            dialogDescription = role.description,
            dialogPermissions = role.permissions.toSet()
        )
    }

    fun hideDialog() {
        _uiState.value = _uiState.value.copy(showDialog = false, editingRole = null)
    }

    fun updateDialogName(value: String) { _uiState.value = _uiState.value.copy(dialogName = value) }
    fun updateDialogDescription(value: String) { _uiState.value = _uiState.value.copy(dialogDescription = value) }

    fun toggleDialogPermission(permission: Permission) {
        val current = _uiState.value.dialogPermissions
        _uiState.value = _uiState.value.copy(
            dialogPermissions = if (permission in current) current - permission else current + permission
        )
    }

    fun saveRole() {
        val state = _uiState.value
        if (state.dialogName.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Nama role harus diisi")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            try {
                val existingRole = state.editingRole
                if (existingRole != null) {
                    val updated = existingRole.copy(
                        name = state.dialogName.trim().lowercase().replace(" ", "_"),
                        displayName = state.dialogName.trim(),
                        description = state.dialogDescription.trim(),
                        permissions = state.dialogPermissions.toList(),
                        updatedAt = Date()
                    )
                    updateRole(updated).fold(
                        onSuccess = {
                            _uiState.value = _uiState.value.copy(
                                isSaving = false, showDialog = false,
                                successMessage = "Role berhasil diperbarui"
                            )
                        },
                        onFailure = { e ->
                            _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message)
                        }
                    )
                } else {
                    val newRole = Role(
                        id = UUID.randomUUID().toString(),
                        name = state.dialogName.trim().lowercase().replace(" ", "_"),
                        displayName = state.dialogName.trim(),
                        description = state.dialogDescription.trim(),
                        permissions = state.dialogPermissions.toList(),
                        isSystemRole = false,
                        createdAt = Date(),
                        updatedAt = Date()
                    )
                    createRole(newRole).fold(
                        onSuccess = {
                            _uiState.value = _uiState.value.copy(
                                isSaving = false, showDialog = false,
                                successMessage = "Role berhasil dibuat"
                            )
                        },
                        onFailure = { e ->
                            _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message)
                        }
                    )
                }
            } catch (e: Exception) {
                Logger.e("Error saving role", e, TAG)
                _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message)
            }
        }
    }

    fun deleteRole(roleId: String) {
        viewModelScope.launch {
            deleteRole(roleId).fold(
                onSuccess = { _uiState.value = _uiState.value.copy(successMessage = "Role berhasil dihapus") },
                onFailure = { e -> _uiState.value = _uiState.value.copy(errorMessage = e.message) }
            )
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    companion object {
        private const val TAG = "RoleManagementViewModel"
    }
}
