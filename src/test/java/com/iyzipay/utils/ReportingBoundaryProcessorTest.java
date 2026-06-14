package com.iyzipay.utils;

import com.iyzipay.model.PaymentDetail;
import com.iyzipay.model.TransactionDetail;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class ReportingBoundaryProcessorTest {

    private static final TimeZone TEST_TZ = TimeZone.getTimeZone("Europe/Berlin");
    private ReportingBoundaryProcessor processor;

    @Before
    public void setUp() {
        processor = new ReportingBoundaryProcessor(TEST_TZ);
    }

    @Test
    public void shouldCalculateStandardDateBoundaries() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.JUNE, 15, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date normalDay = cal.getTime();

        ReportingBoundaryProcessor.DateBoundaryResult result = processor.calculateDateBoundaries(normalDay);

        assertNotNull(result);
        assertFalse(result.isDstTransitionDay());
        assertEquals("STANDARD", result.getBoundaryRule());
        assertEquals(0, result.getPaddingMillis());
        assertEquals(result.getStandardStart().getTime(), result.getEffectiveStart().getTime());
        assertEquals(result.getStandardEnd().getTime(), result.getEffectiveEnd().getTime());
    }

    @Test
    public void shouldCalculateSpringForwardDateBoundaries() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date springForwardDay = cal.getTime();

        ReportingBoundaryProcessor.DateBoundaryResult result = processor.calculateDateBoundaries(springForwardDay);

        assertNotNull(result);
        assertTrue(result.isDstTransitionDay());
        assertEquals("DST_SPRING_FORWARD", result.getBoundaryRule());
        assertEquals(3600000L, result.getPaddingMillis());
        assertEquals(result.getStandardStart().getTime(), result.getEffectiveStart().getTime());
        assertEquals(result.getStandardEnd().getTime() + 3600000L, result.getEffectiveEnd().getTime());
    }

    @Test
    public void shouldCalculateFallBackDateBoundaries() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.OCTOBER, 26, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date fallBackDay = cal.getTime();

        ReportingBoundaryProcessor.DateBoundaryResult result = processor.calculateDateBoundaries(fallBackDay);

        assertNotNull(result);
        assertTrue(result.isDstTransitionDay());
        assertEquals("DST_FALL_BACK", result.getBoundaryRule());
        assertEquals(3600000L, result.getPaddingMillis());
        assertEquals(result.getStandardStart().getTime() - 3600000L, result.getEffectiveStart().getTime());
        assertEquals(result.getStandardEnd().getTime(), result.getEffectiveEnd().getTime());
    }

    @Test
    public void shouldFilterPaymentsByEffectiveBoundaryOnSpringForward() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date springForwardDay = cal.getTime();

        List<PaymentDetail> payments = new ArrayList<PaymentDetail>();

        cal.set(2025, Calendar.MARCH, 30, 2, 0, 0);
        payments.add(createPaymentDetail(1L, cal.getTime(), new BigDecimal("100.00")));

        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        payments.add(createPaymentDetail(2L, cal.getTime(), new BigDecimal("200.00")));

        cal.set(2025, Calendar.MARCH, 30, 23, 59, 59);
        payments.add(createPaymentDetail(3L, cal.getTime(), new BigDecimal("300.00")));

        cal.set(2025, Calendar.MARCH, 31, 0, 59, 59);
        payments.add(createPaymentDetail(4L, cal.getTime(), new BigDecimal("400.00")));

        cal.set(2025, Calendar.MARCH, 29, 23, 59, 59);
        payments.add(createPaymentDetail(5L, cal.getTime(), new BigDecimal("500.00")));

        List<PaymentDetail> filtered = processor.filterByEffectiveBoundary(payments, springForwardDay,
                new ReportingBoundaryProcessor.DateExtractor<PaymentDetail>() {
                    @Override
                    public Date extract(PaymentDetail item) {
                        return item.getCreatedDate();
                    }
                });

        assertEquals("Spring forward with +1h padding should include 4 payments", 4, filtered.size());
    }

    @Test
    public void shouldCorrectCrossDayArchivalOnDstDays() {
        List<PaymentDetail> payments = new ArrayList<PaymentDetail>();

        Calendar cal = Calendar.getInstance(TEST_TZ);

        cal.set(2025, Calendar.MARCH, 30, 1, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);
        payments.add(createPaymentDetail(1L, cal.getTime(), new BigDecimal("100.00")));

        cal.set(2025, Calendar.MARCH, 30, 3, 30, 0);
        payments.add(createPaymentDetail(2L, cal.getTime(), new BigDecimal("200.00")));

        cal.set(2025, Calendar.OCTOBER, 26, 1, 30, 0);
        payments.add(createPaymentDetail(3L, cal.getTime(), new BigDecimal("300.00")));

        cal.set(2025, Calendar.OCTOBER, 26, 2, 30, 0);
        payments.add(createPaymentDetail(4L, cal.getTime(), new BigDecimal("400.00")));

        cal.set(2025, Calendar.JUNE, 15, 12, 0, 0);
        payments.add(createPaymentDetail(5L, cal.getTime(), new BigDecimal("500.00")));

        Map<Date, List<PaymentDetail>> grouped = processor.correctCrossDayArchival(payments);

        assertNotNull(grouped);
        assertTrue("Should have at least 3 different business dates", grouped.size() >= 3);
    }

    @Test
    public void shouldReconcileAndAlignReports() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.JUNE, 15, 12, 0, 0);
        Date businessDate = cal.getTime();

        List<PaymentDetail> reportPayments = new ArrayList<PaymentDetail>();
        cal.set(2025, Calendar.JUNE, 15, 10, 0, 0);
        reportPayments.add(createPaymentDetail(1L, cal.getTime(), new BigDecimal("100.00")));
        cal.set(2025, Calendar.JUNE, 15, 15, 0, 0);
        reportPayments.add(createPaymentDetail(2L, cal.getTime(), new BigDecimal("200.00")));

        List<TransactionDetail> rawTransactions = new ArrayList<TransactionDetail>();
        rawTransactions.add(createTransactionDetail(1L, "2025-06-15 10:00:00", new BigDecimal("100.00")));
        rawTransactions.add(createTransactionDetail(2L, "2025-06-15 15:00:00", new BigDecimal("200.00")));

        ReportingBoundaryProcessor.ReconciliationResult result =
                processor.reconcileWithRawTransactions(reportPayments, rawTransactions, businessDate);

        assertNotNull(result);
        assertEquals(2, result.getMatchedCount());
        assertTrue(result.getInReportNotInRaw().isEmpty());
        assertTrue(result.getInRawNotInReport().isEmpty());
        assertEquals(new BigDecimal("300.00"), result.getReportTotal());
        assertEquals(new BigDecimal("300.00"), result.getRawTotal());
        assertTrue(result.isAligned());
    }

    @Test
    public void shouldDetectMisalignedReports() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.JUNE, 15, 12, 0, 0);
        Date businessDate = cal.getTime();

        List<PaymentDetail> reportPayments = new ArrayList<PaymentDetail>();
        cal.set(2025, Calendar.JUNE, 15, 10, 0, 0);
        reportPayments.add(createPaymentDetail(1L, cal.getTime(), new BigDecimal("100.00")));
        cal.set(2025, Calendar.JUNE, 15, 15, 0, 0);
        reportPayments.add(createPaymentDetail(2L, cal.getTime(), new BigDecimal("200.00")));

        List<TransactionDetail> rawTransactions = new ArrayList<TransactionDetail>();
        rawTransactions.add(createTransactionDetail(1L, "2025-06-15 10:00:00", new BigDecimal("100.00")));
        rawTransactions.add(createTransactionDetail(3L, "2025-06-15 18:00:00", new BigDecimal("300.00")));

        List<String> errors = processor.validateAlignment(reportPayments, rawTransactions, businessDate);

        assertNotNull(errors);
        assertFalse("Should detect misalignment errors", errors.isEmpty());
    }

    private PaymentDetail createPaymentDetail(Long id, Date createdDate, BigDecimal paidPrice) {
        PaymentDetail pd = new PaymentDetail();
        pd.setPaymentId(id);
        pd.setCreatedDate(createdDate);
        pd.setPaidPrice(paidPrice);
        pd.setPaymentStatus(1);
        return pd;
    }

    private TransactionDetail createTransactionDetail(Long paymentId, String transactionDate, BigDecimal paidPrice) {
        TransactionDetail td = new TransactionDetail();
        td.setPaymentId(paymentId);
        td.setTransactionDate(transactionDate);
        td.setPaidPrice(paidPrice);
        td.setTransactionStatus(1);
        return td;
    }
}
