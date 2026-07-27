package com.culinary.orderapp.domain.model

import java.util.UUID

/**
 * Represents a menu category (e.g., Makanan Utama, Minuman, Dessert).
 */
data class Category(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val displayOrder: Int = 0,
    val isActive: Boolean = true
)

/**
 * Type of topping/option selection.
 */
enum class ToppingType {
    /** Only one option can be selected (e.g., spice level). */
    SINGLE_SELECT,
    /** Multiple options can be selected (e.g., extra toppings). */
    MULTI_SELECT
}

/**
 * Represents a topping or conditional option for a menu item.
 */
data class Topping(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val additionalPrice: Long = 0L,
    val type: ToppingType = ToppingType.SINGLE_SELECT,
    val isRequired: Boolean = false,
    val isAvailable: Boolean = true
)

/**
 * Represents a topping group (Level 2 menu), which holds a set of related options.
 */
data class ToppingGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: ToppingType = ToppingType.SINGLE_SELECT,
    val isRequired: Boolean = false,
    val toppings: List<Topping> = emptyList()
)

/**
 * Represents a menu item sold by the restaurant.
 */
data class MenuItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val price: Long = 0L,
    val categoryId: String = "",
    val categoryName: String = "",
    val imageUrl: String = "",
    val isAvailable: Boolean = true,
    val toppingGroups: List<ToppingGroup> = emptyList(),
    val preparationTimeMinutes: Int = 10
)
