package com.iyzipay.utils;

import com.iyzipay.model.PaymentDetail;
import com.iyzipay.model.PaymentTxDetail;
import com.iyzipay.model.RefundDetail;
import com.iyzipay.model.RefundTxDetail;
import com.iyzipay.model.TransactionDetail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

public class ReportingBoundaryProcessor {

    private final TimeZone reportingTimeZone;

    public ReportingBoundaryProcessor() {
        this(TimeZone.getTimeZone(TimeBoundaryUtils.TURKEY_TIMEZONE_ID));
    }

    public ReportingBoundaryProcessor(TimeZone reportingTimeZone) {
        this.reportingTimeZone = reportingTimeZone;
    }

    public DateBoundaryResult calculateDateBoundaries(Date businessDate) {
        Date normalizedDate = TimeBoundaryUtils.getStartOfDay(businessDate, reportingTimeZone);
        Date standardStart = normalizedDate;
        Date standardEnd = TimeBoundaryUtils.getEndOfDay(businessDate, reportingTimeZone);

        boolean isDstDay = TimeBoundaryUtils.isDaylightSavingTransitionDay(businessDate, reportingTimeZone);
        TimeBoundaryUtils.DstTransitionInfo dstInfo = TimeBoundaryUtils.getDstTransitionInfo(businessDate, reportingTimeZone);

        Date effectiveStart = standardStart;
        Date effectiveEnd = standardEnd;
        long paddingMillis = 0;
        String boundaryRule = "STANDARD";

        if (isDstDay) {
            if (dstInfo.isSpringForward()) {
                effectiveEnd = new Date(standardEnd.getTime() + Math.abs(dstInfo.getOffsetDiffMillis()));
                paddingMillis = Math.abs(dstInfo.getOffsetDiffMillis());
                boundaryRule = "DST_SPRING_FORWARD";
            } else {
                effectiveStart = new Date(standardStart.getTime() - Math.abs(dstInfo.getOffsetDiffMillis()));
                paddingMillis = Math.abs(dstInfo.getOffsetDiffMillis());
                boundaryRule = "DST_FALL_BACK";
            }
        }

        return new DateBoundaryResult(
                businessDate,
                standardStart,
                standardEnd,
                effectiveStart,
                effectiveEnd,
                isDstDay,
                dstInfo,
                paddingMillis,
                boundaryRule,
                reportingTimeZone.getID()
        );
    }

    public <T> List<T> filterByEffectiveBoundary(List<T> transactions, Date businessDate, DateExtractor<T> extractor) {
        if (transactions == null || transactions.isEmpty()) {
            return Collections.emptyList();
        }

        DateBoundaryResult boundaries = calculateDateBoundaries(businessDate);
        List<T> result = new ArrayList<T>();

        for (T tx : transactions) {
            Date txDate = extractor.extract(tx);
            if (txDate != null && TimeBoundaryUtils.isTransactionInDateRange(txDate, boundaries.getEffectiveStart(), boundaries.getEffectiveEnd())) {
                result.add(tx);
            }
        }

        return result;
    }

    public Map<Date, List<PaymentDetail>> correctCrossDayArchival(List<PaymentDetail> allPayments) {
        if (allPayments == null || allPayments.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Date, List<PaymentDetail>> result = new TreeMap<Date, List<PaymentDetail>>(new Comparator<Date>() {
            @Override
            public int compare(Date d1, Date d2) {
                return d1.compareTo(d2);
            }
        });

        for (PaymentDetail payment : allPayments) {
            Date createdDate = payment.getCreatedDate();
            Date updatedDate = payment.getUpdatedDate();

            if (createdDate == null) {
                continue;
            }

            Date businessDate = TimeBoundaryUtils.normalizeToBusinessDate(createdDate, createdDate, reportingTimeZone);

            if (updatedDate != null && !TimeBoundaryUtils.isSameDay(createdDate, updatedDate, reportingTimeZone)) {
                if (TimeBoundaryUtils.isDaylightSavingTransitionDay(createdDate, reportingTimeZone)
                        || TimeBoundaryUtils.isDaylightSavingTransitionDay(updatedDate, reportingTimeZone)) {
                    Date correctedDate = resolveCrossDayPayment(payment);
                    if (correctedDate != null) {
                        businessDate = correctedDate;
                    }
                }
            }

            Date key = TimeBoundaryUtils.getStartOfDay(businessDate, reportingTimeZone);
            if (!result.containsKey(key)) {
                result.put(key, new ArrayList<PaymentDetail>());
            }
            result.get(key).add(payment);
        }

        return result;
    }

    private Date resolveCrossDayPayment(PaymentDetail payment) {
        Date createdDate = payment.getCreatedDate();
        Date updatedDate = payment.getUpdatedDate();

        if (createdDate == null) {
            return null;
        }

        long createdHour = getHourOfDay(createdDate);
        long updatedHour = getHourOfDay(updatedDate);

        if (TimeBoundaryUtils.isDstAffectedHour(createdDate, reportingTimeZone)
                || TimeBoundaryUtils.isDstAffectedHour(updatedDate, reportingTimeZone)) {
            Date txDate = createdDate;
            if (payment.getItemTransactions() != null && !payment.getItemTransactions().isEmpty()) {
                Date firstTxDate = null;
                for (PaymentTxDetail tx : payment.getItemTransactions()) {
                    Date blockDate = tx.getBlockageResolvedDate();
                    if (blockDate != null && (firstTxDate == null || blockDate.before(firstTxDate))) {
                        firstTxDate = blockDate;
                    }
                }
                if (firstTxDate != null) {
                    txDate = firstTxDate;
                }
            }
            return TimeBoundaryUtils.getStartOfDay(txDate, reportingTimeZone);
        }

        return TimeBoundaryUtils.getStartOfDay(createdDate, reportingTimeZone);
    }

    public ReconciliationResult reconcileWithRawTransactions(
            List<PaymentDetail> reportedPayments,
            List<TransactionDetail> rawTransactions,
            Date businessDate) {

        DateBoundaryResult boundaries = calculateDateBoundaries(businessDate);
        ReconciliationResult result = new ReconciliationResult(businessDate, boundaries);

        Map<Long, PaymentDetail> reportedById = new HashMap<Long, PaymentDetail>();
        for (PaymentDetail pd : reportedPayments) {
            if (pd.getPaymentId() != null) {
                reportedById.put(pd.getPaymentId(), pd);
            }
        }

        Map<Long, TransactionDetail> rawById = new HashMap<Long, TransactionDetail>();
        for (TransactionDetail td : rawTransactions) {
            if (td.getPaymentId() != null) {
                rawById.put(td.getPaymentId(), td);
            }
        }

        List<PaymentDetail> matched = new ArrayList<PaymentDetail>();
        List<PaymentDetail> inReportNotInRaw = new ArrayList<PaymentDetail>();
        List<TransactionDetail> inRawNotInReport = new ArrayList<TransactionDetail>();

        for (Map.Entry<Long, PaymentDetail> entry : reportedById.entrySet()) {
            if (rawById.containsKey(entry.getKey())) {
                matched.add(entry.getValue());
            } else {
                inReportNotInRaw.add(entry.getValue());
            }
        }

        for (Map.Entry<Long, TransactionDetail> entry : rawById.entrySet()) {
            if (!reportedById.containsKey(entry.getKey())) {
                TransactionDetail td = entry.getValue();
                String txDateStr = td.getTransactionDate();
                if (txDateStr != null) {
                    try {
                        Date txDate = com.iyzipay.GsonProvider.getDateFormat().parse(txDateStr);
                        if (TimeBoundaryUtils.isTransactionInDateRange(txDate, boundaries.getEffectiveStart(), boundaries.getEffectiveEnd())) {
                            inRawNotInReport.add(td);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        result.setMatchedCount(matched.size());
        result.setInReportNotInRaw(inReportNotInRaw);
        result.setInRawNotInReport(inRawNotInReport);
        result.setReportTotal(calculateTotal(reportedPayments));
        result.setRawTotal(calculateTotalFromTx(rawTransactions));
        result.setAligned(inReportNotInRaw.isEmpty() && inRawNotInReport.isEmpty());

        return result;
    }

    public List<String> validateAlignment(List<PaymentDetail> reportedPayments,
                                           List<TransactionDetail> rawTransactions,
                                           Date businessDate) {
        List<String> errors = new ArrayList<String>();
        ReconciliationResult recon = reconcileWithRawTransactions(reportedPayments, rawTransactions, businessDate);

        if (!recon.isAligned()) {
            if (!recon.getInReportNotInRaw().isEmpty()) {
                errors.add(String.format("报表中存在但原始交易中缺失的记录数: %d", recon.getInReportNotInRaw().size()));
                for (PaymentDetail pd : recon.getInReportNotInRaw()) {
                    errors.add(String.format("  - paymentId: %s, createdDate: %s",
                            pd.getPaymentId(),
                            pd.getCreatedDate() != null ? TimeBoundaryUtils.formatDate(pd.getCreatedDate(), TimeBoundaryUtils.DEFAULT_DATE_FORMAT, reportingTimeZone) : "null"));
                }
            }
            if (!recon.getInRawNotInReport().isEmpty()) {
                errors.add(String.format("原始交易中存在但报表中缺失的记录数: %d", recon.getInRawNotInReport().size()));
                for (TransactionDetail td : recon.getInRawNotInReport()) {
                    errors.add(String.format("  - paymentId: %s, transactionDate: %s",
                            td.getPaymentId(), td.getTransactionDate()));
                }
            }
        }

        if (recon.getReportTotal().compareTo(recon.getRawTotal()) != 0) {
            errors.add(String.format("金额不匹配: 报表合计=%s, 原始合计=%s, 差值=%s",
                    recon.getReportTotal(), recon.getRawTotal(),
                    recon.getReportTotal().subtract(recon.getRawTotal())));
        }

        return errors;
    }

    private long getHourOfDay(Date date) {
        if (date == null) {
            return -1;
        }
        Calendar cal = Calendar.getInstance(reportingTimeZone);
        cal.setTime(date);
        return cal.get(Calendar.HOUR_OF_DAY);
    }

    private BigDecimal calculateTotal(List<PaymentDetail> payments) {
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentDetail pd : payments) {
            if (pd.getPaidPrice() != null) {
                total = total.add(pd.getPaidPrice());
            }
        }
        return total;
    }

    private BigDecimal calculateTotalFromTx(List<TransactionDetail> txs) {
        BigDecimal total = BigDecimal.ZERO;
        for (TransactionDetail td : txs) {
            if (td.getPaidPrice() != null) {
                total = total.add(td.getPaidPrice());
            }
        }
        return total;
    }

    public interface DateExtractor<T> {
        Date extract(T item);
    }

    public static class DateBoundaryResult {
        private final Date businessDate;
        private final Date standardStart;
        private final Date standardEnd;
        private final Date effectiveStart;
        private final Date effectiveEnd;
        private final boolean dstTransitionDay;
        private final TimeBoundaryUtils.DstTransitionInfo dstTransitionInfo;
        private final long paddingMillis;
        private final String boundaryRule;
        private final String timeZoneId;

        public DateBoundaryResult(Date businessDate, Date standardStart, Date standardEnd,
                                   Date effectiveStart, Date effectiveEnd, boolean dstTransitionDay,
                                   TimeBoundaryUtils.DstTransitionInfo dstTransitionInfo, long paddingMillis,
                                   String boundaryRule, String timeZoneId) {
            this.businessDate = businessDate;
            this.standardStart = standardStart;
            this.standardEnd = standardEnd;
            this.effectiveStart = effectiveStart;
            this.effectiveEnd = effectiveEnd;
            this.dstTransitionDay = dstTransitionDay;
            this.dstTransitionInfo = dstTransitionInfo;
            this.paddingMillis = paddingMillis;
            this.boundaryRule = boundaryRule;
            this.timeZoneId = timeZoneId;
        }

        public Date getBusinessDate() { return businessDate; }
        public Date getStandardStart() { return standardStart; }
        public Date getStandardEnd() { return standardEnd; }
        public Date getEffectiveStart() { return effectiveStart; }
        public Date getEffectiveEnd() { return effectiveEnd; }
        public boolean isDstTransitionDay() { return dstTransitionDay; }
        public TimeBoundaryUtils.DstTransitionInfo getDstTransitionInfo() { return dstTransitionInfo; }
        public long getPaddingMillis() { return paddingMillis; }
        public String getBoundaryRule() { return boundaryRule; }
        public String getTimeZoneId() { return timeZoneId; }
    }

    public static class ReconciliationResult {
        private final Date businessDate;
        private final DateBoundaryResult boundaries;
        private int matchedCount;
        private List<PaymentDetail> inReportNotInRaw;
        private List<TransactionDetail> inRawNotInReport;
        private BigDecimal reportTotal;
        private BigDecimal rawTotal;
        private boolean aligned;

        public ReconciliationResult(Date businessDate, DateBoundaryResult boundaries) {
            this.businessDate = businessDate;
            this.boundaries = boundaries;
            this.inReportNotInRaw = Collections.emptyList();
            this.inRawNotInReport = Collections.emptyList();
            this.reportTotal = BigDecimal.ZERO;
            this.rawTotal = BigDecimal.ZERO;
        }

        public Date getBusinessDate() { return businessDate; }
        public DateBoundaryResult getBoundaries() { return boundaries; }
        public int getMatchedCount() { return matchedCount; }
        public void setMatchedCount(int matchedCount) { this.matchedCount = matchedCount; }
        public List<PaymentDetail> getInReportNotInRaw() { return inReportNotInRaw; }
        public void setInReportNotInRaw(List<PaymentDetail> inReportNotInRaw) { this.inReportNotInRaw = inReportNotInRaw; }
        public List<TransactionDetail> getInRawNotInReport() { return inRawNotInReport; }
        public void setInRawNotInReport(List<TransactionDetail> inRawNotInReport) { this.inRawNotInReport = inRawNotInReport; }
        public BigDecimal getReportTotal() { return reportTotal; }
        public void setReportTotal(BigDecimal reportTotal) { this.reportTotal = reportTotal; }
        public BigDecimal getRawTotal() { return rawTotal; }
        public void setRawTotal(BigDecimal rawTotal) { this.rawTotal = rawTotal; }
        public boolean isAligned() { return aligned; }
        public void setAligned(boolean aligned) { this.aligned = aligned; }
    }
}
