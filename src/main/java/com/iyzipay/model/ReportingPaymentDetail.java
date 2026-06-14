package com.iyzipay.model;

import com.iyzipay.HttpClient;
import com.iyzipay.IyzipayResource;
import com.iyzipay.Options;
import com.iyzipay.Request;
import com.iyzipay.request.ReportingPaymentDetailRequest;
import com.iyzipay.utils.ReportingBoundaryProcessor;
import com.iyzipay.utils.TimeBoundaryUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class ReportingPaymentDetail extends IyzipayResource {

    private List<PaymentDetail> payments;

    public static ReportingPaymentDetail create(ReportingPaymentDetailRequest request, Options options) {
        String path = "/v2/reporting/payment/details";
        String uri = options.getBaseUrl() + path + getQueryParams(request);
        return HttpClient.create().get(uri,
                getHttpProxy(options),
                getHttpHeadersV2(path, null, options),
                null,
                ReportingPaymentDetail.class);
    }

    private static String getQueryParams(Request request) {
        if (request == null) {
            return "";
        }

        String queryParams = "?conversationId=" + request.getConversationId();

        if (StringUtils.isNotBlank(request.getLocale())) {
            queryParams += "&locale=" + request.getLocale();
        }

        if (request instanceof ReportingPaymentDetailRequest) {
            ReportingPaymentDetailRequest reportingPaymentDetailRequest = (ReportingPaymentDetailRequest) request;
            if (StringUtils.isNoneEmpty(reportingPaymentDetailRequest.getPaymentId())) {
                queryParams += "&paymentId=" + reportingPaymentDetailRequest.getPaymentId();
            }

            if (StringUtils.isNoneEmpty(reportingPaymentDetailRequest.getPaymentConversationId())) {
                queryParams += "&paymentConversationId=" + reportingPaymentDetailRequest.getPaymentConversationId();
            }
        }
        return queryParams;
    }

    public List<PaymentDetail> getPayments() {
        return payments;
    }

    public void setPayments(List<PaymentDetail> payments) {
        this.payments = payments;
    }

    public List<PaymentDetail> getPaymentsFilteredByDst(Date businessDate) {
        return getPaymentsFilteredByDst(businessDate, TimeZone.getTimeZone(TimeBoundaryUtils.TURKEY_TIMEZONE_ID));
    }

    public List<PaymentDetail> getPaymentsFilteredByDst(Date businessDate, TimeZone timeZone) {
        if (payments == null || payments.isEmpty() || businessDate == null) {
            return payments;
        }
        ReportingBoundaryProcessor processor = new ReportingBoundaryProcessor(timeZone);
        return processor.filterByEffectiveBoundary(payments, businessDate,
                new ReportingBoundaryProcessor.DateExtractor<PaymentDetail>() {
                    @Override
                    public Date extract(PaymentDetail item) {
                        return item.getCreatedDate();
                    }
                });
    }

    public Map<Date, List<PaymentDetail>> correctAndGroupByBusinessDate() {
        return correctAndGroupByBusinessDate(TimeZone.getTimeZone(TimeBoundaryUtils.TURKEY_TIMEZONE_ID));
    }

    public Map<Date, List<PaymentDetail>> correctAndGroupByBusinessDate(TimeZone timeZone) {
        if (payments == null || payments.isEmpty()) {
            return new java.util.TreeMap<Date, List<PaymentDetail>>();
        }
        ReportingBoundaryProcessor processor = new ReportingBoundaryProcessor(timeZone);
        return processor.correctCrossDayArchival(payments);
    }

    public ReportingBoundaryProcessor.ReconciliationResult reconcileWithTransactions(
            List<TransactionDetail> rawTransactions, Date businessDate) {
        return reconcileWithTransactions(rawTransactions, businessDate,
                TimeZone.getTimeZone(TimeBoundaryUtils.TURKEY_TIMEZONE_ID));
    }

    public ReportingBoundaryProcessor.ReconciliationResult reconcileWithTransactions(
            List<TransactionDetail> rawTransactions, Date businessDate, TimeZone timeZone) {
        ReportingBoundaryProcessor processor = new ReportingBoundaryProcessor(timeZone);
        return processor.reconcileWithRawTransactions(
                this.payments != null ? this.payments : new ArrayList<PaymentDetail>(),
                rawTransactions != null ? rawTransactions : new ArrayList<TransactionDetail>(),
                businessDate);
    }

    public List<String> validateAlignment(List<TransactionDetail> rawTransactions, Date businessDate) {
        return validateAlignment(rawTransactions, businessDate,
                TimeZone.getTimeZone(TimeBoundaryUtils.TURKEY_TIMEZONE_ID));
    }

    public List<String> validateAlignment(List<TransactionDetail> rawTransactions, Date businessDate, TimeZone timeZone) {
        ReportingBoundaryProcessor processor = new ReportingBoundaryProcessor(timeZone);
        return processor.validateAlignment(
                this.payments != null ? this.payments : new ArrayList<PaymentDetail>(),
                rawTransactions != null ? rawTransactions : new ArrayList<TransactionDetail>(),
                businessDate);
    }

    public boolean isDstTransitionDayInResult(TimeZone timeZone) {
        if (payments == null || payments.isEmpty()) {
            return false;
        }
        for (PaymentDetail pd : payments) {
            if (pd.getCreatedDate() != null && TimeBoundaryUtils.isDaylightSavingTransitionDay(pd.getCreatedDate(), timeZone)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.MULTI_LINE_STYLE);
    }

}

