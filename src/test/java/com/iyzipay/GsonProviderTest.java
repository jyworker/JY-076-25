package com.iyzipay;

import com.google.gson.Gson;
import org.junit.Test;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class GsonProviderTest {

    @Test
    public void shouldReturnSameGsonInstance() {
        Gson gson1 = GsonProvider.getGson();
        Gson gson2 = GsonProvider.getGson();
        assertSame(gson1, gson2);
    }

    @Test
    public void shouldSerializeDateWithUtcTimezone() throws Exception {
        DateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date testDate = utcFormat.parse("2025-03-30 01:30:00");

        String json = GsonProvider.getGson().toJson(testDate);
        String cleanedJson = json.replace("\"", "");

        DateFormat resultFormat = GsonProvider.getDateFormat();
        Date parsedBack = resultFormat.parse(cleanedJson);

        assertEquals(testDate.getTime(), parsedBack.getTime());
        assertTrue(cleanedJson.contains("2025-03-30"));
    }

    @Test
    public void shouldDeserializeDateString() throws Exception {
        String dateStr = "2025-10-26 02:30:00";
        String json = "\"" + dateStr + "\"";

        Date parsed = GsonProvider.getGson().fromJson(json, Date.class);

        DateFormat expectedFormat = GsonProvider.getDateFormat();
        Date expected = expectedFormat.parse(dateStr);

        assertEquals(expected.getTime(), parsed.getTime());
    }

    @Test
    public void shouldDeserializeTimestamp() {
        long timestamp = 1743292800000L;
        String json = "\"" + timestamp + "\"";

        Date parsed = GsonProvider.getGson().fromJson(json, Date.class);

        assertEquals(timestamp, parsed.getTime());
    }

    @Test
    public void shouldHandleSpringForwardDstDateSerialization() throws Exception {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Berlin"));
        cal.set(2025, Calendar.MARCH, 30, 3, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date springForwardDate = cal.getTime();

        String json = GsonProvider.getGson().toJson(springForwardDate);
        String cleaned = json.replace("\"", "");

        Date parsed = GsonProvider.getDateFormat().parse(cleaned);
        assertEquals(springForwardDate.getTime(), parsed.getTime());
    }

    @Test
    public void shouldHandleFallBackDstDateSerialization() throws Exception {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Berlin"));
        cal.set(2025, Calendar.OCTOBER, 26, 2, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date fallBackDate = cal.getTime();

        String json = GsonProvider.getGson().toJson(fallBackDate);
        String cleaned = json.replace("\"", "");

        Date parsed = GsonProvider.getDateFormat().parse(cleaned);
        assertEquals(fallBackDate.getTime(), parsed.getTime());
    }

    @Test
    public void shouldReturnUtcTimezoneConstants() {
        assertEquals("UTC", GsonProvider.UTC_TIMEZONE_ID);
        assertEquals("Europe/Istanbul", GsonProvider.TURKEY_TIMEZONE_ID);
        assertEquals("yyyy-MM-dd HH:mm:ss", GsonProvider.DEFAULT_DATE_FORMAT);
    }
}
