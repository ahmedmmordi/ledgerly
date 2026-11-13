package io.github.ahmedmmordi.ledgerly.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");
    private static final Currency KWD = Currency.getInstance("KWD");

    @Test
    @DisplayName("Creates money from minor units and currency")
    void createsMoneyFromMinorUnitsAndCurrency() {
        Money money = Money.ofMinor(1050L, USD);

        assertThat(money.amountInMinorUnits()).isEqualTo(1050L);
        assertThat(money.amountInCents()).isEqualTo(1050L);
        assertThat(money.currency()).isEqualTo(USD);
    }

    @Test
    @DisplayName("Creates money from string currency code with whitespace and mixed case")
    void createsMoneyFromStringCodeWithWhitespace() {
        Money money = Money.ofMinor(500L, "  usd  ");

        assertThat(money.amountInMinorUnits()).isEqualTo(500L);
        assertThat(money.currency()).isEqualTo(USD);
    }

    @Test
    @DisplayName("Rejects null, blank, or invalid currency codes")
    void rejectsInvalidCurrencyCodes() {
        assertThatThrownBy(() -> Money.ofMinor(100L, (Currency) null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Currency must not be null");

        assertThatThrownBy(() -> Money.ofMinor(100L, (String) null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Currency code must not be null");

        assertThatThrownBy(() -> Money.ofMinor(100L, "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency code must not be blank");

        assertThatThrownBy(() -> Money.ofMinor(100L, "   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency code must not be blank");

        assertThatThrownBy(() -> Money.ofMinor(100L, "INVALID")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Money.ofMinor(100L, "US")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects pseudo-currencies and commodities without minor units (fraction digits < 0)")
    void rejectsPseudoCurrenciesWithoutMinorUnits() {
        Currency xxx = Currency.getInstance("XXX"); // No currency
        Currency xts = Currency.getInstance("XTS"); // Testing code
        Currency xau = Currency.getInstance("XAU"); // Gold ounce

        assertThatThrownBy(() -> Money.ofMinor(100L, xxx)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support minor units");

        assertThatThrownBy(() -> Money.ofMinor(100L, xts)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support minor units");

        assertThatThrownBy(() -> Money.ofMinor(100L, xau)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support minor units");
    }

    @Test
    @DisplayName("Creates money from BigDecimal matching currency decimal scale")
    void createsMoneyFromBigDecimal() {
        Money usd = Money.of(new BigDecimal("10.50"), USD);
        assertThat(usd.amountInMinorUnits()).isEqualTo(1050L);

        // 10.5 is safely rescaled to 10.50 (2 decimals) without loss of precision
        Money usdFromOneDecimal = Money.of(new BigDecimal("10.5"), "USD");
        assertThat(usdFromOneDecimal.amountInMinorUnits()).isEqualTo(1050L);

        // 0-decimal currency (JPY)
        Money jpy = Money.of(new BigDecimal("500"), JPY);
        assertThat(jpy.amountInMinorUnits()).isEqualTo(500L);

        // 3-decimal currency (KWD)
        Money kwd = Money.of(new BigDecimal("1.234"), KWD);
        assertThat(kwd.amountInMinorUnits()).isEqualTo(1234L);
    }

    @Test
    @DisplayName("Rejects BigDecimal with fractional precision exceeding currency minor units")
    void rejectsBigDecimalWithExcessPrecision() {
        // USD only supports 2 decimals; 10.505 would require rounding
        assertThatThrownBy(() -> Money.of(new BigDecimal("10.505"), USD)).isInstanceOf(ArithmeticException.class);

        // JPY supports 0 decimals; 500.5 would require rounding
        assertThatThrownBy(() -> Money.of(new BigDecimal("500.5"), JPY)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("Converts to BigDecimal with proper scale per currency")
    void convertsToBigDecimal() {
        assertThat(Money.ofMinor(1050L, USD).toBigDecimal()).isEqualTo(new BigDecimal("10.50"));
        assertThat(Money.ofMinor(500L, JPY).toBigDecimal()).isEqualTo(new BigDecimal("500"));
        assertThat(Money.ofMinor(1234L, KWD).toBigDecimal()).isEqualTo(new BigDecimal("1.234"));
    }

    @Test
    @DisplayName("Creates zero money instances")
    void createsZeroMoney() {
        Money zero1 = Money.zero(USD);
        Money zero2 = Money.zero("usd");

        assertThat(zero1.amountInMinorUnits()).isEqualTo(0L);
        assertThat(zero1.isZero()).isTrue();
        assertThat(zero1).isEqualTo(zero2);
    }

    @Test
    @DisplayName("Adds amounts with same currency")
    void addsSameCurrency() {
        Money m1 = Money.ofMinor(1000L, USD);
        Money m2 = Money.ofMinor(250L, USD);

        Money result = m1.plus(m2);

        assertThat(result.amountInMinorUnits()).isEqualTo(1250L);
        assertThat(result.currency()).isEqualTo(USD);
    }

    @Test
    @DisplayName("Subtracts amounts with same currency")
    void subtractsSameCurrency() {
        Money m1 = Money.ofMinor(1000L, USD);
        Money m2 = Money.ofMinor(250L, USD);

        Money result = m1.minus(m2);

        assertThat(result.amountInMinorUnits()).isEqualTo(750L);
        assertThat(result.currency()).isEqualTo(USD);
    }

    @Test
    @DisplayName("Rejects arithmetic on mismatched currencies")
    void rejectsArithmeticOnMismatchedCurrencies() {
        Money usd = Money.ofMinor(1000L, USD);
        Money eur = Money.ofMinor(1000L, EUR);

        assertThatThrownBy(() -> usd.plus(eur)).isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("USD").hasMessageContaining("EUR");

        assertThatThrownBy(() -> usd.minus(eur)).isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("USD").hasMessageContaining("EUR");
    }

    @Test
    @DisplayName("Throws NullPointerException with clear message on null operands")
    void rejectsNullOperandsInArithmetic() {
        Money usd = Money.ofMinor(1000L, USD);

        assertThatThrownBy(() -> usd.plus(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("Money operand must not be null");

        assertThatThrownBy(() -> usd.minus(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("Money operand must not be null");
    }

    @Test
    @DisplayName("Handles negative operands, minus going negative, and preserves immutability")
    void handlesNegativeAndPreservesImmutability() {
        Money initial = Money.ofMinor(100L, USD);
        Money larger = Money.ofMinor(150L, USD);
        Money negative = Money.ofMinor(-40L, USD);

        // Minus going negative
        Money deficit = initial.minus(larger);
        assertThat(deficit.amountInMinorUnits()).isEqualTo(-50L);
        assertThat(deficit.isNegative()).isTrue();

        // Adding a negative operand
        Money reduced = initial.plus(negative);
        assertThat(reduced.amountInMinorUnits()).isEqualTo(60L);

        // Immutability: original operands are strictly unchanged
        assertThat(initial.amountInMinorUnits()).isEqualTo(100L);
        assertThat(larger.amountInMinorUnits()).isEqualTo(150L);
        assertThat(negative.amountInMinorUnits()).isEqualTo(-40L);
    }

    @Test
    @DisplayName("Boundary values with zero do not throw")
    void boundaryValuesWithZeroDoNotThrow() {
        Money max = Money.ofMinor(Long.MAX_VALUE, USD);
        Money min = Money.ofMinor(Long.MIN_VALUE, USD);
        Money zero = Money.zero(USD);

        assertThat(max.plus(zero)).isEqualTo(max);
        assertThat(max.minus(zero)).isEqualTo(max);
        assertThat(min.plus(zero)).isEqualTo(min);
        assertThat(min.minus(zero)).isEqualTo(min);
    }

    @Test
    @DisplayName("Protects against arithmetic overflow with ArithmeticException")
    void protectsAgainstOverflow() {
        Money max = Money.ofMinor(Long.MAX_VALUE, USD);
        Money min = Money.ofMinor(Long.MIN_VALUE, USD);
        Money one = Money.ofMinor(1L, USD);

        assertThatThrownBy(() -> max.plus(one)).isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() -> min.minus(one)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("Correctly verifies value predicates")
    void verifiesValuePredicates() {
        Money positive = Money.ofMinor(100L, USD);
        Money zero = Money.zero(USD);
        Money negative = Money.ofMinor(-50L, USD);

        assertThat(positive.isPositive()).isTrue();
        assertThat(positive.isZeroOrPositive()).isTrue();
        assertThat(positive.isZero()).isFalse();
        assertThat(positive.isNegative()).isFalse();

        assertThat(zero.isPositive()).isFalse();
        assertThat(zero.isZeroOrPositive()).isTrue();
        assertThat(zero.isZero()).isTrue();
        assertThat(zero.isNegative()).isFalse();

        assertThat(negative.isPositive()).isFalse();
        assertThat(negative.isZeroOrPositive()).isFalse();
        assertThat(negative.isZero()).isFalse();
        assertThat(negative.isNegative()).isTrue();
    }

    @Test
    @DisplayName("Compares amounts covering true, false, and equal branches")
    void comparesAmountsCoveringAllBranches() {
        Money m100 = Money.ofMinor(100L, USD);
        Money m50 = Money.ofMinor(50L, USD);
        Money m100Copy = Money.ofMinor(100L, USD);

        // Greater than
        assertThat(m100.isGreaterThan(m50)).isTrue();
        assertThat(m50.isGreaterThan(m100)).isFalse();
        assertThat(m100.isGreaterThan(m100Copy)).isFalse();

        // Greater than or equal to
        assertThat(m100.isGreaterThanOrEqualTo(m50)).isTrue();
        assertThat(m100.isGreaterThanOrEqualTo(m100Copy)).isTrue();
        assertThat(m50.isGreaterThanOrEqualTo(m100)).isFalse();

        // Less than
        assertThat(m50.isLessThan(m100)).isTrue();
        assertThat(m100.isLessThan(m50)).isFalse();
        assertThat(m100.isLessThan(m100Copy)).isFalse();

        // Less than or equal to
        assertThat(m50.isLessThanOrEqualTo(m100)).isTrue();
        assertThat(m100.isLessThanOrEqualTo(m100Copy)).isTrue();
        assertThat(m100.isLessThanOrEqualTo(m50)).isFalse();

        // Comparable compareTo
        assertThat(m100.compareTo(m50)).isPositive();
        assertThat(m50.compareTo(m100)).isNegative();
        assertThat(m100.compareTo(m100Copy)).isZero();
    }

    @Test
    @DisplayName("Rejects comparisons on mismatched currencies across all comparison methods")
    void rejectsComparisonsOnMismatchedCurrencies() {
        Money usd = Money.ofMinor(100L, USD);
        Money eur = Money.ofMinor(100L, EUR);

        assertThatThrownBy(() -> usd.compareTo(eur)).isInstanceOf(CurrencyMismatchException.class);

        assertThatThrownBy(() -> usd.isGreaterThan(eur)).isInstanceOf(CurrencyMismatchException.class);

        assertThatThrownBy(() -> usd.isGreaterThanOrEqualTo(eur)).isInstanceOf(CurrencyMismatchException.class);

        assertThatThrownBy(() -> usd.isLessThan(eur)).isInstanceOf(CurrencyMismatchException.class);

        assertThatThrownBy(() -> usd.isLessThanOrEqualTo(eur)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("Rejects null operand across comparison methods")
    void rejectsNullInComparisons() {
        Money usd = Money.ofMinor(100L, USD);

        assertThatThrownBy(() -> usd.compareTo(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("Money operand must not be null");

        assertThatThrownBy(() -> usd.isGreaterThan(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("Money operand must not be null");

        assertThatThrownBy(() -> usd.isGreaterThanOrEqualTo(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("Money operand must not be null");

        assertThatThrownBy(() -> usd.isLessThan(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("Money operand must not be null");

        assertThatThrownBy(() -> usd.isLessThanOrEqualTo(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("Money operand must not be null");
    }

    @Test
    @DisplayName("Strictly adheres to equals and hashCode value contract")
    void adheresToValueEqualityAndHashCode() {
        Money usd100A = Money.ofMinor(100L, USD);
        Money usd100B = Money.ofMinor(100L, USD);
        Money usd200 = Money.ofMinor(200L, USD);
        Money eur100 = Money.ofMinor(100L, EUR);

        assertThat(usd100A).isEqualTo(usd100A);
        assertThat(usd100A).isEqualTo(usd100B);
        assertThat(usd100B).isEqualTo(usd100A);
        assertThat(usd100A.hashCode()).isEqualTo(usd100B.hashCode());
        assertThat(usd100A).isNotEqualTo(usd200);
        assertThat(usd100A).isNotEqualTo(eur100);
        assertThat(usd100A).isNotEqualTo(null);
        assertThat(usd100A).isNotEqualTo("100 USD");
    }

    @Test
    @DisplayName("Multiplies amount by integer factor with overflow protection")
    void multipliesAmountWithOverflowProtection() {
        Money money = Money.ofMinor(250L, USD);

        assertThat(money.multipliedBy(3L)).isEqualTo(Money.ofMinor(750L, USD));
        assertThat(money.multipliedBy(0L)).isEqualTo(Money.zero(USD));
        assertThat(money.multipliedBy(-2L)).isEqualTo(Money.ofMinor(-500L, USD));

        Money max = Money.ofMinor(Long.MAX_VALUE, USD);
        assertThatThrownBy(() -> max.multipliedBy(2L)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("Negates amount and calculates absolute value with overflow protection")
    void negatesAndAbsWithOverflowProtection() {
        Money positive = Money.ofMinor(100L, USD);
        Money negative = Money.ofMinor(-100L, USD);
        Money zero = Money.zero(USD);

        assertThat(positive.negated()).isEqualTo(negative);
        assertThat(negative.negated()).isEqualTo(positive);
        assertThat(zero.negated()).isEqualTo(zero);

        assertThat(positive.abs()).isEqualTo(positive);
        assertThat(negative.abs()).isEqualTo(positive);
        assertThat(zero.abs()).isEqualTo(zero);

        Money min = Money.ofMinor(Long.MIN_VALUE, USD);
        assertThatThrownBy(min::negated).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(min::abs).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("Parses standard money string formatted as '<amount> <currency>'")
    void parsesStandardMoneyString() {
        Money parsedUsd = Money.parse("10.50 USD");
        assertThat(parsedUsd).isEqualTo(Money.ofMinor(1050L, USD));

        Money parsedJpy = Money.parse("500 JPY");
        assertThat(parsedJpy).isEqualTo(Money.ofMinor(500L, JPY));

        Money parsedKwd = Money.parse("1.250 KWD");
        assertThat(parsedKwd).isEqualTo(Money.ofMinor(1250L, KWD));

        // Invalid format rejection
        assertThatThrownBy(() -> Money.parse(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Money.parse("10.50")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid money format");
        assertThatThrownBy(() -> Money.parse("USD 10.50")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Formats user-friendly string as '<amount> <currency>'")
    void formatsUserFriendlyString() {
        assertThat(Money.ofMinor(1050L, USD).toString()).isEqualTo("10.50 USD");
        assertThat(Money.ofMinor(500L, JPY).toString()).isEqualTo("500 JPY");
        assertThat(Money.ofMinor(1250L, KWD).toString()).isEqualTo("1.250 KWD");
        assertThat(Money.ofMinor(-50L, USD).toString()).isEqualTo("-0.50 USD");
    }
}
