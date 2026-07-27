package com.culinary.orderapp.util

import java.text.NumberFormat
import java.util.Locale

/**
 * Formats a [Long] amount (in IDR) to a human-readable Rupiah string.
 * Example: 15000 → "Rp 15.000"
 */
fun Long.toRupiahFormat(): String {
    val format = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${format.format(this)}"
}

/**
 * Formats an [Int] representing minutes to a human-readable string.
 * Example: 65 → "1 jam 5 menit"
 */
fun Int.toMinutesDisplay(): String {
    return when {
        this < 60 -> "$this menit"
        this % 60 == 0 -> "${this / 60} jam"
        else -> "${this / 60} jam ${this % 60} menit"
    }
}
