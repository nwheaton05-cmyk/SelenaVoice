package com.selena.voice;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Random;

import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal Supabase Realtime (Phoenix channels) client over a raw RFC 6455
 * WebSocket. Pure Android SDK + org.json — no external dependencies, so it
 * works with the manual javac/d8 build.
 *
 * Nick 2026-10-18 v1.14: LEG 1 of the push pipeline. Subscribes to INSERTs
 * on public.selena_voice_queue so replies arrive instantly instead of on the
 * 3s poll. The poll loop is kept as a fallback.
 *
 * Usage: new RealtimeClient(host, apiKey, topic, table, listener).start()
 * Listener callbacks fire on the reader thread (same threading as the poll
 * worker — safe to call MainActivity.handleQueueMessage directly).
 */
public class RealtimeClient {
    private static final String TAG = "SelenaVoice";

    public interface Listener {
        void onInsert(JSONObject record);
        void onStatus(String msg);
    }

    /**
     * Supplies a fresh user access token for each (re)connect
     * (v1.50, 2026-10-26). Realtime authorizes postgres_changes
     * against the token's role — the queue's anon SELECT policy is
     * gone, so joins must carry a signed-in user's JWT.
     */
    public interface AccessTokenProvider {
        String getAccessToken();
    }

    private final String host;
    private final String apiKey;
    private final String topic;
    private final String table;
    private final Listener listener;
    private final AccessTokenProvider tokenProvider;

    private volatile boolean running;
    private Thread worker;
    private Socket socket;

    public RealtimeClient(String host, String apiKey, String topic, String table,
                          Listener listener) {
        this(host, apiKey, topic, table, listener, null);
    }

    public RealtimeClient(String host, String apiKey, String topic, String table,
                          Listener listener, AccessTokenProvider tokenProvider) {
        this.host = host;
        this.apiKey = apiKey;
        this.topic = topic;
        this.table = table;
        this.listener = listener;
        this.tokenProvider = tokenProvider;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "realtime-ws");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly();
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
    }

    private void runLoop() {
        int backoff = 2000;
        while (running) {
            try {
                connectAndListen();
                backoff = 2000; // reset after a clean session ends
            } catch (Exception e) {
                if (!running) break;
                listener.onStatus("rt: error: " + e.getMessage()
                        + ", retry in " + (backoff / 1000) + "s");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    break;
                }
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    private void connectAndListen() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket s = sf.createSocket(host, 443);
        s.setSoTimeout(40000);
        this.socket = s;
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        // --- WebSocket handshake ---
        byte[] keyBytes = new byte[16];
        new Random().nextBytes(keyBytes);
        String wsKey = Base64.getEncoder().encodeToString(keyBytes);
        String path = "/realtime/v1/websocket?apikey=" + apiKey + "&vsn=1.0.0";
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();
        // Read the FULL header block. Stopping at the status line leaves
        // the remaining headers in the stream, which then get parsed as
        // WebSocket frames and desync everything (bug found 2026-10-18).
        readHttpHeaders(in);
        listener.onStatus("rt: websocket connected");

        // --- Phoenix join ---
        JSONObject change = new JSONObject();
        change.put("event", "INSERT");
        change.put("schema", "public");
        change.put("table", table);
        JSONArray changes = new JSONArray();
        changes.put(change);
        JSONObject config = new JSONObject();
        config.put("postgres_changes", changes);
        JSONObject payload = new JSONObject();
        payload.put("config", config);
        // v1.50: authorize the channel as the signed-in user. Without
        // this the join rides the anon key and RLS delivers nothing
        // (anon SELECT on the queue was removed 2026-10-26).
        if (tokenProvider != null) {
            try {
                String tok = tokenProvider.getAccessToken();
                if (tok != null && !tok.isEmpty()) {
                    payload.put("access_token", tok);
                }
            } catch (Exception ignored) {
            }
        }
        JSONObject join = new JSONObject();
        join.put("topic", topic);
        join.put("event", "phx_join");
        join.put("payload", payload);
        join.put("ref", "1");
        sendText(out, join.toString());

        // Wait for phx_reply ok (bounded).
        long joinDeadline = System.currentTimeMillis() + 15000;
        boolean joined = false;
        long lastHeartbeat = 0;
        int ref = 2;
        ByteArrayOutputStream frag = new ByteArrayOutputStream();

        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastHeartbeat > 25000) {
                JSONObject hb = new JSONObject();
                hb.put("topic", "phoenix");
                hb.put("event", "heartbeat");
                hb.put("payload", new JSONObject());
                hb.put("ref", String.valueOf(ref++));
                sendText(out, hb.toString());
                lastHeartbeat = now;
            }
            String msg;
            try {
                msg = readTextFrame(in, frag);
            } catch (java.net.SocketTimeoutException ste) {
                continue; // no data within timeout; loop back to heartbeat
            }
            if (msg == null) throw new Exception("server closed connection");
            JSONObject m = new JSONObject(msg);
            String event = m.optString("event", "");
            if ("phx_reply".equals(event)) {
                JSONObject p = m.optJSONObject("payload");
                if (p != null && "ok".equals(p.optString("status"))) {
                    if (!joined) {
                        joined = true;
                        listener.onStatus("rt: subscribed to " + table);
                    }
                } else {
                    listener.onStatus("rt: phx_reply not ok: " + msg);
                }
            } else if ("postgres_changes".equals(event)) {
                JSONObject p = m.optJSONObject("payload");
                if (p != null) {
                    JSONObject data = p.optJSONObject("data");
                    if (data != null && "INSERT".equals(data.optString("type"))) {
                        JSONObject record = data.optJSONObject("record");
                        if (record != null) listener.onInsert(record);
                    }
                }
            }
            if (!joined && now > joinDeadline) {
                throw new Exception("phx_join not acknowledged in 15s");
            }
        }
    }

    // ---------------------------------------------------------- WS framing

    private static void readHttpHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int[] last4 = {-1, -1, -1, -1};
        int cur;
        while ((cur = in.read()) != -1) {
            bos.write(cur);
            last4[0] = last4[1];
            last4[1] = last4[2];
            last4[2] = last4[3];
            last4[3] = cur;
            if (last4[0] == '\r' && last4[1] == '\n'
                    && last4[2] == '\r' && last4[3] == '\n') {
                break;
            }
            if (bos.size() > 16384) throw new Exception("header block too large");
        }
        String headers = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        String statusLine = headers.split("\r\n")[0];
        if (!statusLine.contains("101")) {
            throw new Exception("WS handshake failed: " + statusLine);
        }
    }

    private static void sendText(OutputStream out, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN + text
        int n = data.length;
        if (n < 126) {
            frame.write(0x80 | n);
        } else if (n < 65536) {
            frame.write(0x80 | 126);
            frame.write((n >> 8) & 0xFF);
            frame.write(n & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((n >> (8 * i)) & 0xFF);
        }
        frame.write(mask);
        for (int i = 0; i < n; i++) frame.write(data[i] ^ mask[i % 4]);
        out.write(frame.toByteArray());
        out.flush();
    }

    /**
     * Reads one complete text message, reassembling fragments. Returns null
     * on close frame. Throws SocketTimeoutException on read timeout.
     */
    private static String readTextFrame(InputStream in, ByteArrayOutputStream frag)
            throws Exception {
        frag.reset();
        while (true) {
            int b1 = in.read();
            int b2 = in.read();
            if (b1 == -1 || b2 == -1) return null;
            int opcode = b1 & 0x0F;
            boolean fin = (b1 & 0x80) != 0;
            boolean masked = (b2 & 0x80) != 0;
            long len = b2 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            long remaining = len;
            byte[] chunk = new byte[8192];
            while (remaining > 0) {
                int toRead = (int) Math.min(chunk.length, remaining);
                int r = in.read(chunk, 0, toRead);
                if (r == -1) return null;
                if (masked) {
                    long offset = len - remaining;
                    for (int i = 0; i < r; i++) {
                        chunk[i] ^= mask[(int) ((offset + i) % 4)];
                    }
                }
                frag.write(chunk, 0, r);
                remaining -= r;
            }
            if (opcode == 0x8) return null; // close
            if (opcode == 0x9) continue;    // ping: server pings are rare; ignore
            if (opcode == 0xA) continue;    // pong
            if (fin) break;
            // else: continuation frame follows
        }
        return new String(frag.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int readByte(InputStream in) throws Exception {
        int b = in.read();
        if (b == -1) throw new Exception("unexpected EOF in frame header");
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r == -1) throw new Exception("unexpected EOF");
            off += r;
        }
    }

    /** Unused here (client masks); kept to avoid dead-code warnings in review. */
    @SuppressWarnings("unused")
    private static String wsAcceptKey(String wsKey) throws Exception {
        String magic = wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest(magic.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}
