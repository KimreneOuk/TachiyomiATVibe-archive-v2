package eu.kanade.presentation.components.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Action row for operations such as colorizing a page or resetting data. */
@Composable
fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    actionLabel: String? = null,
) {
    val accent = if (danger) MaterialTheme.colorScheme.error else DesignTokens.AccentPrimary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else DesignTokens.CardFill)
            .border(
                1.dp,
                if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else DesignTokens.CardBorder,
                DesignTokens.ShapeCard,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(DesignTokens.ShapeBadge)
                .background(DesignTokens.SheetBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else DesignTokens.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DesignTokens.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = DesignTokens.TextMuted,
                fontSize = 11.sp,
            )
        }
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
