package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * clock_home weather: one request to Open-Meteo (no key, no account) for the theme's coordinates, at most every
 * 30 minutes and only while Home is on screen. The only network the launcher does; it sends the coordinates and nothing else.
 */
final class Weather {
    interface Callback {
        void onText(String text);
    }

    private static final long MAX_AGE_MS = 30 * 60_000L;

    private final String url;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String text;
    /** The last reading's WMO code, or -1 before the first one (art Auto matches the weather with it). */
    private volatile int code = -1;
    private long fetchedAt;
    private boolean busy;

    Weather(double latitude, double longitude) {
        url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&timezone=auto",
                latitude, longitude);
    }

    /** Hands the last reading over at once, then fetches a new one when it is older than 30 minutes. */
    void refresh(Callback callback) {
        if (text != null) callback.onText(text);
        if (busy || System.currentTimeMillis() - fetchedAt < MAX_AGE_MS) return;
        busy = true;
        executor.execute(() -> {
            String result = fetch();
            main.post(() -> {
                busy = false;
                if (result != null) {
                    text = result;
                    fetchedAt = System.currentTimeMillis();
                    callback.onText(result);
                }
            });
        });
    }

    void close() {
        executor.shutdownNow();
    }

    private String fetch() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[2048];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() < 16384) out.write(buffer, 0, n);
            }
            JSONObject current = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
            long degrees = Math.round(current.getDouble("temperature_2m"));
            code = current.getInt("weather_code");
            return degrees + "°C · " + describe(code);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    int code() {
        return code;
    }

    /** The weather as an art tag: snow, rain or fog while it is happening, otherwise null. */
    static String tag(int code) {
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) return "rain";
        if (code == 45 || code == 48) return "fog";
        return null;
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }
}
