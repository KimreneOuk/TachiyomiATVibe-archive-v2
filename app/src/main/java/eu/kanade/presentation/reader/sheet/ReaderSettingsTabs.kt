package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.designsystem.DesignTokens

@Composable
internal fun ReaderSettingsTabs(tabs: List<String>, selectedIndex: Int, onTabSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(DesignTokens.ShapePill)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapePill)
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            val bringIntoView = remember { BringIntoViewRequester() }
            LaunchedEffect(selected) {
                if (selected) bringIntoView.bringIntoView()
            }
            Box(
                modifier = Modifier
                    .bringIntoViewRequester(bringIntoView)
                    .defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                    .clip(DesignTokens.ShapePill)
                    .background(if (selected) DesignTokens.AccentPrimary else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab) { onTabSelected(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    color = if (selected) DesignTokens.TextOnAccent else DesignTokens.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    softWrap = false,
                )
            }
        }
    }
}
