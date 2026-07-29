package com.culinary.orderapp.ui.screen.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.Role
import com.culinary.orderapp.domain.model.User
import com.culinary.orderapp.domain.usecase.CreateUserUseCase
import com.culinary.orderapp.domain.usecase.CreateUserWithEmailPasswordUseCase
import com.culinary.orderapp.domain.usecase.GetUserByIdUseCase
import com.culinary.orderapp.domain.usecase.ObserveRolesUseCase
import com.culinary.orderapp.domain.usecase.UpdateUserUseCase
import com.culinary.orderapp.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AddEditUserUiState(
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val createWithPassword: Boolean = false,
    val selectedRoleId: String = "",
    val selectedRoleName: String = "",
    val isActive: Boolean = true,
    val roles: List<Role> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isEditMode: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false
)

@HiltViewModel
class AddEditUserViewModel @Inject constructor(
    private val observeRoles: ObserveRolesUseCase,
    private val getUserById: GetUserByIdUseCase,
    private val createUser: CreateUserUseCase,
    private val updateUser: UpdateUserUseCase,
    private val createUserWithEmailPassword: CreateUserWithEmailPasswordUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditUserUiState())
    val uiState: StateFlow<AddEditUserUiState> = _uiState.asStateFlow()

    private var editingUserId: String? = null

    fun initialize(userId: String?) {
        viewModelScope.launch {
            try {
                val roles = observeRoles().first()

                if (userId != null && userId != "new") {
                    editingUserId = userId
                    val user = getUserById(userId).getOrNull()
                    if (user != null) {
                        _uiState.value = _uiState.value.copy(
                            displayName = user.displayName,
                            email = user.email,
                            selectedRoleId = user.roleId,
                            selectedRoleName = user.roleName,
                            isActive = user.isActive,
                            isEditMode = true,
                            roles = roles,
                            isLoading = false
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            roles = roles,
                            isLoading = false,
                            errorMessage = "User not found"
                        )
                    }
                } else {
                    val defaultRole = roles.firstOrNull()
                    _uiState.value = _uiState.value.copy(
                        roles = roles,
                        selectedRoleId = defaultRole?.id ?: "",
                        selectedRoleName = defaultRole?.displayName ?: "",
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Logger.e("Error initializing AddEditUser", e, TAG)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message)
            }
        }
    }

    fun updateDisplayName(value: String) { _uiState.value = _uiState.value.copy(displayName = value) }
    fun updateEmail(value: String) { _uiState.value = _uiState.value.copy(email = value) }
    fun updatePassword(value: String) { _uiState.value = _uiState.value.copy(password = value) }
    fun updateConfirmPassword(value: String) { _uiState.value = _uiState.value.copy(confirmPassword = value) }
    fun updateCreateWithPassword(value: Boolean) { _uiState.value = _uiState.value.copy(createWithPassword = value) }

    fun updateRole(roleId: String) {
        val role = _uiState.value.roles.find { it.id == roleId }
        _uiState.value = _uiState.value.copy(
            selectedRoleId = roleId,
            selectedRoleName = role?.displayName ?: ""
        )
    }

    fun updateIsActive(value: Boolean) { _uiState.value = _uiState.value.copy(isActive = value) }

    fun save() {
        val state = _uiState.value
        if (state.displayName.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Nama harus diisi")
            return
        }
        if (state.email.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Email harus diisi")
            return
        }
        if (state.selectedRoleId.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Role harus dipilih")
            return
        }
        if (!state.isEditMode && state.createWithPassword) {
            if (state.password.isBlank()) {
                _uiState.value = state.copy(errorMessage = "Password harus diisi")
                return
            }
            if (state.password != state.confirmPassword) {
                _uiState.value = state.copy(errorMessage = "Password tidak cocok")
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            try {
                if (state.isEditMode && editingUserId != null) {
                    val existingUser = getUserById(editingUserId!!).getOrNull()
                    if (existingUser != null) {
                        val updatedUser = existingUser.copy(
                            displayName = state.displayName,
                            email = state.email,
                            roleId = state.selectedRoleId,
                            roleName = state.selectedRoleName,
                            isActive = state.isActive
                        )
                        updateUser(updatedUser).fold(
                            onSuccess = { _uiState.value = _uiState.value.copy(isSaving = false, saved = true) },
                            onFailure = { e ->
                                _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message)
                            }
                        )
                    }
                } else if (state.createWithPassword) {
                    createUserWithEmailPassword(
                        email = state.email,
                        password = state.password,
                        displayName = state.displayName,
                        roleId = state.selectedRoleId,
                        roleName = state.selectedRoleName
                    ).fold(
                        onSuccess = { _uiState.value = _uiState.value.copy(isSaving = false, saved = true) },
                        onFailure = { e ->
                            _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message)
                        }
                    )
                } else {
                    val user = User(
                        id = UUID.randomUUID().toString(),
                        email = state.email,
                        displayName = state.displayName,
                        photoUrl = null,
                        roleId = state.selectedRoleId,
                        roleName = state.selectedRoleName,
                        isActive = true
                    )
                    createUser(user).fold(
                        onSuccess = { _uiState.value = _uiState.value.copy(isSaving = false, saved = true) },
                        onFailure = { e ->
                            _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message)
                        }
                    )
                }
            } catch (e: Exception) {
                Logger.e("Error saving user", e, TAG)
                _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message ?: "Gagal menyimpan")
            }
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(errorMessage = null) }

    companion object {
        private const val TAG = "AddEditUserViewModel"
    }
}
