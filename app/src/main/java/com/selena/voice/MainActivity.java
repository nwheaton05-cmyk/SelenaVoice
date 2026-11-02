package com.selena.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Selena Voice main UI (Nick, 2026-10-18).
 *
 * Nick 2026-10-19 v1.16: BACKGROUND OPERATION. The activity is a pure
 * UI shell — status line, message history with replay, playback
 * controls, output toggles, version label. ALL playback work
 * (Realtime push, queue polling, heartbeat, MP3/TTS playback) lives
 * in VoiceService, which keeps running with the screen locked. The
 * activity just starts the service and renders the service's broadcasts.
 *
 * v1.82 (Nick, 2026-11-02): PLAYBACK-ONLY — the microphone, the
 * dictation popup, the always-listening switch, the mic button, the
 * voiceprint/machine dialogs, and the dictation text box are gone.
 * Nick replies in the Muse app; this screen is a player.
 */
public class MainActivity extends Activity {
    private static final String TAG = "SelenaVoice";

    private TextView statusText;
    private TextView pollStatusText;
    private android.widget.LinearLayout messageList;
    // Nick 2026-10-23 v1.38: was the inner message-box scroller; the box
    // now flows in the page scroll, so this is the outer ScrollView.
    private ScrollView outerScroll;

    private final MessageReceiver receiver;

    // Nick 2026-10-21: pause/resume button for playback.
    private Button pauseButton;
    private boolean playbackPaused = false;

    // Nick 2026-10-22 v1.33: skip to newest — abandon the current message
    // and play the newest queued one.
    private Button skipButton;

    // Nick 2026-10-22 v1.35: hard stop — kill all playback/speech now,
    // drop unlocked queued items.
    private Button stopButton;

    // Nick 2026-10-22 v1.34: delete unlocked — wipe the server queue and
    // local history except for locked messages.
    private Button deleteUnlockedButton;

    // v1.50 (2026-10-26): account sign-in + delete-key settings. The
    // queue's anon SELECT policy is gone, so Realtime needs a real
    // user token (SupabaseAuth); the DELETE route needs the
    // x-voice-delete-key header, whose value is stored on-device via
    // the delete-key dialog — never hardcoded.
    private Button signInButton;
    private Button deleteKeyButton;

    // Nick 2026-10-22 v1.33: in-app auto-updater banner. Hidden until the
    // version check finds a newer APK; tap downloads + installs it.
    private Button updateBanner;
    private String pendingApkUrl;

    private TextView voiceModeLabel;
    private Switch voiceModeSwitch;

    // Nick 2026-10-19 v1.17: no-auto-readout + output-route toggles.
    private Switch noReadoutSwitch;
    private Switch btSwitch;
    private Switch spkSwitch;
    private Switch earbudsOnlySwitch;
    private Switch headsetFallbackSwitch;

    public MainActivity() {
        receiver = new MessageReceiver(this);
    }

    // Nick 2026-10-18 v1.9: history shows Selena's messages (and, in
    // pre-v1.82 histories, Nick's dictated entries, rendered "You:"
    // style). The audio queue only ever plays audioUrl MP3s from
    // /api/selena-voice/queue — Selena's replies.
    private static final String COLOR_SELENA = "#2D2A32"; // selena_dark
    private static final String COLOR_NICK = "#6B4FA1";   // selena_purple

    /** Called by MessageReceiver when Selena has a new voice message. */
    public void onSelenaMessage(String text) {
        // Nick 2026-10-21 v1.24: history is the UI now; re-render.
        // Nick 2026-10-22 v1.26: new message — scroll to it.
        renderHistory(true);
    }

    /** Called by MessageReceiver with the last queue-poll result. */
    public void onPollStatus(String status) {
        if (status == null) return;
        final String s = status;
        runOnUiThread(() -> pollStatusText.setText(s));
    }

    /**
     * Nick 2026-10-21/22: playback pause button state. v1.28: the button is
     * ALWAYS enabled — a tap always reaches the service, which toasts what
     * it did ("Paused" / "Nothing playing to pause"). Label toggles ⏸️/▶️.
     */
    public void onPlaybackState(boolean playing, boolean paused) {
        playbackPaused = paused;
        // Nick 2026-10-22 v1.29: DIAG — prove the broadcast reached the UI.
        VoiceService.log("ui: playbackState playing=" + playing + " paused=" + paused);
        runOnUiThread(() -> {
            // Nick 2026-10-22 v1.28: always enabled — the service decides
            // and reports back via toast.
            pauseButton.setVisibility(android.view.View.VISIBLE);
            pauseButton.setEnabled(true);
            pauseButton.setText(paused ? "▶️ Resume" : "⏸️ Pause");
        });
    }

    /** Nick 2026-10-22 v1.28: service feedback toasts (pause/resume). */
    public void onToast(String msg) {
        // Nick 2026-10-22 v1.29: DIAG — prove the toast broadcast arrived.
        VoiceService.log("ui: toast received: " + msg);
        runOnUiThread(() -> {
            try {
                // v1.29: LONG so it's harder to miss.
                android.widget.Toast.makeText(this, msg,
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Nick 2026-10-23 v1.36: global crash reporter — the 07:43 crash
        // died with no server report. Next time we get the stack trace.
        CrashReporter.install(this);
        setContentView(R.layout.activity_main);

        // Nick 2026-10-23 v1.48: tap-anywhere to pause her. During Selena's
        // playback (tap-interrupt mode, or as backup in voice-interrupt
        // mode), tapping anywhere on the screen pauses her voice. The
        // service ignores it when nothing is playing (toasts).
        findViewById(android.R.id.content).setOnClickListener(v -> {
            Intent pause = new Intent(this, VoiceService.class);
            pause.setAction("PAUSE_PLAYBACK");
            startForegroundService(pause);
        });

        statusText = findViewById(R.id.statusText);
        pollStatusText = findViewById(R.id.pollStatusText);
        // Nick 2026-10-19 v1.15: show the installed version so we can
        // verify which build is on the phone (kills version-guessing).
        // Nick 2026-10-21 v1.23: include build date so identical version
        // names across rebuilds are distinguishable.
        try {
            String vn = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            String buildDate = "2026-10-22";
            ((TextView) findViewById(R.id.versionText)).setText("v" + vn + " (" + buildDate + ")");
        } catch (Exception ignored) {}
        // Nick 2026-10-23 v1.48: long-press version text to set earbuds
        // BT device name (one-time setup for the earbuds-only lane).
        findViewById(R.id.versionText).setOnLongClickListener(v -> {
            showEarbudsSetupDialog();
            return true;
        });
        // Nick 2026-10-21 v1.24: persistent message history with replay.
        MessageHistory.init(this);
        messageList = findViewById(R.id.messageList);
        outerScroll = findViewById(R.id.outerScroll);
        pauseButton = findViewById(R.id.pauseButton);
        // Nick 2026-10-22 v1.28: always enabled from launch — a tap always
        // reaches the service, which toasts what it did.
        pauseButton.setEnabled(true);

        // Nick 2026-10-21: pause/resume button for playback. v1.28: every
        // tap sends the toggle — the SERVICE decides pause vs resume from
        // live state, so a stale button label can never send the wrong one.
        pauseButton.setOnClickListener(v -> {
            Intent i = new Intent(this, VoiceService.class);
            i.setAction("PAUSE_PLAYBACK");
            startForegroundService(i);
        });

        // Nick 2026-10-22 v1.35: stop button. Sends STOP_ALL — the service
        // kills whatever is playing/speaking and drops unlocked queued
        // items. Always enabled; the service toasts so a tap never dies
        // silently.
        stopButton = findViewById(R.id.stopButton);
        stopButton.setEnabled(true);
        stopButton.setOnClickListener(v -> {
            Intent i = new Intent(this, VoiceService.class);
            i.setAction(VoiceService.ACTION_STOP_ALL);
            startForegroundService(i);
        });

        // Nick 2026-10-22 v1.33: skip-to-newest button. Sends
        // SKIP_TO_NEWEST — the service abandons the current message and
        // plays the newest queued one. Always enabled; the service toasts
        // the outcome so a tap never dies silently.
        skipButton = findViewById(R.id.skipButton);
        skipButton.setEnabled(true);
        skipButton.setOnClickListener(v -> {
            Intent i = new Intent(this, VoiceService.class);
            i.setAction("SKIP_TO_NEWEST");
            startForegroundService(i);
        });

        // Nick 2026-10-22 v1.34: delete-unlocked button. Deletes all
        // messages except locked ones — server queue (via DELETE with
        // keep_ids) and local history. Always toasts the outcome.
        deleteUnlockedButton = findViewById(R.id.deleteUnlockedButton);
        deleteUnlockedButton.setEnabled(true);
        deleteUnlockedButton.setOnClickListener(v -> deleteUnlocked());

        // v1.50 (2026-10-26): sign-in + delete-key settings buttons.
        signInButton = findViewById(R.id.signInButton);
        signInButton.setEnabled(true);
        signInButton.setOnClickListener(v -> showSignInDialog());
        deleteKeyButton = findViewById(R.id.deleteKeyButton);
        deleteKeyButton.setEnabled(true);
        deleteKeyButton.setOnClickListener(v -> showDeleteKeyDialog());
        refreshSignInButton();

        // Nick 2026-10-22 v1.33: in-app auto-updater. Hidden until the
        // version check finds a newer APK — v1.33 is the last manual
        // install.
        updateBanner = findViewById(R.id.updateBanner);
        updateBanner.setVisibility(View.GONE);
        checkForAppUpdate();

        // Nick 2026-10-18 v1.11: voice mode toggle — Phone (fast, on-device
        // TTS, default) vs Achernar (quality, in-app Google Cloud TTS
        // synthesis since v1.43; was server-generated Aria MP3). Stored in
        // SharedPreferences so it survives restarts; the service reads the
        // same key when handling each message.
        // Nick 2026-10-23: toggle ON = Achernar active (was inverted —
        // OFF meant Chirp, which confused everyone).
        voiceModeLabel = findViewById(R.id.voiceModeLabel);
        voiceModeSwitch = findViewById(R.id.voiceModeSwitch);
        voiceModeSwitch.setChecked(!isPhoneVoiceMode());
        updateVoiceModeLabel();
        voiceModeSwitch.setOnCheckedChangeListener((v, chirp) -> {
            getSharedPreferences("selena_voice", MODE_PRIVATE).edit()
                    .putString(VoiceService.KEY_VOICE_MODE,
                            chirp ? VoiceService.VOICE_ARIA : VoiceService.VOICE_PHONE)
                    .apply();
            updateVoiceModeLabel();
            VoiceService.log("voice: mode=" + (chirp ? "callirrhoe (in-app)" : "phone (fast)"));
        });

        // Nick 2026-10-19 v1.17: no-auto-readout — messages land in the
        // log but are never played aloud. Stored in SharedPreferences so
        // the service's queue handling reads the same value.
        noReadoutSwitch = findViewById(R.id.noReadoutSwitch);
        noReadoutSwitch.setChecked(VoiceService.getNoAutoReadout(this));
        noReadoutSwitch.setOnCheckedChangeListener((v, on) -> {
            VoiceService.setNoAutoReadout(this, on);
            VoiceService.log("voice: no-auto-readout=" + on);
        });

        // Nick 2026-10-19 v1.17: output-route toggles, mutually exclusive.
        // Neither on = auto (Bluetooth wins when connected, else speaker).
        btSwitch = findViewById(R.id.btSwitch);
        spkSwitch = findViewById(R.id.spkSwitch);
        syncOutputSwitches();
        btSwitch.setOnCheckedChangeListener((v, on) -> {
            VoiceService.setForceBluetooth(this, on); // clears speaker pref
            VoiceService.log("voice: output=bluetooth" + (on ? " (exclusive)" : " (auto)"));
            syncOutputSwitches();
        });
        spkSwitch.setOnCheckedChangeListener((v, on) -> {
            VoiceService.setForceSpeaker(this, on); // clears BT pref
            VoiceService.log("voice: output=speaker" + (on ? " (exclusive)" : " (auto)"));
            syncOutputSwitches();
        });

        // Nick 2026-10-30 v1.73: earbuds-only playback lane. ON
        // (the default): Selena plays ONLY into the named earbuds
        // (headset channel, Spotify untouched) and not at all when
        // they're disconnected. Needs the earbuds name (long-press
        // the version text) — without one the lane stays inert.
        earbudsOnlySwitch = findViewById(R.id.earbudsOnlySwitch);
        earbudsOnlySwitch.setChecked(VoiceService.getEarbudsOnly(this));
        earbudsOnlySwitch.setOnCheckedChangeListener((v, on) -> {
            VoiceService.setEarbudsOnly(this, on);
            VoiceService.log("lane: earbuds-only=" + on);
            if (on) {
                String name = getSharedPreferences(
                        "selena_voice_prefs", MODE_PRIVATE)
                        .getString("earbuds_device_name", "");
                if (name == null || name.isEmpty()) {
                    android.widget.Toast.makeText(this,
                            "Set your earbuds name first "
                                    + "(long-press the version text)",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });

        // Nick 2026-10-30 v1.77: headset fallback for the earbuds
        // lane. ON (the default): when the clean earbuds route is
        // refused or unprovable in a split case (Android Auto
        // refuses the media pin), Selena falls back to the earbuds'
        // headset channel instead of staying silent. OFF restores
        // the v1.76 park-on-refusal behavior.
        headsetFallbackSwitch = findViewById(R.id.headsetFallbackSwitch);
        headsetFallbackSwitch.setChecked(
                VoiceService.getHeadsetFallback(this));
        headsetFallbackSwitch.setOnCheckedChangeListener((v, on) -> {
            VoiceService.setHeadsetFallback(this, on);
            VoiceService.log("lane: headset-fallback=" + on);
        });

        requestPermissions();
        // The service starts as mediaPlayback, which needs NO runtime
        // permission — playback, polling, and the queue just work.
        // v1.82: the app holds no microphone permission at all.
        startVoiceService();
    }

    // ------------------------------------------------------------ in-app updater
    // Nick 2026-10-22 v1.33: self-update so this is the last manual
    // install. On launch, compare the installed versionCode against
    // /api/selena-voice/version. A newer build shows a green banner;
    // tap downloads the APK and fires the system installer.

    private static final String VERSION_URL =
            "https://siteviewoperations.com/api/selena-voice/version";

    private int getInstalledVersionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return (int) pi.getLongVersionCode();
            } else {
                return pi.versionCode;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private void checkForAppUpdate() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(VERSION_URL);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return;
                }
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();
                org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                int latestCode = obj.optInt("versionCode", 0);
                String apkUrl = obj.optString("apkUrl", "");
                boolean available = obj.optBoolean("apkAvailable", false);
                int installed = getInstalledVersionCode();
                VoiceService.log("updater: installed=" + installed
                        + " latest=" + latestCode + " available=" + available);
                if (available && latestCode > installed && !apkUrl.isEmpty()) {
                    final String dl = apkUrl;
                    runOnUiThread(() -> showUpdateBanner(dl));
                }
            } catch (Exception e) {
                VoiceService.log("updater: check failed: " + e.getMessage());
            }
        }).start();
    }

    private void showUpdateBanner(String apkUrl) {
        pendingApkUrl = apkUrl;
        updateBanner.setVisibility(View.VISIBLE);
        updateBanner.setEnabled(true);
        updateBanner.setText("⬆️ Update available — tap to install");
        updateBanner.setOnClickListener(v -> downloadAndInstallApk(pendingApkUrl));
        VoiceService.log("updater: banner shown");
    }

    // Nick 2026-10-22 v1.34: delete unlocked — wipe the server queue and
    // local history except for locked messages. Server keeps the locked
    // ones via keep_ids; local history drops unlocked entries (and MP3s).
    // Always toasts the outcome so a tap never dies silently.
    private static final String QUEUE_DELETE_URL =
            "https://siteviewoperations.com/api/selena-voice/queue";

    private void deleteUnlocked() {
        deleteUnlockedButton.setEnabled(false);
        // Tell the service to drop unlocked items from its playback
        // queues (locked ones keep playing/queued).
        Intent clearIntent = new Intent(this, VoiceService.class);
        clearIntent.setAction("CLEAR_UNLOCKED");
        startForegroundService(clearIntent);
        new Thread(() -> {
            int serverDeleted = -1;
            int serverKept = -1;
            String err = null;
            try {
                java.util.List<String> keepIds = MessageHistory.getLockedServerIds();
                org.json.JSONObject body = new org.json.JSONObject();
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String id : keepIds) arr.put(id);
                body.put("keep_ids", arr);

                java.net.URL url = new java.net.URL(QUEUE_DELETE_URL);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json");
                // v1.50 (2026-10-26): the DELETE route requires the
                // dedicated x-voice-delete-key credential and fails
                // closed without it. The value is entered in-app
                // (Delete key button) and stored on-device only.
                String deleteKey = getSharedPreferences(
                        "selena_voice", MODE_PRIVATE)
                        .getString("voice_delete_key", "");
                if (deleteKey != null && !deleteKey.isEmpty()) {
                    conn.setRequestProperty("x-voice-delete-key", deleteKey);
                }
                byte[] bytes = body.toString().getBytes("UTF-8");
                conn.getOutputStream().write(bytes);
                int code = conn.getResponseCode();
                if (code == 200) {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    org.json.JSONObject resp = new org.json.JSONObject(sb.toString());
                    serverDeleted = resp.optInt("deleted", -1);
                    serverKept = resp.optInt("kept", -1);
                } else {
                    err = "HTTP " + code;
                }
                conn.disconnect();
            } catch (Exception e) {
                err = e.getMessage();
            }

            // Clear unlocked from local history regardless of server result
            // (local and server are independent; a server failure shouldn't
            // block local cleanup). Then sweep orphaned MP3s.
            int localRemoved = MessageHistory.deleteUnlocked();
            int orphansDeleted = 0;
            try {
                orphansDeleted = MessageHistory.deleteOrphanedAudio(getCacheDir());
            } catch (Exception e) {
                VoiceService.log("delete-unlocked: orphan sweep failed: " + e.getMessage());
            }

            final int fDeleted = serverDeleted;
            final int fKept = serverKept;
            final String fErr = err;
            final int fLocal = localRemoved;
            final int fOrphans = orphansDeleted;
            runOnUiThread(() -> {
                deleteUnlockedButton.setEnabled(true);
                renderHistory(false);
                String msg;
                if (fErr != null) {
                    msg = "Server delete failed (" + fErr + ") — cleared "
                            + fLocal + " local";
                } else if (fDeleted == 0 && fLocal == 0) {
                    msg = "Nothing to delete";
                } else {
                    msg = "Deleted " + fDeleted + " server, " + fLocal
                            + " local" + (fOrphans > 0 ? ", " + fOrphans + " orphan files" : "")
                            + (fKept > 0 ? " (" + fKept + " locked kept)" : "");
                }
                android.widget.Toast.makeText(MainActivity.this, msg,
                        android.widget.Toast.LENGTH_LONG).show();
                VoiceService.log("delete-unlocked: server deleted=" + fDeleted
                        + " kept=" + fKept + " local removed=" + fLocal
                        + " orphans=" + fOrphans
                        + (fErr != null ? " err=" + fErr : ""));
            });
        }).start();
    }

    // ------------------------------------------------------------ v1.50 account + delete key
    // 2026-10-26 (OVH batch): the queue's anon SELECT policy was
    // removed — Realtime now authorizes as the signed-in user, so the
    // app needs a real Supabase Auth login. Credentials are entered
    // here and stored on-device only (SupabaseAuth). The delete key
    // for the DELETE route's x-voice-delete-key header is likewise
    // entered here, never hardcoded (the server env VOICE_DELETE_KEY
    // is the matching half, set in the deployment env).

    private void refreshSignInButton() {
        if (signInButton == null) return;
        if (SupabaseAuth.isLoggedIn(this)) {
            signInButton.setText("👤 " + SupabaseAuth.savedEmail(this));
        } else {
            signInButton.setText("👤 Sign in");
        }
    }

    private void showSignInDialog() {
        android.widget.LinearLayout box =
                new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);
        final android.widget.EditText emailIn =
                new android.widget.EditText(this);
        emailIn.setHint("Email");
        emailIn.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailIn.setText(SupabaseAuth.savedEmail(this));
        final android.widget.EditText passIn =
                new android.widget.EditText(this);
        passIn.setHint("Password");
        passIn.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(emailIn);
        box.addView(passIn);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Sign in (queue access)")
                .setView(box)
                .setPositiveButton("Sign in", (d, w) -> {
                    final String email =
                            emailIn.getText().toString().trim();
                    final String pass = passIn.getText().toString();
                    new Thread(() -> {
                        String err = SupabaseAuth.login(
                                MainActivity.this, email, pass);
                        runOnUiThread(() -> {
                            refreshSignInButton();
                            android.widget.Toast.makeText(
                                    MainActivity.this,
                                    err == null
                                            ? "Signed in as " + email
                                            : "Sign-in failed: " + err,
                                    android.widget.Toast.LENGTH_LONG)
                                    .show();
                            if (err == null) {
                                // Rejoin Realtime with the user token.
                                Intent ri = new Intent(
                                        MainActivity.this, VoiceService.class);
                                ri.setAction("RESTART_REALTIME");
                                startForegroundService(ri);
                            }
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Sign out", (d, w) -> {
                    SupabaseAuth.logout(MainActivity.this);
                    refreshSignInButton();
                    Intent ri = new Intent(
                            MainActivity.this, VoiceService.class);
                    ri.setAction("RESTART_REALTIME");
                    startForegroundService(ri);
                })
                .show();
    }

    private void showDeleteKeyDialog() {
        final android.widget.EditText keyIn =
                new android.widget.EditText(this);
        keyIn.setHint("Voice delete key");
        keyIn.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyIn.setText(getSharedPreferences("selena_voice", MODE_PRIVATE)
                .getString("voice_delete_key", ""));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete key (server DELETE credential)")
                .setView(keyIn)
                .setPositiveButton("Save", (d, w) -> {
                    getSharedPreferences("selena_voice", MODE_PRIVATE)
                            .edit()
                            .putString("voice_delete_key",
                                    keyIn.getText().toString().trim())
                            .apply();
                    android.widget.Toast.makeText(MainActivity.this,
                            "Delete key saved",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void downloadAndInstallApk(String apkUrl) {
        updateBanner.setEnabled(false);
        updateBanner.setText("⬇️ Downloading update…");
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(apkUrl);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                int code = conn.getResponseCode();
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                java.io.File outFile = new java.io.File(
                        getExternalFilesDir(
                                android.os.Environment.DIRECTORY_DOWNLOADS),
                        "selena-voice-update.apk");
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.FileOutputStream out =
                             new java.io.FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                conn.disconnect();
                VoiceService.log("updater: downloaded " + outFile.length()
                        + " bytes -> " + outFile.getAbsolutePath());
                runOnUiThread(() -> {
                    updateBanner.setText("⬆️ Update available — tap to install");
                    updateBanner.setEnabled(true);
                    installApk(outFile);
                });
            } catch (Exception e) {
                VoiceService.log("updater: download failed: " + e);
                runOnUiThread(() -> {
                    updateBanner.setText("⚠️ Download failed — tap to retry");
                    updateBanner.setEnabled(true);
                });
            }
        }).start();
    }

    private void installApk(java.io.File apkFile) {
        try {
            // Android 8+: the system prompts for the per-app "install
            // unknown apps" grant on first use — no code needed.
            android.net.Uri uri = android.net.Uri.parse(
                    "content://com.selena.voice.apkprovider/"
                            + apkFile.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri,
                    "application/vnd.android.package-archive");
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
            VoiceService.log("updater: install intent fired");
        } catch (Exception e) {
            VoiceService.log("updater: install intent failed: " + e);
            Toast.makeText(this, "Couldn't start installer",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean isPhoneVoiceMode() {
        return VoiceService.VOICE_PHONE.equals(
                getSharedPreferences("selena_voice", MODE_PRIVATE)
                        .getString(VoiceService.KEY_VOICE_MODE,
                                VoiceService.VOICE_PHONE));
    }

    private void updateVoiceModeLabel() {
        final boolean phone = isPhoneVoiceMode();
        runOnUiThread(() -> voiceModeLabel.setText(
                phone ? "Voice: Phone (fast)" : "Voice: Achernar"));
    }

    /** Nick 2026-10-19 v1.17: reflect the mutually-exclusive output prefs
     * in both switches. setChecked() only fires the listener when the
     * value changes, so this can't recurse. */
    private void syncOutputSwitches() {
        btSwitch.setChecked(VoiceService.getForceBluetooth(this));
        spkSwitch.setChecked(VoiceService.getForceSpeaker(this));
    }

    private void requestPermissions() {
        // v1.82: POST_NOTIFICATIONS is the only runtime permission
        // this app asks for anymore (RECORD_AUDIO and READ_PHONE_STATE
        // left with the microphone and the phone-call mic mute).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void startVoiceService() {
        Intent i = new Intent(this, VoiceService.class);
        startForegroundService(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(VoiceService.ACTION_MESSAGE);
        f.addAction(VoiceService.ACTION_POLL_STATUS);
        f.addAction(VoiceService.ACTION_PLAYBACK_STATE);
        f.addAction(VoiceService.ACTION_TOAST);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        // Nick 2026-10-19 v1.16: no more activity-owned loops. The service
        // keeps polling/heartbeat/realtime alive with the screen locked;
        // the activity only renders broadcasts while visible.
        // Nick 2026-10-21 v1.24: load persistent message history on open
        // (catches messages that arrived while backgrounded).
        MessageHistory.purgeExpired();
        renderHistory();
        // Nick 2026-10-22 v1.25: pull the service's LIVE status on open —
        // the layout defaults ("Poll: not started") go stale when broadcasts
        // fire while backgrounded.
        Intent statusReq = new Intent(this, VoiceService.class);
        statusReq.setAction(VoiceService.ACTION_REQUEST_STATUS);
        startService(statusReq);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ UI helpers

    // Nick 2026-10-21 v1.24: render persistent message history with
    // replay + lock buttons. Called on resume and when new messages arrive.
    private void renderHistory(boolean scrollToNewest) {
        runOnUiThread(() -> {
            messageList.removeAllViews();
            java.util.List<MessageHistory.Entry> entries = MessageHistory.getAll();
            if (entries.isEmpty()) {
                android.widget.TextView empty = new android.widget.TextView(this);
                empty.setText("Waiting for messages…");
                empty.setTextSize(14);
                empty.setTextColor(getResources().getColor(R.color.selena_dark));
                empty.setPadding(12, 12, 12, 12);
                messageList.addView(empty);
                return;
            }
            for (MessageHistory.Entry e : entries) {
                messageList.addView(buildMessageCard(e));
            }
            // Nick 2026-10-22 v1.26: only jump to the newest message when a
            // NEW message arrives — never on lock toggles or resume.
            if (scrollToNewest) {
                outerScroll.post(() -> outerScroll.fullScroll(android.view.View.FOCUS_DOWN));
            }
        });
    }

    private void renderHistory() {
        renderHistory(false);
    }

    private android.view.View buildMessageCard(MessageHistory.Entry e) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(12, 12, 12, 12);
        // Nick 2026-10-23 v1.46: his dictations are visually distinct —
        // light purple card with a "you said" header. Locked tint wins.
        card.setBackgroundColor(e.locked ? 0xFFFFF3D6
                : (e.fromNick ? 0xFFF2E9FF : 0xFFF5F5F5));
        android.widget.LinearLayout.LayoutParams cp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cp);

        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(e.text);
        tv.setTextSize(14);
        tv.setTextColor(getResources().getColor(R.color.selena_dark));
        card.addView(tv);

        // Nick 2026-10-23 v1.46: sender header — his dictations read as
        // "🎤 You said:" in purple, clearly distinct from her replies.
        if (e.fromNick) {
            android.widget.TextView sender = new android.widget.TextView(this);
            sender.setText("🎤 You said:");
            sender.setTextSize(12);
            sender.setTypeface(null, android.graphics.Typeface.BOLD);
            sender.setTextColor(0xFF6B4FA1); // selena_purple
            card.addView(sender, 0);
        }

        // Timestamp + buttons row
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 0);

        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("M/d h:mm a",
            java.util.Locale.US);
        android.widget.TextView ts = new android.widget.TextView(this);
        ts.setText(fmt.format(new java.util.Date(e.timestamp)));
        ts.setTextSize(11);
        ts.setTextColor(0xFF888888);
        android.widget.LinearLayout.LayoutParams tsp =
            new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ts.setLayoutParams(tsp);
        row.addView(ts);

        // Lock toggle — Nick 2026-10-22 v1.26: explicit words, not just emoji.
        android.widget.Button lockBtn = new android.widget.Button(this);
        lockBtn.setText(e.locked ? "🔒 LOCKED" : "🔓 Lock");
        lockBtn.setOnClickListener(v -> {
            MessageHistory.setLocked(e.id, !e.locked);
            renderHistory();
        });
        row.addView(lockBtn);

        // Replay button (only if MP3 exists)
        if (e.audioPath != null) {
            java.io.File f = new java.io.File(e.audioPath);
            if (f.exists()) {
                android.widget.Button replayBtn = new android.widget.Button(this);
                replayBtn.setText("▶ Replay");
                replayBtn.setOnClickListener(v -> {
                    android.content.Intent i = new android.content.Intent(this,
                        VoiceService.class);
                    i.setAction(VoiceService.ACTION_REPLAY);
                    i.putExtra(VoiceService.EXTRA_FILE_PATH, e.audioPath);
                    startService(i);
                });
                row.addView(replayBtn);
            }
        }
        card.addView(row);
        return card;
    }

    /**
     * Nick 2026-10-23 v1.48: one-time earbuds setup. Long-press the
     * version text to open this. Enter the Bluetooth name of Nick's
     * earbuds (as shown in Android BT settings). The name drives the
     * earbuds-only playback lane (v1.73).
     */
    private void showEarbudsSetupDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Earbuds Bluetooth name");
        // Pre-fill with current saved value, if any.
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences("selena_voice_prefs", MODE_PRIVATE);
            String cur = prefs.getString("earbuds_device_name", "");
            if (cur != null) input.setText(cur);
        } catch (Exception ignored) {}
        new android.app.AlertDialog.Builder(this)
                .setTitle("Earbuds device")
                .setMessage("Enter the Bluetooth name of your earbuds "
                        + "(as shown in Android Bluetooth settings). "
                        + "This name drives earbuds-only playback: "
                        + "with that switch on, she plays only into these "
                        + "earbuds and stays silent while they're away.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    getSharedPreferences("selena_voice_prefs", MODE_PRIVATE)
                            .edit().putString("earbuds_device_name", name).apply();
                    android.widget.Toast.makeText(this,
                            name.isEmpty() ? "Earbuds cleared"
                                    : "Earbuds set: " + name,
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
