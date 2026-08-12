package com.culinary.orderapp.ui.theme

import androidx.compose.ui.graphics.Color

// Primary brand colors – warm terracotta/orange palette matching Indonesian culinary aesthetics.
// Values are tuned so foreground content always keeps strong contrast against its background.
val OrangePrimary = Color(0xFFC2410C)      // deep terracotta (5.2:1 vs white)
val OrangeLight = Color(0xFFFF8A4C)        // vivid orange (dark theme primary)
val OrangeDark = Color(0xFF8A2E00)         // deepest brand tone

val OrangeContainer = Color(0xFFFFE0CC)    // light peach container
val OnOrangeContainer = Color(0xFF4A1E00)  // deep warm brown on container

// Secondary – amber, darkened so it stays readable as text on light surfaces
val AmberAccent = Color(0xFF8F5A00)
val AmberContainer = Color(0xFFFFE9B0)
val OnAmberContainer = Color(0xFF4A3300)
val AmberDark = Color(0xFF5E3A00)

// Tertiary – warm chestnut, used for labels/icons
val Chestnut = Color(0xFF9B5600)
val ChestnutContainer = Color(0xFFFFDDCB)
val OnChestnutContainer = Color(0xFF5B2E00)

// Neutral
val Background = Color(0xFFFFF8F5)
val Surface = Color(0xFFFFFFFF)
val OnBackground = Color(0xFF1C1B1F)
val OnSurface = Color(0xFF1C1B1F)

// Dark theme variants
val OrangePrimaryDark = Color(0xFFFF8A4C)
val OrangeContainerDark = Color(0xFF5A2B00)
val OnOrangeContainerDark = Color(0xFFFFDDC6)
val AmberAccentDark = Color(0xFFFFBE65)
val AmberContainerDark = Color(0xFF4E3A00)
val OnAmberContainerDark = Color(0xFFFFE9C3)
val ChestnutDark = Color(0xFFFFB46E)
val ChestnutContainerDark = Color(0xFF6B3600)
val OnChestnutContainerDark = Color(0xFFFFDDC4)

// Status colors – pastel container / dark content pairs engineered for readability
val StatusPending = Color(0xFFE3E2E6)
val OnStatusPending = Color(0xFF3F3F46)
val StatusInQueue = Color(0xFFD6E3FF)
val OnStatusInQueue = Color(0xFF0A3D62)
val StatusPreparing = Color(0xFFFFE3B3)
val OnStatusPreparing = Color(0xFF6B4500)
val StatusReady = Color(0xFFD7EDD4)
val OnStatusReady = Color(0xFF1E4A1F)
val StatusServed = Color(0xFFDCE3E9)
val OnStatusServed = Color(0xFF2C3A47)
val StatusCancelled = Color(0xFFFFDAD8)
val OnStatusCancelled = Color(0xFF7D2828)
