package org.apache.ofbiz.square;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.math.BigDecimal;

import org.junit.Test;

public class BooksMoneyTest {
    @Test
    public void usesOriginalBasisPointPlusFixedFormula() {
        assertEquals(new BooksMoney.Charge(10000, 379, 10379), BooksMoney.charge(10000, true, 349, 30));
        assertEquals(new BooksMoney.Charge(1, 1, 2), BooksMoney.charge(1, true, 0, 1));
        assertEquals(new BooksMoney.Charge(50, 1, 51), BooksMoney.charge(50, true, 100, 0));
    }

    @Test
    public void disabledFeeDoesNotReadUnusedRate() {
        assertEquals(new BooksMoney.Charge(12345, 0, 12345), BooksMoney.charge(12345, false, -1, -1));
    }

    @Test
    public void failsClosedOnInvalidAmountsOrRates() {
        for (long amount : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> BooksMoney.charge(amount, true, 300, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> BooksMoney.charge(100, true, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> BooksMoney.charge(100, true, 2001, 0));
        assertThrows(IllegalArgumentException.class, () -> BooksMoney.charge(100, true, 300, -1));
        assertThrows(ArithmeticException.class, () -> BooksMoney.charge(Long.MAX_VALUE, true, 300, 0));
    }

    @Test
    public void everyDisclosedFigureMustMatchAndConsentMustBeExplicit() {
        BooksMoney.Charge c = BooksMoney.charge(10000, true, 300, 30);
        BooksMoney.requireConsent(c, 10000, 330, 10330, true);
        assertThrows(IllegalArgumentException.class, () -> BooksMoney.requireConsent(c, 10000, 330, 10330, false));
        assertThrows(IllegalArgumentException.class, () -> BooksMoney.requireConsent(c, 9999, 330, 10330, true));
        assertThrows(IllegalArgumentException.class, () -> BooksMoney.requireConsent(c, 10000, 329, 10330, true));
        assertThrows(IllegalArgumentException.class, () -> BooksMoney.requireConsent(c, 10000, 330, 10331, true));
        BooksMoney.requireConsent(BooksMoney.charge(10000, false, 0, 0), 10000, 0, 10000, false);
    }

    @Test
    public void exactDecimalBoundaryRejectsHiddenFractions() {
        assertEquals(12345, BooksMoney.cents(new BigDecimal("123.45000")));
        assertEquals(new BigDecimal("123.45"), BooksMoney.amount(12345));
        assertThrows(ArithmeticException.class, () -> BooksMoney.cents(new BigDecimal("123.456")));
    }
}
