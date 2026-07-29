package com.culinary.orderapp.ui.screen.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.User
import com.culinary.orderapp.domain.usecase.InitializeDefaultSettingsUseCase
import com.culinary.orderapp.domain.usecase.InitializeSystemRolesUseCase
import com.culinary.orderapp.domain.usecase.SignInWithGoogleUseCase
import com.culinary.orderapp.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val user: User? = null,
    val errorMessage: String? = null,
    val isInitialized: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val signInWithGoogle: SignInWithGoogleUseCase,
    private val initializeSystemRoles: InitializeSystemRolesUseCase,
    private val initializeDefaultSettings: InitializeDefaultSettingsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        initializeApp()
    }

    private fun initializeApp() {
        viewModelScope.launch {
            try {
                Logger.d("Initializing app data", TAG)
                
                // Initialize system roles
                initializeSystemRoles().onFailure { e ->
                    Logger.e("Failed to initialize system roles", e, TAG)
                }
                
                // Initialize default settings
                initializeDefaultSettings().onFailure { e ->
                    Logger.e("Failed to initialize default settings", e, TAG)
                }
                
                _uiState.value = _uiState.value.copy(isInitialized = true)
                Logger.i("App initialization completed", TAG)
            } catch (e: Exception) {
                Logger.e("Error during app initialization", e, TAG)
                _uiState.value = _uiState.value.copy(
                    isInitialized = true,
                    errorMessage = "Initialization error: ${e.message}"
                )
            }
        }
    }

    fun handleGoogleSignIn(idToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            
            Logger.d("Processing Google Sign-In", TAG)
            val result = signInWithGoogle(idToken)
            
            result.fold(
                onSuccess = { user ->
                    Logger.i("Sign-in successful: ${user.email}", TAG)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        user = user,
                        errorMessage = null
                    )
                },
                onFailure = { error ->
                    Logger.e("Sign-in failed", error, TAG)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Sign-in failed"
                    )
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    companion object {
        private const val TAG = "LoginViewModel"
    }
}
