package com.iyzipay.utils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public final class TimeBoundaryUtils {

    public static final String UTC_TIMEZONE_ID = "UTC";
    public static final String TURKEY_TIMEZONE_ID = "Europe/Istanbul";
    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_ONLY_FORMAT = "yyyy-MM-dd";

    private static final int MIDNIGHT_HOUR = 0;
    private static final int END_OF_DAY_HOUR = 23;
    private static final int END_OF_DAY_MINUTE = 59;
    private static final int END_OF_DAY_SECOND = 59;
    private static final int END_OF_DAY_MILLIS = 999;

    private TimeBoundaryUtils() {
    }

    public static TimeZone getUtcTimeZone() {
        return TimeZone.getTimeZone(UTC_TIMEZONE_ID);
    }

    public static TimeZone getTurkeyTimeZone() {
        return TimeZone.getTimeZone(TURKEY_TIMEZONE_ID);
    }

    public static Date getStartOfDay(Date date, TimeZone timeZone) {
        Calendar cal = Calendar.getInstance(timeZone);
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, MIDNIGHT_HOUR);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    public static Date getEndOfDay(Date date, TimeZone timeZone) {
        Calendar cal = Calendar.getInstance(timeZone);
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, END_OF_DAY_HOUR);
        cal.set(Calendar.MINUTE, END_OF_DAY_MINUTE);
        cal.set(Calendar.SECOND, END_OF_DAY_SECOND);
        cal.set(Calendar.MILLISECOND, END_OF_DAY_MILLIS);
        return cal.getTime();
    }

    public static Date getStartOfDayUtc(Date date) {
        return getStartOfDay(date, getUtcTimeZone());
    }

    public static Date getEndOfDayUtc(Date date) {
        return getEndOfDay(date, getUtcTimeZone());
    }

    public static Date getStartOfDayTurkey(Date date) {
        return getStartOfDay(date, getTurkeyTimeZone());
    }

    public static Date getEndOfDayTurkey(Date date) {
        return getEndOfDay(date, getTurkeyTimeZone());
    }

    public static Date addDays(Date date, int days, TimeZone timeZone) {
        Calendar cal = Calendar.getInstance(timeZone);
        cal.setTime(date);
        cal.add(Calendar.DAY_OF_MONTH, days);
        return cal.getTime();
    }

    public static boolean isDaylightSavingTransitionDay(Date date, TimeZone timeZone) {
        Date startOfDay = getStartOfDay(date, timeZone);
        Date startOfNextDay = addDays(startOfDay, 1, timeZone);

        long rawOffset = timeZone.getRawOffset();
        int offsetStart = timeZone.getOffset(startOfDay.getTime());
        int offsetEnd = timeZone.getOffset(startOfNextDay.getTime());

        return offsetStart != offsetEnd;
    }

    public static boolean isDaylightSavingTransitionDayTurkey(Date date) {
        return isDaylightSavingTransitionDay(date, getTurkeyTimeZone());
    }

    public static DstTransitionInfo getDstTransitionInfo(Date date, TimeZone timeZone) {
        Date startOfDay = getStartOfDay(date, timeZone);
        Date startOfNextDay = addDays(startOfDay, 1, timeZone);

        int offsetStart = timeZone.getOffset(startOfDay.getTime());
        int offsetEnd = timeZone.getOffset(startOfNextDay.getTime());

        if (offsetStart == offsetEnd) {
            return new DstTransitionInfo(false, 0, offsetStart);
        }

        int offsetDiffHours = (offsetEnd - offsetStart) / (int) TimeUnit.HOURS.toMillis(1);

        Calendar cal = Calendar.getInstance(timeZone);
        cal.setTime(startOfDay);
        cal.add(Calendar.HOUR_OF_DAY, 2);
        Date transitionTime = cal.getTime();
        int transitionOffset = timeZone.getOffset(transitionTime.getTime());

        boolean isSpringForward = transitionOffset != offsetStart;

        return new DstTransitionInfo(true, offsetDiffHours, isSpringForward ? offsetStart : offsetEnd);
    }

    public static Date[] getArchivalDateRangeWithDstCorrection(Date date, TimeZone timeZone) {
        Date startOfDay = getStartOfDay(date, timeZone);
        Date endOfDay = getEndOfDay(date, timeZone);

        if (isDaylightSavingTransitionDay(date, timeZone)) {
            DstTransitionInfo info = getDstTransitionInfo(date, timeZone);
            if (info.isSpringForward()) {
                endOfDay = new Date(endOfDay.getTime() + Math.abs(info.getOffsetDiffMillis()));
            } else {
                startOfDay = new Date(startOfDay.getTime() - Math.abs(info.getOffsetDiffMillis()));
            }
        }

        return new Date[]{startOfDay, endOfDay};
    }

    public static Date normalizeToBusinessDate(Date transactionDate, Date createdDate, TimeZone timeZone) {
        if (isDaylightSavingTransitionDay(createdDate, timeZone)) {
            DstTransitionInfo info = getDstTransitionInfo(createdDate, timeZone);

            Calendar businessDayCal = Calendar.getInstance(timeZone);
            businessDayCal.setTime(createdDate);
            businessDayCal.set(Calendar.HOUR_OF_DAY, MIDNIGHT_HOUR);
            businessDayCal.set(Calendar.MINUTE, 0);
            businessDayCal.set(Calendar.SECOND, 0);
            businessDayCal.set(Calendar.MILLISECOND, 0);

            if (info.isSpringForward()) {
                Calendar transitionPoint = Calendar.getInstance(timeZone);
                transitionPoint.setTime(createdDate);
                transitionPoint.set(Calendar.HOUR_OF_DAY, 3);
                transitionPoint.set(Calendar.MINUTE, 0);

                Calendar txCal = Calendar.getInstance(timeZone);
                txCal.setTime(transactionDate);

                if (txCal.before(transitionPoint)) {
                    return businessDayCal.getTime();
                }
            } else {
                Calendar transitionPoint = Calendar.getInstance(timeZone);
                transitionPoint.setTime(createdDate);
                transitionPoint.set(Calendar.HOUR_OF_DAY, 2);
                transitionPoint.set(Calendar.MINUTE, 0);

                Calendar txCal = Calendar.getInstance(timeZone);
                txCal.setTime(transactionDate);

                if (txCal.after(transitionPoint) || txCal.equals(transitionPoint)) {
                    businessDayCal.add(Calendar.DAY_OF_MONTH, -1);
                }
            }
            return businessDayCal.getTime();
        }
        return getStartOfDay(createdDate, timeZone);
    }

    public static String formatDate(Date date, String pattern, TimeZone timeZone) {
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        sdf.setTimeZone(timeZone);
        return sdf.format(date);
    }

    public static String formatDateOnlyUtc(Date date) {
        return formatDate(date, DATE_ONLY_FORMAT, getUtcTimeZone());
    }

    public static String formatDateOnlyTurkey(Date date) {
        return formatDate(date, DATE_ONLY_FORMAT, getTurkeyTimeZone());
    }

    public static boolean isSameDay(Date date1, Date date2, TimeZone timeZone) {
        Calendar cal1 = Calendar.getInstance(timeZone);
        cal1.setTime(date1);
        Calendar cal2 = Calendar.getInstance(timeZone);
        cal2.setTime(date2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    public static boolean isTransactionInDateRange(Date transactionDate, Date rangeStart, Date rangeEnd) {
        return !transactionDate.before(rangeStart) && !transactionDate.after(rangeEnd);
    }

    public static long getDayDurationMillis(Date date, TimeZone timeZone) {
        Date start = getStartOfDay(date, timeZone);
        Date end = addDays(start, 1, timeZone);
        return end.getTime() - start.getTime();
    }

    public static boolean isDstAffectedHour(Date date, TimeZone timeZone) {
        if (!isDaylightSavingTransitionDay(date, timeZone)) {
            return false;
        }
        Calendar cal = Calendar.getInstance(timeZone);
        cal.setTime(date);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 1 && hour <= 4;
    }

    public static class DstTransitionInfo {
        private final boolean transitionDay;
        private final int offsetDiffHours;
        private final int baseOffsetMillis;

        public DstTransitionInfo(boolean transitionDay, int offsetDiffHours, int baseOffsetMillis) {
            this.transitionDay = transitionDay;
            this.offsetDiffHours = offsetDiffHours;
            this.baseOffsetMillis = baseOffsetMillis;
        }

        public boolean isTransitionDay() {
            return transitionDay;
        }

        public int getOffsetDiffHours() {
            return offsetDiffHours;
        }

        public long getOffsetDiffMillis() {
            return offsetDiffHours * TimeUnit.HOURS.toMillis(1);
        }

        public int getBaseOffsetMillis() {
            return baseOffsetMillis;
        }

        public boolean isSpringForward() {
            return offsetDiffHours > 0;
        }

        public boolean isFallBack() {
            return offsetDiffHours < 0;
        }
    }
}
