package hu.kiadaskoveto.parser

import hu.kiadaskoveto.core.money.MeasureUnit
import hu.kiadaskoveto.parser.categorize.CategorizationContext
import hu.kiadaskoveto.parser.categorize.CategoryKeys
import hu.kiadaskoveto.parser.categorize.ProductCategorizer
import hu.kiadaskoveto.parser.categorize.RuleMatchType
import hu.kiadaskoveto.parser.categorize.UserCategoryMapping
import hu.kiadaskoveto.parser.categorize.UserCategoryRule
import hu.kiadaskoveto.parser.model.CategorySource
import hu.kiadaskoveto.parser.normalize.ProductNameInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CategorizationTest {

    private val categorizer = ProductCategorizer()
    private val interpreter = ProductNameInterpreter()

    private fun cat(name: String) = categorizer.categorize(name, null).key

    @Test fun `examples from the specification`() {
        val milk = interpreter.interpret("TEJ ESL 2,8% 1L")
        assertEquals("Tej", milk.productType)
        assertEquals(BigDecimal("1"), milk.packageSize?.amount)
        assertEquals(MeasureUnit.LITER, milk.packageSize?.unit)
        assertTrue(milk.variant!!.contains("2,8%"))

        val cheese = interpreter.interpret("TRAP SAJT KG")
        assertEquals("Trappista sajt", cheese.productType)
        assertTrue(cheese.soldByWeight)

        val banana = interpreter.interpret("BANAN LOOSE")
        assertEquals("Banán", banana.displayName)

        val cola = interpreter.interpret("COCA COLA 1,5L")
        assertEquals("Coca-Cola", cola.brand)
        assertEquals(BigDecimal("1.5"), cola.packageSize?.amount)
        assertEquals(MeasureUnit.LITER, cola.packageSize?.unit)
    }

    @Test fun `unexplained words keep the receipt's own spelling`() {
        val bread = interpreter.interpret("Teljes kiőrlésű kenyér 500g")
        assertEquals("Kenyér", bread.productType)
        assertEquals("Kenyér teljes kiőrlésű", bread.displayName)
        assertEquals("500 g", bread.packageSize?.format())
        val milk = interpreter.interpret("Félzsíros tej 1,5% 1l")
        assertEquals("Tej félzsíros 1,5%", milk.displayName)
        assertEquals("Pilos", interpreter.interpret("Pilos Joghurt natúr 150g").brand)
    }

    @Test fun `interpretations are never fully certain for unknown words`() {
        val unknown = interpreter.interpret("XYZ QWERTY 12")
        assertNull(unknown.productType)
        assertTrue(unknown.certainty <= 0.4)
    }

    @Test fun `multipack sizes are multiplied`() {
        val water = interpreter.interpret("ASV.VIZ SZM 6X1,5L")
        assertEquals(BigDecimal("9.0"), water.packageSize?.amount)
        assertEquals(MeasureUnit.LITER, water.packageSize?.unit)
    }

    @Test fun `default categories for typical Hungarian lines`() {
        assertEquals(CategoryKeys.PRODUCE, cat("BANAN LOOSE"))
        assertEquals(CategoryKeys.PRODUCE, cat("PARADICSOM KOKTEL 250G"))
        assertEquals(CategoryKeys.MEAT, cat("CSIRKEMELLFILE 1KG"))
        assertEquals(CategoryKeys.MEAT, cat("PICK TELISZALAMI"))
        assertEquals(CategoryKeys.DAIRY, cat("TEJF 20% 330G"))
        assertEquals(CategoryKeys.DAIRY, cat("JOGHURT EPRES 150G"))
        assertEquals(CategoryKeys.BAKERY, cat("KIFLI VAJAS 60G"))
        assertEquals(CategoryKeys.FOOD_OTHER, cat("FINOMLISZT BL55 1KG"))
        assertEquals(CategoryKeys.DRINKS, cat("ASV.VIZ SZM 1,5L"))
        assertEquals(CategoryKeys.SWEETS, cat("TEJCSOKOLADE 100G"))
        assertEquals(CategoryKeys.FROZEN, cat("FAGY. ZOLDBORSO 450G"))
        assertEquals(CategoryKeys.FROZEN, cat("JEGKREM VANILIA"))
        assertEquals(CategoryKeys.HOUSEHOLD, cat("WC PAPIR 3 RET 8TEK"))
        assertEquals(CategoryKeys.CLEANING, cat("MOSOGATOSZER CITROM 1L"))
        assertEquals(CategoryKeys.COSMETICS, cat("FOGKREM MENTA 75ML"))
        assertEquals(CategoryKeys.COSMETICS, cat("TISZTASAGI BETET"))
        assertEquals(CategoryKeys.BABY, cat("PELENKA 4 MAXI 46DB"))
        assertEquals(CategoryKeys.PET, cat("MACSKAELEDEL ALUTALCAS 100G"))
        assertEquals(CategoryKeys.ALCOHOL, cat("DREHER SOR 0,5L DOB"))
        assertEquals(CategoryKeys.TOBACCO, cat("MARLBORO GOLD"))
        assertEquals(CategoryKeys.UNCATEGORIZED, cat("XQZ 123"))
    }

    @Test fun `OCR-damaged words are matched fuzzily with lower confidence`() {
        val guess = categorizer.categorize("JOGHUTR NATUR", null)
        assertEquals(CategoryKeys.DAIRY, guess.key)
        assertTrue(guess.confidence < 0.95)
    }

    @Test fun `confirmed user correction wins and is store aware`() {
        val ctx = CategorizationContext(
            mappings = listOf(
                UserCategoryMapping("BANAN LOOSE", "spar", CategoryKeys.OTHER),
                UserCategoryMapping("TITOKZATOS ARU", null, CategoryKeys.HOUSEHOLD),
            )
        )
        val atSpar = categorizer.categorize("BANAN LOOSE", "spar", ctx)
        assertEquals(CategoryKeys.OTHER, atSpar.key)
        assertEquals(CategorySource.USER_MAPPING, atSpar.source)
        assertEquals(1.0, atSpar.confidence, 0.0)
        assertEquals(CategoryKeys.HOUSEHOLD, categorizer.categorize("Titokzatos áru", "lidl", ctx).key)
        // OCR variation of a confirmed line is recognized too
        assertEquals(CategoryKeys.HOUSEHOLD, categorizer.categorize("TITOKZATOS ARV", "lidl", ctx).key)
    }

    @Test fun `user rules apply before the dictionary`() {
        val ctx = CategorizationContext(rules = listOf(
            UserCategoryRule("KUTYA", RuleMatchType.CONTAINS, CategoryKeys.OTHER, priority = 1),
            UserCategoryRule("^BIO ", RuleMatchType.REGEX, CategoryKeys.FOOD_OTHER),
        ))
        val g = categorizer.categorize("KUTYAELEDEL 1KG", null, ctx)
        assertEquals(CategoryKeys.OTHER, g.key)
        assertEquals(CategorySource.USER_RULE, g.source)
        assertEquals(CategoryKeys.FOOD_OTHER, categorizer.categorize("BIO ZABPELYHES ALMA", null, ctx).key)
    }
}
