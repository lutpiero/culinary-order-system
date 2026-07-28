package com.culinary.orderapp.data.model

import com.culinary.orderapp.domain.model.Category
import com.culinary.orderapp.domain.model.MenuItem
import com.culinary.orderapp.domain.model.Topping
import com.culinary.orderapp.domain.model.ToppingGroup
import com.culinary.orderapp.domain.model.ToppingType
import com.google.firebase.firestore.PropertyName

/**
 * Firestore DTO for a Category document.
 * All fields must have default values for Firestore deserialization.
 */
data class CategoryDto(
    val id: String = "",
    val name: String = "",
    val displayOrder: Int = 0,
    @PropertyName("isActive")
    val isActive: Boolean = true
) {
    fun toDomain() = Category(
        id = id,
        name = name,
        displayOrder = displayOrder,
        isActive = isActive
    )

    companion object {
        fun fromDomain(category: Category) = CategoryDto(
            id = category.id,
            name = category.name,
            displayOrder = category.displayOrder,
            isActive = category.isActive
        )
    }
}

/**
 * Firestore DTO for a Topping.
 */
data class ToppingDto(
    val id: String = "",
    val name: String = "",
    val additionalPrice: Long = 0L,
    val type: String = ToppingType.SINGLE_SELECT.name,
    @PropertyName("isRequired")
    val isRequired: Boolean = false,
    @PropertyName("isAvailable")
    val isAvailable: Boolean = true
) {
    fun toDomain() = Topping(
        id = id,
        name = name,
        additionalPrice = additionalPrice,
        type = ToppingType.valueOf(type),
        isRequired = isRequired,
        isAvailable = isAvailable
    )

    companion object {
        fun fromDomain(topping: Topping) = ToppingDto(
            id = topping.id,
            name = topping.name,
            additionalPrice = topping.additionalPrice,
            type = topping.type.name,
            isRequired = topping.isRequired,
            isAvailable = topping.isAvailable
        )
    }
}

/**
 * Firestore DTO for a ToppingGroup.
 */
data class ToppingGroupDto(
    val id: String = "",
    val name: String = "",
    val type: String = ToppingType.SINGLE_SELECT.name,
    @PropertyName("isRequired")
    val isRequired: Boolean = false,
    val toppings: List<ToppingDto> = emptyList()
) {
    fun toDomain() = ToppingGroup(
        id = id,
        name = name,
        type = ToppingType.valueOf(type),
        isRequired = isRequired,
        toppings = toppings.map { it.toDomain() }
    )

    companion object {
        fun fromDomain(group: ToppingGroup) = ToppingGroupDto(
            id = group.id,
            name = group.name,
            type = group.type.name,
            isRequired = group.isRequired,
            toppings = group.toppings.map { ToppingDto.fromDomain(it) }
        )
    }
}

/**
 * Firestore DTO for a MenuItem document.
 */
data class MenuItemDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val price: Long = 0L,
    val categoryId: String = "",
    val categoryName: String = "",
    val imageUrl: String = "",
    @PropertyName("isAvailable")
    val isAvailable: Boolean = true,
    val toppingGroups: List<ToppingGroupDto> = emptyList(),
    val preparationTimeMinutes: Int = 10,
    val stock: Int? = null
) {
    fun toDomain() = MenuItem(
        id = id,
        name = name,
        description = description,
        price = price,
        categoryId = categoryId,
        categoryName = categoryName,
        imageUrl = imageUrl,
        isAvailable = isAvailable,
        toppingGroups = toppingGroups.map { it.toDomain() },
        preparationTimeMinutes = preparationTimeMinutes,
        stock = stock
    )

    companion object {
        fun fromDomain(item: MenuItem) = MenuItemDto(
            id = item.id,
            name = item.name,
            description = item.description,
            price = item.price,
            categoryId = item.categoryId,
            categoryName = item.categoryName,
            imageUrl = item.imageUrl,
            isAvailable = item.isAvailable,
            toppingGroups = item.toppingGroups.map { ToppingGroupDto.fromDomain(it) },
            preparationTimeMinutes = item.preparationTimeMinutes,
            stock = item.stock
        )
    }
}
