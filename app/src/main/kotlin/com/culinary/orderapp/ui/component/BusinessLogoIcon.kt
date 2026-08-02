package com.culinary.orderapp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Displays the business logo from [logoUrl] inside a circular frame.
 * Falls back to the restaurant icon when the URL is null or blank.
 */
@Composable
fun BusinessLogoIcon(
    logoUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = Color.White
) {
    if (!logoUrl.isNullOrBlank()) {
        AsyncImage(
            model = logoUrl,
            contentDescription = "Ikon bisnis",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        )
    } else {
        Icon(
            imageVector = Icons.Filled.Restaurant,
            contentDescription = "Ikon bisnis",
            tint = tint,
            modifier = modifier.size(size)
        )
    }
}
