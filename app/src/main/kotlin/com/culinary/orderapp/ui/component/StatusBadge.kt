package com.culinary.orderapp.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.culinary.orderapp.domain.model.OrderStatus
import com.culinary.orderapp.ui.theme.StatusCancelled
import com.culinary.orderapp.ui.theme.StatusInQueue
import com.culinary.orderapp.ui.theme.StatusPending
import com.culinary.orderapp.ui.theme.StatusPreparing
import com.culinary.orderapp.ui.theme.StatusReady
import com.culinary.orderapp.ui.theme.StatusServed

/**
 * A pill-shaped badge displaying the order status with an appropriate colour.
 */
@Composable
fun StatusBadge(status: OrderStatus, large: Boolean = false) {
    val (backgroundColor, textColor) = when (status) {
        OrderStatus.PENDING -> StatusPending to Color.White
        OrderStatus.IN_QUEUE -> StatusInQueue to Color.White
        OrderStatus.PREPARING -> StatusPreparing to Color.White
        OrderStatus.READY -> StatusReady to Color.White
        OrderStatus.SERVED -> StatusServed to Color.White
        OrderStatus.CANCELLED -> StatusCancelled to Color.White
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .padding(horizontal = if (large) 12.dp else 8.dp, vertical = if (large) 6.dp else 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = status.displayName,
            color = textColor,
            fontSize = if (large) 14.sp else 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
