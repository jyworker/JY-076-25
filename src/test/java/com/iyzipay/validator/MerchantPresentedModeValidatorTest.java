package com.iyzipay.validator;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class MerchantPresentedModeValidatorTest {

    private final String transactionAmount;
    private final boolean expectedResult;

    public MerchantPresentedModeValidatorTest(String transactionAmount, boolean expectedResult) {
        this.transactionAmount = transactionAmount;
        this.expectedResult = expectedResult;
    }

    @Parameterized.Parameters(name = "{index}: transactionAmount=\"{0}\" should be valid={1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"1", true},
                {"100", true},
                {"999", true},
                {"1234567890123", true},
                {"1.0", true},
                {"100.5", true},
                {"0.1", true},
                {"123.1", true},
                {"123.12", true},
                {"123.123", true},
                {"123.1234", true},
                {"123.12345", true},
                {"9999999999999.99999", true},

                {"100.", false},
                {"1E2", false},
                {"1e2", false},
                {"1.5E2", false},
                {"1E+2", false},
                {"1E-2", false},
                {null, false},
                {"", false},
                {" ", false},
                {"  ", false},
                {"-1", false},
                {"-100.5", false},
                {"12345678901234", false},
                {"1.123456", false},
                {"123.123456", false},
                {"abc", false},
                {"1a", false},
                {"1.2a", false},
                {".1", false},
                {"..1", false},
                {"1..1", false},
                {"1.1.", false},
                {" 100", false},
                {"100 ", false},
                {"+100", false},
                {"0x10", false},
        });
    }

    @Test
    public void should_validate_transaction_amount() {
        assertEquals(expectedResult, MerchantPresentedModeValidator.validateTransactionAmount(transactionAmount));
    }
}
