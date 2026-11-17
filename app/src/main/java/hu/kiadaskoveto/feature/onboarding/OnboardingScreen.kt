package hu.kiadaskoveto.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hu.kiadaskoveto.ui.theme.Spacing
import kotlinx.coroutines.launch

private data class OnboardingPage(val icon: ImageVector, val title: String, val text: String)

private val pages = listOf(
    OnboardingPage(Icons.Outlined.WifiOff, "Az adataid a telefonodon maradnak",
        "Nincs regisztráció, nincs felhő és nincs szükség internetre. A blokkjaid, termékeid és kimutatásaid kizárólag ezen a készüléken vannak."),
    OnboardingPage(Icons.Outlined.DocumentScanner, "Fényképezd le vagy importáld a blokkot",
        "Kamera, kép vagy PDF. A szövegfelismerés a telefonon fut, magyar nyelvi modellel. Amit nem tud biztosan kiolvasni, azt megjelöli ellenőrzésre."),
    OnboardingPage(Icons.Outlined.AutoAwesome, "Automatikus kategorizálás",
        "A termékek kategóriát kapnak a bolti rövidítések alapján is. Ha javítasz egyet, a KiadásKövető megjegyzi a következő vásárlásra."),
    OnboardingPage(Icons.Outlined.Insights, "Kimutatások helyben",
        "Havi költés, kategóriák, árváltozások, boltok összevetése és költségkeretek — mind a telefonodon számolva, pontos forintösszegekkel."),
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kihagyás") }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                val p = pages[index]
                Column(
                    Modifier.fillMaxSize().padding(horizontal = Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(132.dp).clip(RoundedCornerShape(40.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(p.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(64.dp))
                    }
                    Spacer(Modifier.height(Spacing.section))
                    Text(p.title, style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(Spacing.md))
                    Text(p.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(max = 440.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(Spacing.xl).semantics { contentDescription = "${pager.currentPage + 1}. oldal a ${pages.size}-ből" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f)) {
                    repeat(pages.size) { i ->
                        val active = i == pager.currentPage
                        Box(Modifier.padding(end = 6.dp).height(8.dp).width(if (active) 24.dp else 8.dp).clip(CircleShape)
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                Button(
                    onClick = { if (pager.currentPage == pages.lastIndex) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (pager.currentPage == pages.lastIndex) "Kezdés" else "Tovább") }
            }
        }
    }
}
