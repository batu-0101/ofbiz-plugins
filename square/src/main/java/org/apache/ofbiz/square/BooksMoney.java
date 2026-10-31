package org.apache.ofbiz.square;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact cents at the provider boundary; OFBiz's native BigDecimal amounts stay authoritative. */
public final class BooksMoney {
    private BooksMoney() { }

    public record Charge(long principal, long fee, long total) { }

    public static long cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    public static BigDecimal amount(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    public static Charge charge(long principal, boolean enabled, long bps, long fixed) {
        if (principal <= 0) {
            throw new IllegalArgumentException("There is no positive amount due to collect.");
        }
        if (!enabled) {
            return new Charge(principal, 0, principal);
        }
        if (bps < 0 || bps > 2000 || fixed < 0 || fixed > 1000) {
            throw new IllegalArgumentException("The card fee configuration is invalid.");
        }
        long percentage = BigDecimal.valueOf(principal).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValueExact();
        long fee = Math.addExact(percentage, fixed);
        return new Charge(principal, fee, Math.addExact(principal, fee));
    }

    public static void requireConsent(Charge current, long principal, long fee, long total, boolean accepted) {
        if (current.fee() > 0 && !accepted) {
            throw new IllegalArgumentException("Review and accept the card processing fee before continuing.");
        }
        if (current.principal() != principal || current.fee() != fee || current.total() != total) {
            throw new IllegalArgumentException("The card amount changed. Review and accept the current total before continuing.");
        }
    }
}
