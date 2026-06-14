package com.iyzipay.utils;

import org.junit.Test;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class TimeBoundaryUtilsTest {

    private static final TimeZone TEST_TZ = TimeZone.getTimeZone("Europe/Berlin");
    private static final TimeZone UTC_TZ = TimeZone.getTimeZone("UTC");

    @Test
    public void shouldDetectSpringForwardDstDay() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date springForwardDay = cal.getTime();

        assertTrue("March 30, 2025 should be DST transition day in Berlin",
                TimeBoundaryUtils.isDaylightSavingTransitionDay(springForwardDay, TEST_TZ));

        TimeBoundaryUtils.DstTransitionInfo info =
                TimeBoundaryUtils.getDstTransitionInfo(springForwardDay, TEST_TZ);

        assertTrue(info.isTransitionDay());
        assertTrue("Spring forward should have positive offset diff", info.isSpringForward());
        assertFalse(info.isFallBack());
        assertEquals(1, info.getOffsetDiffHours());
    }

    @Test
    public void shouldDetectFallBackDstDay() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.OCTOBER, 26, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date fallBackDay = cal.getTime();

        assertTrue("October 26, 2025 should be DST transition day in Berlin",
                TimeBoundaryUtils.isDaylightSavingTransitionDay(fallBackDay, TEST_TZ));

        TimeBoundaryUtils.DstTransitionInfo info =
                TimeBoundaryUtils.getDstTransitionInfo(fallBackDay, TEST_TZ);

        assertTrue(info.isTransitionDay());
        assertTrue("Fall back should have negative offset diff", info.isFallBack());
        assertFalse(info.isSpringForward());
        assertEquals(-1, info.getOffsetDiffHours());
    }

    @Test
    public void shouldNotDetectNormalDayAsDst() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.JUNE, 15, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date normalDay = cal.getTime();

        assertFalse("Normal day should not be DST transition day",
                TimeBoundaryUtils.isDaylightSavingTransitionDay(normalDay, TEST_TZ));

        TimeBoundaryUtils.DstTransitionInfo info =
                TimeBoundaryUtils.getDstTransitionInfo(normalDay, TEST_TZ);

        assertFalse(info.isTransitionDay());
        assertEquals(0, info.getOffsetDiffHours());
    }

    @Test
    public void shouldCorrectlyCalculateStartOfDay() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 14, 30, 45);
        cal.set(Calendar.MILLISECOND, 123);
        Date input = cal.getTime();

        Date startOfDay = TimeBoundaryUtils.getStartOfDay(input, TEST_TZ);

        Calendar resultCal = Calendar.getInstance(TEST_TZ);
        resultCal.setTime(startOfDay);
        assertEquals(2025, resultCal.get(Calendar.YEAR));
        assertEquals(Calendar.MARCH, resultCal.get(Calendar.MONTH));
        assertEquals(30, resultCal.get(Calendar.DAY_OF_MONTH));
        assertEquals(0, resultCal.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, resultCal.get(Calendar.MINUTE));
        assertEquals(0, resultCal.get(Calendar.SECOND));
        assertEquals(0, resultCal.get(Calendar.MILLISECOND));
    }

    @Test
    public void shouldCorrectlyCalculateEndOfDay() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.OCTOBER, 26, 8, 15, 30);
        Date input = cal.getTime();

        Date endOfDay = TimeBoundaryUtils.getEndOfDay(input, TEST_TZ);

        Calendar resultCal = Calendar.getInstance(TEST_TZ);
        resultCal.setTime(endOfDay);
        assertEquals(2025, resultCal.get(Calendar.YEAR));
        assertEquals(Calendar.OCTOBER, resultCal.get(Calendar.MONTH));
        assertEquals(26, resultCal.get(Calendar.DAY_OF_MONTH));
        assertEquals(23, resultCal.get(Calendar.HOUR_OF_DAY));
        assertEquals(59, resultCal.get(Calendar.MINUTE));
        assertEquals(59, resultCal.get(Calendar.SECOND));
        assertEquals(999, resultCal.get(Calendar.MILLISECOND));
    }

    @Test
    public void shouldGetSpringForwardArchivalRangeWithPadding() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date springForwardDay = cal.getTime();

        Date[] range = TimeBoundaryUtils.getArchivalDateRangeWithDstCorrection(springForwardDay, TEST_TZ);

        assertNotNull(range);
        assertEquals(2, range.length);

        Date standardStart = TimeBoundaryUtils.getStartOfDay(springForwardDay, TEST_TZ);
        Date standardEnd = TimeBoundaryUtils.getEndOfDay(springForwardDay, TEST_TZ);
        long expectedPadding = 3600000L;

        assertEquals(standardStart.getTime(), range[0].getTime());
        assertEquals("Spring forward should extend end boundary by 1 hour",
                standardEnd.getTime() + expectedPadding, range[1].getTime());
    }

    @Test
    public void shouldGetFallBackArchivalRangeWithPadding() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.OCTOBER, 26, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date fallBackDay = cal.getTime();

        Date[] range = TimeBoundaryUtils.getArchivalDateRangeWithDstCorrection(fallBackDay, TEST_TZ);

        assertNotNull(range);
        assertEquals(2, range.length);

        Date standardStart = TimeBoundaryUtils.getStartOfDay(fallBackDay, TEST_TZ);
        long expectedPadding = 3600000L;

        assertEquals("Fall back should extend start boundary by 1 hour",
                standardStart.getTime() - expectedPadding, range[0].getTime());
    }

    @Test
    public void shouldNormalizeBusinessDateOnSpringForwardDay() {
        Calendar createdCal = Calendar.getInstance(TEST_TZ);
        createdCal.set(2025, Calendar.MARCH, 30, 2, 30, 0);
        createdCal.set(Calendar.MILLISECOND, 0);
        Date transactionDate = createdCal.getTime();

        Date businessDate = TimeBoundaryUtils.normalizeToBusinessDate(
                transactionDate, transactionDate, TEST_TZ);

        Calendar expectedCal = Calendar.getInstance(TEST_TZ);
        expectedCal.set(2025, Calendar.MARCH, 30, 0, 0, 0);
        expectedCal.set(Calendar.MILLISECOND, 0);

        assertEquals(expectedCal.getTimeInMillis(), businessDate.getTime());
    }

    @Test
    public void shouldNormalizeBusinessDateOnFallBackDay() {
        Calendar createdCal = Calendar.getInstance(TEST_TZ);
        createdCal.set(2025, Calendar.OCTOBER, 26, 2, 30, 0);
        createdCal.set(Calendar.MILLISECOND, 0);
        Date transactionDate = createdCal.getTime();

        Date businessDate = TimeBoundaryUtils.normalizeToBusinessDate(
                transactionDate, transactionDate, TEST_TZ);

        assertNotNull(businessDate);
    }

    @Test
    public void shouldFormatDateOnly() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 15, 45, 30);
        Date date = cal.getTime();

        String formatted = TimeBoundaryUtils.formatDate(date, TimeBoundaryUtils.DATE_ONLY_FORMAT, TEST_TZ);

        assertEquals("2025-03-30", formatted);
    }

    @Test
    public void shouldCheckSameDay() {
        Calendar cal1 = Calendar.getInstance(TEST_TZ);
        cal1.set(2025, Calendar.MARCH, 30, 2, 0, 0);
        Date d1 = cal1.getTime();

        Calendar cal2 = Calendar.getInstance(TEST_TZ);
        cal2.set(2025, Calendar.MARCH, 30, 23, 59, 59);
        Date d2 = cal2.getTime();

        Calendar cal3 = Calendar.getInstance(TEST_TZ);
        cal3.set(2025, Calendar.MARCH, 31, 0, 0, 1);
        Date d3 = cal3.getTime();

        assertTrue(TimeBoundaryUtils.isSameDay(d1, d2, TEST_TZ));
        assertFalse(TimeBoundaryUtils.isSameDay(d1, d3, TEST_TZ));
    }

    @Test
    public void shouldCheckTransactionInDateRange() {
        Calendar cal = Calendar.getInstance(UTC_TZ);
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        Date tx = cal.getTime();

        cal.set(2025, Calendar.MARCH, 30, 0, 0, 0);
        Date start = cal.getTime();

        cal.set(2025, Calendar.MARCH, 30, 23, 59, 59);
        Date end = cal.getTime();

        assertTrue(TimeBoundaryUtils.isTransactionInDateRange(tx, start, end));

        cal.set(2025, Calendar.MARCH, 29, 23, 59, 59);
        Date before = cal.getTime();
        assertFalse(TimeBoundaryUtils.isTransactionInDateRange(before, start, end));

        cal.set(2025, Calendar.MARCH, 31, 0, 0, 0);
        Date after = cal.getTime();
        assertFalse(TimeBoundaryUtils.isTransactionInDateRange(after, start, end));
    }

    @Test
    public void shouldDetectDstAffectedHour() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 2, 30, 0);
        Date dstHour = cal.getTime();
        assertTrue(TimeBoundaryUtils.isDstAffectedHour(dstHour, TEST_TZ));

        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        Date normalHour = cal.getTime();
        assertFalse(TimeBoundaryUtils.isDstAffectedHour(normalHour, TEST_TZ));

        cal.set(2025, Calendar.JUNE, 15, 2, 0, 0);
        Date nonDstDay = cal.getTime();
        assertFalse(TimeBoundaryUtils.isDstAffectedHour(nonDstDay, TEST_TZ));
    }

    @Test
    public void shouldGetDayDurationForDstDays() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        Date springForwardDay = cal.getTime();
        long springDuration = TimeBoundaryUtils.getDayDurationMillis(springForwardDay, TEST_TZ);
        assertEquals("Spring forward day should have 23 hours", 23 * 3600000L, springDuration);

        cal.set(2025, Calendar.OCTOBER, 26, 12, 0, 0);
        Date fallBackDay = cal.getTime();
        long fallDuration = TimeBoundaryUtils.getDayDurationMillis(fallBackDay, TEST_TZ);
        assertEquals("Fall back day should have 25 hours", 25 * 3600000L, fallDuration);

        cal.set(2025, Calendar.JUNE, 15, 12, 0, 0);
        Date normalDay = cal.getTime();
        long normalDuration = TimeBoundaryUtils.getDayDurationMillis(normalDay, TEST_TZ);
        assertEquals("Normal day should have 24 hours", 24 * 3600000L, normalDuration);
    }
}
