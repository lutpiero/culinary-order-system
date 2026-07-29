package com.culinary.orderapp.domain.state

import com.culinary.orderapp.domain.model.Permission
import com.culinary.orderapp.domain.model.Role
import com.culinary.orderapp.domain.model.SystemRoles
import com.culinary.orderapp.domain.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentUserState @Inject constructor() {

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _currentRole = MutableStateFlow<Role?>(null)
    val currentRole: StateFlow<Role?> = _currentRole.asStateFlow()

    fun update(user: User?, role: Role?) {
        _currentUser.value = user
        _currentRole.value = role
    }

    fun hasPermission(permission: Permission): Boolean {
        val user = _currentUser.value ?: return false
        if (user.roleId == SystemRoles.OWNER) return true
        if (!user.isActive) return false
        return _currentRole.value?.permissions?.contains(permission) == true
    }

    fun isOwner(): Boolean {
        return _currentUser.value?.roleId == SystemRoles.OWNER
    }

    fun clear() {
        _currentUser.value = null
        _currentRole.value = null
    }
}
