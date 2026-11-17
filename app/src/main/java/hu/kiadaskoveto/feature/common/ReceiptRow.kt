package hu.kiadaskoveto.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.core.money.Money
import hu.kiadaskoveto.data.WallClock
import hu.kiadaskoveto.database.model.ReceiptSummary
import hu.kiadaskoveto.ui.components.MoneyText
import hu.kiadaskoveto.ui.components.ReviewMarker
import hu.kiadaskoveto.ui.format.Fmt
import hu.kiadaskoveto.ui.theme.Spacing

@Composable
fun ReceiptRow(r: ReceiptSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val date = WallClock.fromStored(r.purchasedAt).toLocalDate()
    Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = Spacing.sm, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(r.storeName ?: "Ismeretlen bolt", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val dateText = if (r.dateKnown) Fmt.relativeDay(date) else "Dátum nincs megadva"
                val items = if (r.itemCount > 0) " · ${r.itemCount} tétel" else ""
                Text(dateText + items, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                when {
                    r.status == "FAILED" -> ReviewMarker("Feldolgozás sikertelen", Modifier.padding(top = 4.dp))
                    r.status == "DRAFT" -> ReviewMarker("Jóváhagyásra vár", Modifier.padding(top = 4.dp))
                    r.reviewCount > 0 -> ReviewMarker("${r.reviewCount} sor ellenőrzendő", Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            MoneyText(Money(r.totalMinor), style = MaterialTheme.typography.titleSmall)
        }
    }
}
