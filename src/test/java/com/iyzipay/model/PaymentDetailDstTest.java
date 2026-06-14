package com.iyzipay.model;

import com.iyzipay.utils.TimeBoundaryUtils;
import org.junit.Test;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class PaymentDetailDstTest {

    private static final TimeZone TEST_TZ = TimeZone.getTimeZone("Europe/Berlin");

    @Test
    public void shouldReturnBusinessDateForNormalDay() {
        PaymentDetail pd = new PaymentDetail();
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.JUNE, 15, 14, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);
        pd.setCreatedDate(cal.getTime());

        Date businessDate = pd.getBusinessDate(TEST_TZ);
        assertNotNull(businessDate);

        Calendar resultCal = Calendar.getInstance(TEST_TZ);
        resultCal.setTime(businessDate);
        assertEquals(2025, resultCal.get(Calendar.YEAR));
        assertEquals(Calendar.JUNE, resultCal.get(Calendar.MONTH));
        assertEquals(15, resultCal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void shouldReturnBusinessDateForSpringForwardDay() {
        PaymentDetail pd = new PaymentDetail();
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 2, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);
        pd.setCreatedDate(cal.getTime());

        Date businessDate = pd.getBusinessDate(TEST_TZ);
        assertNotNull(businessDate);

        Calendar resultCal = Calendar.getInstance(TEST_TZ);
        resultCal.setTime(businessDate);
        assertEquals(2025, resultCal.get(Calendar.YEAR));
        assertEquals(Calendar.MARCH, resultCal.get(Calendar.MONTH));
        assertEquals(30, resultCal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void shouldReturnBusinessDateForFallBackDay() {
        PaymentDetail pd = new PaymentDetail();
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.OCTOBER, 26, 2, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);
        pd.setCreatedDate(cal.getTime());

        Date businessDate = pd.getBusinessDate(TEST_TZ);
        assertNotNull(businessDate);
    }

    @Test
    public void shouldFormatBusinessDateString() {
        PaymentDetail pd = new PaymentDetail();
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        pd.setCreatedDate(cal.getTime());

        String dateStr = TimeBoundaryUtils.formatDate(
                pd.getBusinessDate(TEST_TZ), TimeBoundaryUtils.DATE_ONLY_FORMAT, TEST_TZ);
        assertNotNull(dateStr);
        assertEquals("2025-03-30", dateStr);
    }

    @Test
    public void shouldDetectDstAffectedDay() {
        PaymentDetail dstDay = new PaymentDetail();
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        dstDay.setCreatedDate(cal.getTime());
        assertTrue(dstDay.isDstAffected(TEST_TZ));

        PaymentDetail normalDay = new PaymentDetail();
        cal.set(2025, Calendar.JUNE, 15, 12, 0, 0);
        normalDay.setCreatedDate(cal.getTime());
        assertFalse(normalDay.isDstAffected(TEST_TZ));
    }

    @Test
    public void shouldDetectDstAffectedHour() {
        PaymentDetail dstHour = new PaymentDetail();
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 2, 30, 0);
        dstHour.setCreatedDate(cal.getTime());
        assertTrue(dstHour.isDstAffectedTransaction(TEST_TZ));

        PaymentDetail normalHour = new PaymentDetail();
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        normalHour.setCreatedDate(cal.getTime());
        assertFalse(normalHour.isDstAffectedTransaction(TEST_TZ));

        PaymentDetail nonDstDay = new PaymentDetail();
        cal.set(2025, Calendar.JUNE, 15, 2, 0, 0);
        nonDstDay.setCreatedDate(cal.getTime());
        assertFalse(nonDstDay.isDstAffectedTransaction(TEST_TZ));
    }

    @Test
    public void shouldReturnNullWhenNoCreatedDate() {
        PaymentDetail pd = new PaymentDetail();
        assertNull(pd.getBusinessDate(TEST_TZ));
        assertNull(pd.getBusinessDateString(TEST_TZ));
        assertFalse(pd.isDstAffected(TEST_TZ));
        assertFalse(pd.isDstAffectedTransaction(TEST_TZ));
    }
}
