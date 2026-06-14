package com.iyzipay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public final class GsonProvider {

    public static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String UTC_TIMEZONE_ID = "UTC";
    public static final String TURKEY_TIMEZONE_ID = "Europe/Istanbul";

    private static final ThreadLocal<DateFormat> DATE_FORMAT_HOLDER = new ThreadLocal<DateFormat>() {
        @Override
        protected DateFormat initialValue() {
            SimpleDateFormat sdf = new SimpleDateFormat(DEFAULT_DATE_FORMAT);
            sdf.setTimeZone(TimeZone.getTimeZone(UTC_TIMEZONE_ID));
            sdf.setLenient(false);
            return sdf;
        }
    };

    private static final Gson GSON = createGson();
    private static final Gson GSON_PRETTY = createGsonPretty();
    private static final Gson GSON_FOR_SIGNATURE = createGsonForSignature();

    private GsonProvider() {
    }

    public static Gson getGson() {
        return GSON;
    }

    public static Gson getGsonPretty() {
        return GSON_PRETTY;
    }

    public static Gson getGsonForSignature() {
        return GSON_FOR_SIGNATURE;
    }

    public static DateFormat getDateFormat() {
        return DATE_FORMAT_HOLDER.get();
    }

    private static Gson createGson() {
        return new GsonBuilder()
                .registerTypeAdapter(Date.class, new UtcDateTypeAdapter())
                .disableHtmlEscaping()
                .serializeNulls()
                .create();
    }

    private static Gson createGsonPretty() {
        return new GsonBuilder()
                .registerTypeAdapter(Date.class, new UtcDateTypeAdapter())
                .disableHtmlEscaping()
                .serializeNulls()
                .setPrettyPrinting()
                .create();
    }

    private static Gson createGsonForSignature() {
        return new GsonBuilder()
                .registerTypeAdapter(Date.class, new UtcDateTypeAdapter())
                .serializeNulls()
                .create();
    }

    private static class UtcDateTypeAdapter implements JsonSerializer<Date>, JsonDeserializer<Date> {

        @Override
        public JsonElement serialize(Date date, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(getDateFormat().format(date));
        }

        @Override
        public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            String dateString = json.getAsString();
            try {
                return getDateFormat().parse(dateString);
            } catch (ParseException e) {
                try {
                    long timestamp = Long.parseLong(dateString);
                    return new Date(timestamp);
                } catch (NumberFormatException nfe) {
                    throw new JsonParseException("Unable to parse date: " + dateString, e);
                }
            }
        }
    }
}
