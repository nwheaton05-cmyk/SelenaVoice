package com.selena.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Supabase Auth (email + password) for Selena Voice — v1.50
 * (2026-10-26, OVH batch).
 *
 * Why: the selena_voice_queue table's anon SELECT policy was removed
 * (2026-10-26 security batch) — only authenticated users can SELECT
 * now, which is what Supabase Realtime checks before delivering
 * postgres_changes rows. The app therefore signs in with a real
 * account and hands the user JWT to RealtimeClient on every join.
 *
 * Credentials are entered in-app (MainActivity sign-in dialog) and
 * stored only in this app's private SharedPreferences — never
 * hardcoded. The only baked-in key is the public anon API key, which
 * is the gateway key Supabase Auth itself requires, not a login.
 *
 * ALL methods block on network I/O — call from worker threads only.
 */
public class SupabaseAuth {
    private static final String TAG = "SelenaVoice";
    private static final String AUTH_BASE =
            "https://api.siteviewoperations.com/auth/v1";
    // Public anon key of the OVH Supabase (same value VoiceService and
    // CrashReporter carry) — required as the `apikey` header by the
    // auth endpoints; it grants nothing by itself.
    private static final String API_KEY =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzdXBhYmFzZSIsImlhdCI6MTc5Mjg1Njk0MCwiZXhwIjo0OTQ4NTMwNTQwLCJyb2xlIjoiYW5vbiJ9.tHuj64aAawXs_4dl-TVdgfxOUcmyKloh204BfEc60mk";
    private static final String PREFS = "selena_voice_auth";
    private static final String K_EMAIL = "email";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EXPIRES = "expires_at_ms";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isLoggedIn(Context ctx) {
        return prefs(ctx).getString(K_REFRESH, null) != null;
    }

    /** The public anon key, for read-only REST calls (profiles). */
    public static String apiKey() {
        return API_KEY;
    }

    public static String savedEmail(Context ctx) {
        String e = prefs(ctx).getString(K_EMAIL, "");
        return e == null ? "" : e;
    }

    public static void logout(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    /**
     * Nick 2026-10-28 v1.53: the signed-in account's stable key — the
     * auth user id (`sub` claim of the stored access token), falling
     * back to a sanitized email key if the token can't be parsed.
     * Used to key per-user on-device stores (the voiceprint wall,
     * spec §5): it identifies WHICH profile a sample belongs to; it
     * is never an authentication decision by itself. Non-blocking:
     * reads only what's already stored. Null when signed out.
     */
    public static String userKey(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String access = p.getString(K_ACCESS, null);
        if (access != null) {
            try {
                String[] parts = access.split("\\.");
                if (parts.length >= 2) {
                    byte[] payload = android.util.Base64.decode(
                            parts[1], android.util.Base64.URL_SAFE
                                    | android.util.Base64.NO_WRAP
                                    | android.util.Base64.NO_PADDING);
                    JSONObject claims = new JSONObject(
                            new String(payload, StandardCharsets.UTF_8));
                    String sub = claims.optString("sub", "");
                    if (!sub.isEmpty()) return sub;
                }
            } catch (Exception e) {
                Log.w(TAG, "auth: userKey parse failed", e);
            }
        }
        String email = p.getString(K_EMAIL, "");
        if (email != null && !email.isEmpty()) {
            return "em_" + email.replaceAll("[^a-zA-Z0-9]", "_");
        }
        return null;
    }

    /** Returns null on success, or a human-readable error. Blocking. */
    public static String login(Context ctx, String email, String password) {
        try {
            JSONObject body = new JSONObject();
            body.put("email", email);
            body.put("password", password);
            JSONObject resp = tokenRequest("grant_type=password", body);
            if (resp == null) return "no response from auth server";
            if (resp.has("access_token")) {
                store(ctx, email, resp);
                Log.i(TAG, "auth: signed in as " + email);
                return null;
            }
            String err = resp.optString("error_description", "");
            if (err.isEmpty()) err = resp.optString("msg", "");
            if (err.isEmpty()) err = resp.optString("error", "sign-in failed");
            return err;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * A currently-valid access token, refreshing when within 60s of
     * expiry. Null when signed out or the refresh fails. Blocking.
     */
    public static String validAccessToken(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String access = p.getString(K_ACCESS, null);
        long expires = p.getLong(K_EXPIRES, 0);
        if (access != null
                && System.currentTimeMillis() < expires - 60_000) {
            return access;
        }
        String refresh = p.getString(K_REFRESH, null);
        if (refresh == null) return null;
        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refresh);
            JSONObject resp = tokenRequest("grant_type=refresh_token", body);
            if (resp != null && resp.has("access_token")) {
                store(ctx, savedEmail(ctx), resp);
                Log.i(TAG, "auth: token refreshed");
                return resp.getString("access_token");
            }
            Log.w(TAG, "auth: refresh failed: " + resp);
        } catch (Exception e) {
            Log.w(TAG, "auth: refresh error: " + e.getMessage());
        }
        return null;
    }

    private static void store(Context ctx, String email, JSONObject resp) {
        long expiresIn = resp.optLong("expires_in", 3600);
        prefs(ctx).edit()
                .putString(K_EMAIL, email)
                .putString(K_ACCESS, resp.optString("access_token", null))
                .putString(K_REFRESH, resp.optString("refresh_token", null))
                .putLong(K_EXPIRES,
                        System.currentTimeMillis() + expiresIn * 1000L)
                .apply();
    }

    private static JSONObject tokenRequest(String query, JSONObject body)
            throws Exception {
        URL url = new URL(AUTH_BASE + "/token?" + query);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("apikey", API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(bytes);
        os.close();
        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
            conn.disconnect();
            return null;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();
        return new JSONObject(sb.toString());
    }
}
