package com.culinary.orderapp.ui.screen.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.User
import com.culinary.orderapp.domain.usecase.SignInWithEmailPasswordUseCase
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
    private val signInWithEmailPassword: SignInWithEmailPasswordUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState(isInitialized = true))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

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

    fun handleEmailPasswordSignIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            
            Logger.d("Processing email/password sign-in", TAG)
            val result = signInWithEmailPassword(email, password)
            
            result.fold(
                onSuccess = { user ->
                    Logger.i("Email sign-in successful: ${user.email}", TAG)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        user = user,
                        errorMessage = null
                    )
                },
                onFailure = { error ->
                    Logger.e("Email sign-in failed", error, TAG)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Email/Password sign-in failed"
                    )
                }
            )
        }
    }

    fun handleGoogleSignInError(message: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = message
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    companion object {
        private const val TAG = "LoginViewModel"
    }
}
