package hu.kiadaskoveto.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun `folds Hungarian accents`() {
        assertEquals("ARVIZTURO TUKORFUROGEP", HungarianText.matchKey("Árvíztűrő tükörfúrógép"))
        assertEquals("TEJ ESL 2,8% 1L", HungarianText.matchKey("  tej  ESL 2,8%   1L "))
        assertEquals("COCA COLA 1,5L", HungarianText.matchKey("Coca-Cola 1,5L"))
    }

    @Test fun `repairs numeric OCR confusions only in numeric tokens`() {
        assertEquals("459", HungarianText.repairNumericToken("4S9"))
        assertEquals("1.299", HungarianText.repairNumericToken("l.Z99"))
        assertEquals("BANAN", HungarianText.repairNumericToken("BANAN"))
    }

    @Test fun `fuzzy similarity tolerates OCR errors`() {
        assertTrue(Fuzzy.similarity("OSSZESEM", "OSSZESEN") >= 0.8)
        assertTrue(Fuzzy.similarity("JOGHUTR", "JOGHURT") >= 0.7)
        assertEquals(1, Fuzzy.damerauLevenshtein("JOGHUTR", "JOGHURT"))
        assertTrue(Fuzzy.tokenSetSimilarity("TEJ ESL 2,8% 1L", "ESL TEJ 2,8% 1L") > 0.99)
        assertTrue(Fuzzy.jaroWinkler("TRAPPISTA", "TRAPISTA") > 0.9)
    }

    @Test fun `sentence case uses Hungarian locale`() {
        assertEquals("Őszibarack befőtt", HungarianText.sentenceCase("ŐSZIBARACK BEFŐTT"))
    }
}
