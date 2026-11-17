package hu.kiadaskoveto

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives every top-level destination and every settings screen on a real device/emulator. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun skipOnboarding() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KiadasApp
        runBlocking { app.container.settings.setOnboardingDone(true); app.container.settings.setAppLock(false) }
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Áttekintés").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tab(label: String) = rule.onNodeWithTag("nav:$label").performClick()

    @Test fun topLevelDestinationsOpen() {
        tab("Blokkok")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Keresés boltra vagy termékre").fetchSemanticsNodes().isNotEmpty() }
        tab("Statisztika")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Havi").fetchSemanticsNodes().isNotEmpty() }
        listOf("Kategóriák", "Termékek", "Árak").forEach { rule.onAllNodesWithText(it).onFirst().performClick() }
        tab("Termékek")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Keresés terméknévre").fetchSemanticsNodes().isNotEmpty() }
        tab("Beállítások")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Megjelenés").fetchSemanticsNodes().isNotEmpty() }
        tab("Áttekintés")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("fab:add").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun addReceiptSheetOffersAllImportOptions() {
        rule.onNodeWithTag("fab:add").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Fényképezés").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Kép importálása").assertExists()
        rule.onNodeWithText("PDF importálása").assertExists()
        rule.onNodeWithText("Kézi rögzítés").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Blokk ellenőrzése").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Nincsenek tételek", substring = true).assertExists()
        // Discard the empty manual draft
        rule.onNodeWithText("Blokk ellenőrzése").assertExists()
        rule.onAllNodesWithContentDescription("Blokk törlése").onFirst().performClick()
        rule.onNodeWithText("Törlés").performClick()
        // Back on the dashboard once the draft is gone
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("fab:add").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Nincsenek tételek", substring = true).assertCountEquals(0)
    }

    @Test fun everySettingsScreenOpensAndReturns() {
        val screens = listOf(
            "Költségkeretek" to "Figyelmeztetések",
            "Kategóriák" to "Zöldség és gyümölcs",
            "Kategorizálási szabályok" to "Saját szabályok",
            "Boltok" to "Boltok",
            "Biztonsági mentés és exportálás" to "Biztonsági mentés",
            "Helyi AI (opcionális)" to "Készülék",
            "Adatvédelem" to "Hol vannak az adataid?",
            "Névjegy és licencek" to "Nyílt forrású összetevők",
        )
        for ((entry, marker) in screens) {
            tab("Beállítások")
            rule.waitUntil(10_000) { rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(entry))
            rule.onNodeWithText(entry).performClick()
            try {
                rule.waitUntil(10_000) { rule.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isNotEmpty() }
            } catch (e: Throwable) {
                throw AssertionError("Screen '$entry' did not show '$marker'", e)
            }
            Espresso.pressBack()
            rule.waitForIdle()
        }
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTag(tag: String) =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag))

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithContentDescription(desc: String) =
    onAllNodes(androidx.compose.ui.test.hasContentDescription(desc))
