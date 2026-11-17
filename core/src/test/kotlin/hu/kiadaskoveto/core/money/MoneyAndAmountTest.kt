package hu.kiadaskoveto.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MoneyAndAmountTest {

    private fun money(s: String) = AmountParser.parseMoney(s)

    @Test fun `parses Hungarian money formats`() {
        assertEquals(Money.ofForint(1299), money("1299"))
        assertEquals(Money.ofForint(1299), money("1 299"))
        assertEquals(Money.ofForint(1299), money("1.299"))
        assertEquals(Money.ofForint(1299), money("1.299,00"))
        assertEquals(Money.ofForint(1299), money("1,299.00"))
        assertEquals(Money.ofForint(1299), money("1 299 Ft"))
        assertEquals(Money(1250), money("12,50"))
        assertEquals(Money.ofForint(-150), money("-150"))
        assertEquals(Money.ofForint(-150), money("150-"))
        assertEquals(Money.ofForint(1234567), money("1.234.567"))
        assertEquals(Money.ofForint(449), money("449,00"))
    }

    @Test fun `rejects malformed amounts`() {
        assertNull(money(""))
        assertNull(money("abc"))
        assertNull(money("12.34.5"))
        assertNull(money("2024.03.15"))
        assertNull(money("14:32"))
        assertNull(money("1,2,3"))
    }

    @Test fun `three digit group means thousands for money but decimals for quantities`() {
        assertEquals(Money.ofForint(1299), money("1,299"))
        assertEquals(Quantity(1299), AmountParser.parseQuantity("1,299"))
        assertEquals(Quantity(456), AmountParser.parseQuantity("0,456"))
        assertEquals(Quantity(456), AmountParser.parseQuantity("0.456"))
        assertEquals(Quantity(2000), AmountParser.parseQuantity("2"))
        assertNull(AmountParser.parseQuantity("0"))
    }

    @Test fun `money arithmetic is exact`() {
        val sum = listOf(Money(10), Money(20), Money(30)).sumMoney()
        assertEquals(Money(60), sum)
        // 0,1 + 0,2 style floating point errors cannot happen
        assertEquals(Money(30), Money(10) + Money(20))
        // 0,846 kg × 499 Ft/kg = 422,154 Ft → rounded half-up to whole fillér
        assertEquals(Money(42215), Money.ofForint(499) * Quantity(846))
        assertEquals(Money(42215), Money.fromBigDecimal(BigDecimal("422.154")))
    }

    @Test fun `formats Hungarian style`() {
        assertEquals("1 299 Ft", Money.ofForint(1299).format())
        assertEquals("-150 Ft", Money.ofForint(-150).format())
        assertEquals("12,50 Ft", Money(1250).format())
        assertEquals("1 234 567", Money.ofForint(1234567).format(withCurrency = false))
        assertEquals("0,456", Quantity(456).format())
        assertEquals("2", Quantity(2000).format())
    }

    @Test fun `unit price only when quantity known`() {
        assertNull(PriceMath.unitPrice(Money.ofForint(658), null))
        assertEquals(Money.ofForint(329), PriceMath.unitPrice(Money.ofForint(658), Quantity.ofWhole(2)))
        assertTrue(PriceMath.isConsistent(Quantity(846), Money.ofForint(499), Money.ofForint(422)))
        assertFalse(PriceMath.isConsistent(Quantity.ofWhole(2), Money.ofForint(329), Money.ofForint(700)))
    }

    @Test fun `price per base unit normalizes package sizes`() {
        val half = PackageSize(BigDecimal("0.5"), MeasureUnit.LITER)
        val ml = PackageSize(BigDecimal("500"), MeasureUnit.MILLILITER)
        assertEquals(BigDecimal("658.00"), PriceMath.pricePerBaseUnit(Money.ofForint(329), Quantity.ONE, half))
        assertEquals(BigDecimal("658.00"), PriceMath.pricePerBaseUnit(Money.ofForint(329), Quantity.ONE, ml))
        assertTrue(half.isComparableWith(ml))
        assertFalse(half.isComparableWith(PackageSize(BigDecimal.ONE, MeasureUnit.KILOGRAM)))
        assertNull(PriceMath.pricePerBaseUnit(Money.ofForint(329), Quantity.ONE, null))
    }

    @Test fun `percent change and share`() {
        assertEquals(BigDecimal("10.0"), PriceMath.percentChange(Money.ofForint(1000), Money.ofForint(1100)))
        assertEquals(BigDecimal("-25.0"), PriceMath.percentChange(Money.ofForint(400), Money.ofForint(300)))
        assertNull(PriceMath.percentChange(Money.ZERO, Money.ofForint(300)))
        assertEquals(BigDecimal("33.3"), PriceMath.percentOf(Money.ofForint(1), Money.ofForint(3)))
        assertEquals(Money(33333), PriceMath.average(Money.ofForint(1000), 3))
        assertEquals(Money.ofForint(333), PriceMath.average(Money.ofForint(1000), 3).roundedToForint())
        assertEquals(Money.ofForint(198), Money(19818).roundedToForint())
        assertEquals(Money.ofForint(199), Money(19850).roundedToForint())
        assertEquals(Money.ofForint(-198), Money(-19818).roundedToForint())
    }

    @Test fun `Hungarian cash rounding`() {
        assertEquals(Money.ofForint(5315), PriceMath.cashRounded(Money.ofForint(5313)))
        assertEquals(Money.ofForint(5310), PriceMath.cashRounded(Money.ofForint(5312)))
        assertEquals(Money.ofForint(880), PriceMath.cashRounded(Money.ofForint(878)))
        assertEquals(Money.ofForint(2155), PriceMath.cashRounded(Money.ofForint(2155)))
    }
}
