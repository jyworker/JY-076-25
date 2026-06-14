package com.iyzipay.utils;

import com.iyzipay.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class DstCompensationSchedulerTest {

    private static final TimeZone TEST_TZ = TimeZone.getTimeZone("Europe/Berlin");
    private DstCompensationScheduler scheduler;

    @Before
    public void setUp() {
        Options options = new Options();
        options.setApiKey("test-api-key");
        options.setSecretKey("test-secret-key");
        options.setBaseUrl("https://sandbox-api.iyzipay.com");
        scheduler = new DstCompensationScheduler(options, TEST_TZ);
    }

    @After
    public void tearDown() {
        scheduler.shutdown();
    }

    @Test
    public void shouldFindDstTransitionDatesInRange() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.JANUARY, 1, 0, 0, 0);
        Date rangeStart = cal.getTime();

        cal.set(2025, Calendar.DECEMBER, 31, 23, 59, 59);
        Date rangeEnd = cal.getTime();

        Date[] transitions = scheduler.findDstTransitionDatesInRange(rangeStart, rangeEnd);

        assertNotNull(transitions);
        assertEquals("2025 should have 2 DST transitions in Berlin", 2, transitions.length);

        Calendar marchCal = Calendar.getInstance(TEST_TZ);
        marchCal.setTime(transitions[0]);
        assertEquals("First transition should be in March", Calendar.MARCH, marchCal.get(Calendar.MONTH));
        assertEquals(30, marchCal.get(Calendar.DAY_OF_MONTH));

        Calendar octCal = Calendar.getInstance(TEST_TZ);
        octCal.setTime(transitions[1]);
        assertEquals("Second transition should be in October", Calendar.OCTOBER, octCal.get(Calendar.MONTH));
        assertEquals(26, octCal.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void shouldFindAffectedDatesForSpringForward() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
        Date targetDate = cal.getTime();

        Date[] affected = scheduler.findAffectedDatesForCompensation(targetDate);

        assertNotNull(affected);
        assertTrue("Should find at least the DST day itself", affected.length >= 1);
    }

    @Test
    public void shouldFindAffectedDatesForFallBack() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.OCTOBER, 26, 12, 0, 0);
        Date targetDate = cal.getTime();

        Date[] affected = scheduler.findAffectedDatesForCompensation(targetDate);

        assertNotNull(affected);
        assertTrue("Should find affected dates around fall back", affected.length >= 1);
    }

    @Test
    public void shouldFindNoTransitionDatesInNormalRange() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.JUNE, 1, 0, 0, 0);
        Date rangeStart = cal.getTime();

        cal.set(2025, Calendar.JULY, 31, 23, 59, 59);
        Date rangeEnd = cal.getTime();

        Date[] transitions = scheduler.findDstTransitionDatesInRange(rangeStart, rangeEnd);

        assertNotNull(transitions);
        assertEquals("No DST transitions in summer", 0, transitions.length);
    }

    @Test
    public void shouldHandleScheduleCompensationWithNoAffectedDates() {
        Calendar cal = Calendar.getInstance(TEST_TZ);
        cal.set(2025, Calendar.JUNE, 15, 12, 0, 0);
        Date normalDate = cal.getTime();

        DstCompensationScheduler.CompensationResult result = scheduler.scheduleCompensation(normalDate);

        assertNotNull(result);
        assertEquals(DstCompensationScheduler.CompensationStatus.NO_DATES_AFFECTED, result.getStatus());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    public void shouldTrackTaskStatusRegistry() {
        Options options = new Options();
        options.setApiKey("test");
        options.setSecretKey("test");
        options.setBaseUrl("https://test.com");

        DstCompensationScheduler localScheduler = new DstCompensationScheduler(options, TEST_TZ);
        try {
            Calendar cal = Calendar.getInstance(TEST_TZ);
            cal.set(2025, Calendar.MARCH, 30, 12, 0, 0);
            Date dstDate = cal.getTime();

            localScheduler.scheduleCompensation(dstDate);
            assertNotNull(localScheduler.getAllTasks());
        } finally {
            localScheduler.shutdown();
        }
    }

    @Test
    public void shouldReturnCorrectCompensationStatusEnumValues() {
        assertNotNull(DstCompensationScheduler.CompensationStatus.valueOf("PENDING"));
        assertNotNull(DstCompensationScheduler.CompensationStatus.valueOf("RUNNING"));
        assertNotNull(DstCompensationScheduler.CompensationStatus.valueOf("COMPLETED"));
        assertNotNull(DstCompensationScheduler.CompensationStatus.valueOf("PARTIALLY_COMPLETED"));
        assertNotNull(DstCompensationScheduler.CompensationStatus.valueOf("RECALCULATION_NEEDED"));
        assertNotNull(DstCompensationScheduler.CompensationStatus.valueOf("FAILED"));
        assertNotNull(DstCompensationScheduler.CompensationStatus.valueOf("TIMEOUT"));
        assertNotNull(DstCompensationScheduler.CompensationStatus.valueOf("INTERRUPTED"));
        assertNotNull(DstCompensationScheduler.CompensationStatus.valueOf("NO_DATES_AFFECTED"));
    }
}
