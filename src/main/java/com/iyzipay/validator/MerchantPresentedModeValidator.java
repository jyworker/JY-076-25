package com.iyzipay.validator;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

public class MerchantPresentedModeValidator {

    private static final Pattern TRANSACTION_AMOUNT_PATTERN = Pattern.compile("^\\d{1,13}(\\.\\d{1,5})?$");

    private MerchantPresentedModeValidator() {
    }

    public static boolean validateTransactionAmount(String transactionAmount) {
        if (StringUtils.isBlank(transactionAmount)) {
            return false;
        }
        return TRANSACTION_AMOUNT_PATTERN.matcher(transactionAmount).matches();
    }
}
