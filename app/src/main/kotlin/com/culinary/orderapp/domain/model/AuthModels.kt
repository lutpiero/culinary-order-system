package com.culinary.orderapp.domain.model

import java.util.Date

/**
 * Represents a user in the system
 */
data class User(
    val id: String = "",
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val roleId: String = "",
    val roleName: String = "",
    val isActive: Boolean = true,
    val createdAt: Date = Date(),
    val lastLoginAt: Date? = null
)

/**
 * Represents a role with associated permissions
 */
data class Role(
    val id: String = "",
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val permissions: List<Permission> = emptyList(),
    val isSystemRole: Boolean = false, // Cannot be deleted if true
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

/**
 * Represents a permission that can be assigned to roles
 */
enum class Permission(val displayName: String, val description: String) {
    // Menu Management
    VIEW_MENU("View Menu", "Can view menu items"),
    MANAGE_MENU("Manage Menu", "Can create, edit, and delete menu items"),
    MANAGE_CATEGORIES("Manage Categories", "Can create, edit, and delete categories"),
    MANAGE_TOPPINGS("Manage Toppings", "Can create, edit, and delete toppings"),
    
    // Order Management
    VIEW_ORDERS("View Orders", "Can view all orders"),
    MANAGE_ORDERS("Manage Orders", "Can update order status and cancel orders"),
    
    // Finance
    VIEW_FINANCE("View Finance", "Can view financial reports and statistics"),
    
    // User Management
    VIEW_USERS("View Users", "Can view all users"),
    MANAGE_USERS("Manage Users", "Can create, edit, and delete users"),
    
    // Role Management
    VIEW_ROLES("View Roles", "Can view all roles"),
    MANAGE_ROLES("Manage Roles", "Can create, edit, and delete custom roles"),
    
    // Settings
    VIEW_SETTINGS("View Settings", "Can view business settings"),
    MANAGE_SETTINGS("Manage Settings", "Can modify business settings"),
    
    // QR Code
    VIEW_QR_CODE("View QR Code", "Can view and generate QR codes");
    
    companion object {
        fun fromString(value: String): Permission? {
            return entries.find { it.name == value }
        }
    }
}

/**
 * Predefined system roles
 */
object SystemRoles {
    const val OWNER = "owner"
    const val FINANCE = "finance"
    
    fun getOwnerRole(): Role = Role(
        id = OWNER,
        name = OWNER,
        displayName = "Owner",
        description = "Full access to all features",
        permissions = Permission.entries.toList(),
        isSystemRole = true
    )
    
    fun getFinanceRole(): Role = Role(
        id = FINANCE,
        name = FINANCE,
        displayName = "Finance",
        description = "Access to financial reports only",
        permissions = listOf(
            Permission.VIEW_FINANCE,
            Permission.VIEW_ORDERS
        ),
        isSystemRole = true
    )
    
    fun getAllSystemRoles(): List<Role> = listOf(
        getOwnerRole(),
        getFinanceRole()
    )
}

/**
 * Business settings
 */
data class BusinessSettings(
    val id: String = "settings", // Single document
    val businessName: String = "",
    val webUrl: String = "",
    val phoneNumber: String = "",
    val address: String = "",
    val currency: String = "Rp",
    val taxPercentage: Double = 0.0,
    val serviceChargePercentage: Double = 0.0,
    val logoUrl: String? = null,
    val updatedAt: Date = Date(),
    val updatedBy: String = ""
)
