package com.culinary.orderapp.data.model

import com.culinary.orderapp.domain.model.BusinessSettings
import com.culinary.orderapp.domain.model.Permission
import com.culinary.orderapp.domain.model.Role
import com.culinary.orderapp.domain.model.User
import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName
import java.util.Date

/**
 * Firestore DTO for User
 */
data class UserDto(
    val id: String = "",
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val roleId: String = "",
    val roleName: String = "",
    @PropertyName("isActive")
    val isActive: Boolean = true,
    val createdAt: Timestamp = Timestamp.now(),
    val lastLoginAt: Timestamp? = null
) {
    fun toDomain(): User = User(
        id = id,
        email = email,
        displayName = displayName,
        photoUrl = photoUrl,
        roleId = roleId,
        roleName = roleName,
        isActive = isActive,
        createdAt = createdAt.toDate(),
        lastLoginAt = lastLoginAt?.toDate()
    )

    companion object {
        fun fromDomain(user: User): UserDto = UserDto(
            id = user.id,
            email = user.email,
            displayName = user.displayName,
            photoUrl = user.photoUrl,
            roleId = user.roleId,
            roleName = user.roleName,
            isActive = user.isActive,
            createdAt = Timestamp(user.createdAt),
            lastLoginAt = user.lastLoginAt?.let { Timestamp(it) }
        )
    }
}

/**
 * Firestore DTO for Role
 */
data class RoleDto(
    val id: String = "",
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val permissions: List<String> = emptyList(), // Store as permission names
    @PropertyName("isSystemRole")
    val isSystemRole: Boolean = false,
    val createdAt: Timestamp = Timestamp.now(),
    val updatedAt: Timestamp = Timestamp.now()
) {
    fun toDomain(): Role = Role(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        permissions = permissions.mapNotNull { Permission.fromString(it) },
        isSystemRole = isSystemRole,
        createdAt = createdAt.toDate(),
        updatedAt = updatedAt.toDate()
    )

    companion object {
        fun fromDomain(role: Role): RoleDto = RoleDto(
            id = role.id,
            name = role.name,
            displayName = role.displayName,
            description = role.description,
            permissions = role.permissions.map { it.name },
            isSystemRole = role.isSystemRole,
            createdAt = Timestamp(role.createdAt),
            updatedAt = Timestamp(role.updatedAt)
        )
    }
}

/**
 * Firestore DTO for BusinessSettings
 */
data class BusinessSettingsDto(
    val id: String = "settings",
    val businessName: String = "",
    val webUrl: String = "",
    val phoneNumber: String = "",
    val address: String = "",
    val currency: String = "Rp",
    val taxPercentage: Double = 0.0,
    val serviceChargePercentage: Double = 0.0,
    val logoUrl: String? = null,
    val paymentMethods: Map<String, Boolean> = mapOf(
        "QRIS" to true,
        "BANK_TRANSFER" to true,
        "CASHIER" to true
    ),
    val updatedAt: Timestamp = Timestamp.now(),
    val updatedBy: String = ""
) {
    fun toDomain(): BusinessSettings = BusinessSettings(
        id = id,
        businessName = businessName,
        webUrl = webUrl,
        phoneNumber = phoneNumber,
        address = address,
        currency = currency,
        taxPercentage = taxPercentage,
        serviceChargePercentage = serviceChargePercentage,
        logoUrl = logoUrl,
        paymentMethods = paymentMethods,
        updatedAt = updatedAt.toDate(),
        updatedBy = updatedBy
    )

    companion object {
        fun fromDomain(settings: BusinessSettings): BusinessSettingsDto = BusinessSettingsDto(
            id = settings.id,
            businessName = settings.businessName,
            webUrl = settings.webUrl,
            phoneNumber = settings.phoneNumber,
            address = settings.address,
            currency = settings.currency,
            taxPercentage = settings.taxPercentage,
            serviceChargePercentage = settings.serviceChargePercentage,
            logoUrl = settings.logoUrl,
            paymentMethods = settings.paymentMethods,
            updatedAt = Timestamp(settings.updatedAt),
            updatedBy = settings.updatedBy
        )
    }
}
