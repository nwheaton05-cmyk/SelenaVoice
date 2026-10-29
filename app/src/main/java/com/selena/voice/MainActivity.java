package com.selena.voice;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
 * Nick 2026-10-19 v1.16: BACKGROUND OPERATION. The activity is now a pure
 * UI shell — status line, always-listening toggle, pulse dot, message log,
 * mic button fallback, voice-mode toggle, version label. ALL voice work
 * (Realtime push, queue polling, heartbeat, MP3/TTS playback, mic) lives
 * in VoiceService, which keeps running with the screen locked. The
 * activity just starts the service and renders the service's broadcasts.
 */
public class MainActivity extends Activity {
    private static final String TAG = "SelenaVoice";

    private TextView statusText;
    private TextView pollStatusText;
    private android.widget.LinearLayout messageList;
    // Nick 2026-10-23 v1.38: was the inner message-box scroller; the box
    // now flows in the page scroll, so this is the outer ScrollView.
    private ScrollView outerScroll;
    private Switch alwaysOnSwitch;
    private View pulseDot;
    private ObjectAnimator pulseAnimator;

    private final MessageReceiver receiver;

    // Nick 2026-10-18 v1.8: the mic button is a real on/off toggle (manual
    // fallback when always-listening is off). One press starts, next stops.
    private Button micButton;
    private boolean manualMicOn = false;

    // Nick 2026-10-23 v1.46: live dictation popup — shows exactly what
    // Nick is dictating in real time (one-shot mic, or always-listening).
    private android.app.Dialog dictationDialog;
    private TextView dictationTextView;

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
    private Button voiceprintButton;
    private Button machinesButton;
    // v1.65 (2026-10-29): the text box (offline workflow). The field
    // mirrors the PREF_HELD_TEXT preference the service stacks into;
    // the activity is the field's editor + manual sender.
    private android.widget.EditText heldTextBox;
    private android.content.SharedPreferences heldPrefs;
    private boolean heldBoxSelfWrite = false;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener
            heldListener = (prefs, key) -> {
                if (VoiceService.PREF_HELD_TEXT.equals(key)
                        && !heldBoxSelfWrite) {
                    runOnUiThread(this::refreshHeldTextBox);
                }
            };

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

    public MainActivity() {
        receiver = new MessageReceiver(this);
    }

    // Nick 2026-10-18 v1.9: the log shows the full conversation. Nick's own
    // words appear as "You:" in muted purple; Selena's replies as "Selena:"
    // in dark. Nick's inputs are NEVER spoken aloud — the audio queue only
    // ever plays audioUrl MP3s from /api/selena-voice/queue (Selena's
    // replies). His inputs go to /api/selena-voice/input as text only.
    private static final String COLOR_SELENA = "#2D2A32"; // selena_dark
    private static final String COLOR_NICK = "#6B4FA1";   // selena_purple

    /** Called by MessageReceiver when Selena has a new voice message. */
    public void onSelenaMessage(String text) {
        // Nick 2026-10-21 v1.24: history is the UI now; re-render.
        // Nick 2026-10-22 v1.26: new message — scroll to it.
        renderHistory(true);
    }

    /** Called by MessageReceiver when Nick's voice input was accepted. */
    public void onInputSent(String text) {
        VoiceService.log("log: showing Nick's message");
        // Nick 2026-10-21 v1.24: voice inputs aren't in history; just re-render.
        // Nick 2026-10-23 v1.46: they ARE now — from-Nick history entries.
        // The sentence landed below: clear the popup for the next one.
        // Toast only for one-shot; always-listening would spam it per sentence.
        clearDictationPopup();
        renderHistory();
        if (manualMicOn) {
            runOnUiThread(() ->
                    Toast.makeText(this, "Sent to Selena", Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Nick 2026-10-23 v1.46: live dictation text from the service
     * (partial results + in-progress sentence). Shows/updates the popup.
     */
    public void onPartialInput(String text) {
        if (text == null) return;
        // One-shot: the popup was shown on mic-ON. Always-listening: pop
        // it up on first live dictation so it doesn't sit empty while idle.
        if (dictationDialog == null && VoiceService.getAlwaysListening(this)) {
            showDictationPopup();
        }
        updateDictationPopup(text);
    }

    /** Nick 2026-10-23 v1.46: show the live dictation popup. */
    private void showDictationPopup() {
        if (dictationDialog != null || isFinishing()) return;
        runOnUiThread(() -> {
            if (dictationDialog != null || isFinishing()) return;
            android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (24 * getResources().getDisplayMetrics().density);
            layout.setPadding(pad, pad, pad, pad);

            TextView status = new TextView(this);
            status.setText("🎙️ Listening…");
            status.setTextSize(14);
            status.setTextColor(0xFF888888);
            layout.addView(status);

            dictationTextView = new TextView(this);
            dictationTextView.setText("");
            dictationTextView.setTextSize(22);
            dictationTextView.setTextColor(getResources().getColor(R.color.selena_dark));
            dictationTextView.setPadding(0, pad / 2, 0, 0);
            layout.addView(dictationTextView);

            android.app.AlertDialog dialog =
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Dictation")
                            .setView(layout)
                            .create();
            dialog.setCanceledOnTouchOutside(true);
            dialog.setOnDismissListener(d -> {
                dictationDialog = null;
                dictationTextView = null;
            });
            dictationDialog = dialog;
            dialog.show();
            // Wide popup — dictation text needs room.
            android.view.Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            }
        });
    }

    /** Nick 2026-10-23 v1.46: update the popup's live text. */
    private void updateDictationPopup(String text) {
        runOnUiThread(() -> {
            if (dictationTextView != null) {
                dictationTextView.setText(text);
            }
        });
    }

    /** Nick 2026-10-23 v1.46: blank the popup for the next sentence. */
    private void clearDictationPopup() {
        updateDictationPopup("");
    }

    /** Nick 2026-10-23 v1.46: take the popup down. */
    private void dismissDictationPopup() {
        runOnUiThread(() -> {
            if (dictationDialog != null) {
                try {
                    dictationDialog.dismiss();
                } catch (Exception ignored) {}
                dictationDialog = null;
                dictationTextView = null;
            }
        });
    }

    /** Called by MessageReceiver when speech is detected (pulse the dot). */
    public void onSpeechDetected() {
        pulse();
    }

    /** Called by MessageReceiver when the listening state changes. */
    public void onListeningState(boolean listening) {
        onListeningState(listening, null);
    }

    /**
     * Nick 2026-10-28 v1.52: while the noise gate holds the recognizer
     * off, the status line says so — a working gate must never look
     * like a broken mic (spec §4.5). The mic button still forces a
     * session open, which is the discoverable override.
     */
    public void onListeningState(boolean listening, String gateState) {
        if ("killed".equals(gateState) || "noise_hold".equals(gateState)) {
            final String text = "noise_hold".equals(gateState)
                    ? "🚜 Paused — loud noise (dictation held)"
                    : "🚜 Paused — loud noise";
            runOnUiThread(() -> statusText.setText(text));
            return;
        }
        updateListeningUi(listening);
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
        // BT device name (one-time setup for voice-interrupt mode).
        findViewById(R.id.versionText).setOnLongClickListener(v -> {
            showEarbudsSetupDialog();
            return true;
        });
        // Nick 2026-10-21 v1.24: persistent message history with replay.
        MessageHistory.init(this);
        messageList = findViewById(R.id.messageList);
        outerScroll = findViewById(R.id.outerScroll);
        alwaysOnSwitch = findViewById(R.id.alwaysOnSwitch);
        pulseDot = findViewById(R.id.pulseDot);
        micButton = findViewById(R.id.micButton);
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
        voiceprintButton = findViewById(R.id.voiceprintButton);
        voiceprintButton.setEnabled(true);
        voiceprintButton.setOnClickListener(v -> showVoiceprintDialog());
        machinesButton = findViewById(R.id.machinesButton);
        machinesButton.setEnabled(true);
        machinesButton.setOnClickListener(v -> showMachinesDialog());
        refreshSignInButton();

        // v1.65: text box wiring (offline workflow).
        heldPrefs = getSharedPreferences(VoiceService.PREFS, MODE_PRIVATE);
        heldTextBox = findViewById(R.id.heldTextBox);
        Button heldSendButton = findViewById(R.id.heldSendButton);
        refreshHeldTextBox();
        heldPrefs.registerOnSharedPreferenceChangeListener(heldListener);
        heldSendButton.setOnClickListener(v -> sendHeldText());

        // Nick 2026-10-22 v1.33: in-app auto-updater. Hidden until the
        // version check finds a newer APK — v1.33 is the last manual
        // install.
        updateBanner = findViewById(R.id.updateBanner);
        updateBanner.setVisibility(View.GONE);
        checkForAppUpdate();

        alwaysOnSwitch.setChecked(VoiceService.getAlwaysListening(this));
        alwaysOnSwitch.setOnCheckedChangeListener((v, on) -> {
            // Nick 2026-10-23 v1.41: don't auto-start mic listening until
            // RECORD_AUDIO is granted. Revert the flip, prompt, retry on grant.
            if (on && !hasMicPermission()) {
                alwaysOnSwitch.setChecked(false);
                pendingMicAction = "always";
                ensureMicPermission();
                return;
            }
            VoiceService.setAlwaysListening(this, on);
            if (on) {
                // The switch owns the mic now — drop any manual toggle state.
                manualMicOn = false;
            } else {
                // Nick 2026-10-23 v1.46: always-listening off — popup down.
                dismissDictationPopup();
            }
            updateMicButton();
        });

        micButton.setOnClickListener(v -> {
            if (alwaysOnSwitch.isChecked()) {
                Toast.makeText(this, "Already always listening — mic is live",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // Nick 2026-10-23 v1.41: no mic without RECORD_AUDIO — prompt
            // first, engage on grant.
            if (!hasMicPermission()) {
                pendingMicAction = "manual";
                ensureMicPermission();
                return;
            }
            if (!manualMicOn) {
                Intent i = new Intent(this, VoiceService.class);
                i.setAction("MANUAL_LISTEN");
                startForegroundService(i);
                manualMicOn = true;
                VoiceService.log("mic: manual toggle ON");
                // Nick 2026-10-23 v1.46: live dictation popup.
                showDictationPopup();
                Toast.makeText(this, "Listening… tap mic again to stop",
                        Toast.LENGTH_SHORT).show();
            } else {
                Intent i = new Intent(this, VoiceService.class);
                i.setAction("MANUAL_STOP");
                startForegroundService(i);
                manualMicOn = false;
                VoiceService.log("mic: manual toggle OFF");
                // Nick 2026-10-23 v1.46: dictation done — popup down. Any
                // in-progress sentence still flushes to history via the
                // service's sentence timer.
                dismissDictationPopup();
                Toast.makeText(this, "Mic off", Toast.LENGTH_SHORT).show();
            }
            updateMicButton();
        });

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

        requestPermissions();
        // Nick 2026-10-23 v1.41: the service starts as mediaPlayback, which
        // needs NO runtime permission — playback, polling, and the queue
        // work with the mic denied. Mic listening only engages after
        // RECORD_AUDIO is granted (the service refuses otherwise).
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

    /**
     * Nick 2026-10-28 v1.53 (Phase 4, spec §5): voiceprint status +
     * per-profile reset. The reset itself runs in VoiceService (the
     * RESET_VOICEPRINT intent) because the service owns the live
     * profile; this dialog only reports status and asks.
     */
    private void showVoiceprintDialog() {
        String status = Voiceprint.statusLine(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Learned voiceprint")
                .setMessage(status
                        + "\n\nThe voiceprint only ever makes the noise gate MORE"
                        + " willing to hear you — it can never block you. Reset"
                        + " deletes this account's learned profile and its"
                        + " server archive; learning starts over from scratch.")
                .setPositiveButton("Reset voiceprint", (d, w) -> {
                    Intent ri = new Intent(
                            MainActivity.this, VoiceService.class);
                    ri.setAction("RESET_VOICEPRINT");
                    startForegroundService(ri);
                    android.widget.Toast.makeText(MainActivity.this,
                            "Voiceprint reset",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    /**
     * Nick 2026-10-29 v1.61: the learned machine-sound library. The
     * app names new machines by VOICE first (it asks after the noise
     * stops); this dialog is the fallback label path — tap a machine
     * to rename it. Renaming here counts as named: the voice prompts
     * stop for that machine.
     */
    private void showMachinesDialog() {
        java.util.List<MachineProfiles.Profile> profiles =
                MachineProfiles.listForUi(this);
        if (profiles == null || profiles.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Machine sounds")
                    .setMessage("No machine sounds learned yet.\n\n"
                            + "When something loud and steady runs — the"
                            + " mower, a weed whacker, a blower — the app"
                            + " learns its sound as its own profile, and"
                            + " after it stops she'll ask you what it was.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String[] labels = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            labels[i] = MachineProfiles.describe(profiles.get(i));
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Machine sounds (tap to rename)")
                .setItems(labels, (d, which) ->
                        showMachineRenameDialog(profiles.get(which)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showMachineRenameDialog(MachineProfiles.Profile p) {
        final android.widget.EditText nameIn =
                new android.widget.EditText(this);
        nameIn.setHint("Machine name");
        nameIn.setText(p.name);
        nameIn.setSelection(nameIn.getText().length());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Name this machine")
                .setView(nameIn)
                .setPositiveButton("Save", (d, w) -> {
                    String name = nameIn.getText().toString().trim();
                    if (!name.isEmpty()
                            && MachineProfiles.renameForUi(this, p.id, name)) {
                        android.widget.Toast.makeText(MainActivity.this,
                                "Saved: " + name,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
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

    private static final int REQ_MIC = 1;
    // Nick 2026-10-23 v1.41: a mic action the user tapped while RECORD_AUDIO
    // was missing — retried once the permission is granted ("always" =
    // the always-listen switch, "manual" = the mic button).
    private String pendingMicAction = null;

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean micAskedBefore() {
        return getPreferences(MODE_PRIVATE).getBoolean("mic_asked", false);
    }

    private void setMicAskedBefore() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("mic_asked", true).apply();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Nick 2026-10-23 v1.41: the service is already running (started in
        // onCreate as mediaPlayback — no permission needed). Mic features
        // engage only if granted; a denial never crashes anything.
        if (hasMicPermission()) {
            if (pendingMicAction != null) {
                String a = pendingMicAction;
                pendingMicAction = null;
                engageMicAction(a);
            } else if (alwaysOnSwitch.isChecked()) {
                // Service-side auto-start refused while denied; user intent
                // (switch on) stands, so engage now that it's granted.
                VoiceService.setAlwaysListening(this, true);
            }
        } else {
            Toast.makeText(this,
                    "Mic permission denied — voice input stays off. "
                            + "Enable it in Settings > Apps > Selena Voice > Permissions.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Retry a mic action the user tapped before the permission arrived. */
    private void engageMicAction(String action) {
        if ("always".equals(action)) {
            // Fires the switch listener -> setAlwaysListening(true) -> service.
            alwaysOnSwitch.setChecked(true);
        } else {
            Intent i = new Intent(this, VoiceService.class);
            i.setAction("MANUAL_LISTEN");
            startForegroundService(i);
            manualMicOn = true;
            // Nick 2026-10-23 v1.46: permission-grant path — popup too.
            showDictationPopup();
            updateMicButton();
        }
    }

    /**
     * Nick 2026-10-23 v1.41: surface the RECORD_AUDIO prompt. First ask goes
     * through the system dialog; after a denial we explain and ask again;
     * once the user has blocked it ("don't ask again" / Settings revoke),
     * only Settings can fix it — take them there.
     */
    public void ensureMicPermission() {
        if (hasMicPermission()) return;
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this,
                    "Selena Voice needs the mic to hear you — please allow it",
                    Toast.LENGTH_LONG).show();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else if (!micAskedBefore()) {
            setMicAskedBefore();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            Toast.makeText(this,
                    "Mic is blocked — enable it in Settings > Apps > Selena Voice > Permissions",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        // v1.72: call-state watch (the phone-call mic mute). Asked
        // alongside the mic; if refused, the service logs it once
        // and the mute simply stays inactive — nothing else changes.
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 1);
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
        f.addAction(VoiceService.ACTION_INPUT_SENT);
        f.addAction(VoiceService.ACTION_SPEECH_DETECTED);
        f.addAction(VoiceService.ACTION_LISTENING_STATE);
        // Nick 2026-10-23 v1.46: live dictation popup text.
        f.addAction(VoiceService.ACTION_PARTIAL_INPUT);
        // Nick 2026-10-23 v1.41: service asks the activity to prompt for mic.
        f.addAction(VoiceService.ACTION_REQUEST_MIC_PERMISSION);
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
        // Nick 2026-10-18 v1.8: if the activity was recreated while the
        // service was in manual-listen mode, pick up the real state.
        manualMicOn = VoiceService.isManualListening();
        updateMicButton();
        refreshHeldTextBox(); // v1.65: pick up anything stacked while away
        // Nick 2026-10-23 v1.46: recreate the dictation popup if a manual
        // session was already running (the service's sentence buffer and
        // partials will refill it).
        if (manualMicOn) {
            showDictationPopup();
        }
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
        persistHeldTextBox(); // v1.65: the user's edits are the field truth
        // Nick 2026-10-23 v1.46: popup is activity-scoped — take it down
        // to avoid a window leak. Re-shown on resume if still listening.
        dismissDictationPopup();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ v1.65 text box

    /** Mirror the persisted held text into the field (no-op when
     *  already in sync, so typing is never clobbered by our own
     *  writes). */
    private void refreshHeldTextBox() {
        if (heldTextBox == null || heldPrefs == null) return;
        String held = heldPrefs.getString(VoiceService.PREF_HELD_TEXT, "");
        if (held == null) held = "";
        if (!held.equals(heldTextBox.getText().toString())) {
            heldTextBox.setText(held);
            try {
                heldTextBox.setSelection(held.length());
            } catch (Exception ignored) {}
        }
    }

    /** Persist the field's current content (edits and clears are
     *  authoritative — the reconnect announcement keys off this). */
    private void persistHeldTextBox() {
        if (heldTextBox == null || heldPrefs == null) return;
        String cur = heldTextBox.getText().toString();
        String held = heldPrefs.getString(VoiceService.PREF_HELD_TEXT, "");
        if (held == null) held = "";
        if (!cur.equals(held)) {
            heldBoxSelfWrite = true;
            try {
                if (cur.trim().isEmpty()) {
                    heldPrefs.edit().remove(VoiceService.PREF_HELD_TEXT).apply();
                } else {
                    heldPrefs.edit()
                            .putString(VoiceService.PREF_HELD_TEXT, cur).apply();
                }
            } finally {
                heldBoxSelfWrite = false;
            }
        }
    }

    /**
     * The manual send — the ONLY way text leaves the box
     * (2026-10-29 disposition rule: no auto-delivery, ever). Sends
     * the field's whole content as one input post, mirroring
     * VoiceService.doPostInput's wire shape. Success clears the box;
     * failure keeps everything held and says so.
     */
    private void sendHeldText() {
        if (heldTextBox == null) return;
        final String text = heldTextBox.getText().toString().trim();
        if (text.isEmpty()) {
            android.widget.Toast.makeText(this, "The text box is empty",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL(
                        VoiceService.BASE_URL + "/api/selena-voice/input");
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("text", text);
                body.put("timestamp", System.currentTimeMillis());
                java.io.OutputStream os = conn.getOutputStream();
                try {
                    os.write(body.toString().getBytes("UTF-8"));
                } finally {
                    try { os.close(); } catch (Exception ignored) {}
                }
                int code = conn.getResponseCode();
                ok = code == 200 || code == 201;
            } catch (Exception e) {
                ok = false;
            } finally {
                if (conn != null) conn.disconnect();
            }
            final boolean sent = ok;
            runOnUiThread(() -> {
                if (sent) {
                    // Nick 2026-10-29 15:19: typed sends are recorded
                    // in the window's conversation record, in order,
                    // alongside dictations — same MessageHistory the
                    // dictation path writes — and rendered at once.
                    MessageHistory.addNick(text);
                    renderHistory(true);
                    heldBoxSelfWrite = true;
                    try {
                        heldPrefs.edit()
                                .remove(VoiceService.PREF_HELD_TEXT).apply();
                    } finally {
                        heldBoxSelfWrite = false;
                    }
                    heldTextBox.setText("");
                    android.widget.Toast.makeText(this, "Sent",
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    persistHeldTextBox();
                    android.widget.Toast.makeText(this,
                            "Still offline — your words stay in the text box",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }).start();
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

    private void updateListeningUi(boolean listening) {
        runOnUiThread(() -> statusText.setText(
                listening ? "🎙️ Listening for Selena…" : "⏸️ Paused — toggle to resume"));
    }

    /**
     * Nick 2026-10-18: the mic button is a real toggle. Green = manual
     * listening is ON (tap to stop), purple/default = OFF (tap to talk).
     * When always-listening owns the mic, the button stays in its OFF look.
     */
    private void updateMicButton() {
        runOnUiThread(() -> {
            if (manualMicOn) {
                micButton.setText("🎤 ON\ntap to stop");
                micButton.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF2E7D32));
            } else {
                micButton.setText("🎤");
                micButton.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF6B4FA1));
            }
        });
    }

    /** Nick 2026-10-18: visual pulse so he knows the app hears him. */
    private void pulse() {
        runOnUiThread(() -> {
            if (pulseAnimator != null && pulseAnimator.isRunning()) return;
            pulseAnimator = ObjectAnimator.ofFloat(pulseDot, "alpha", 1f, 0.2f, 1f);
            pulseAnimator.setDuration(600);
            pulseAnimator.start();
        });
    }

    /**
     * Nick 2026-10-23 v1.48: one-time earbuds setup. Long-press the
     * version text to open this. Enter the Bluetooth name of Nick's
     * earbuds (as shown in Android BT settings). When this device is
     * connected, Selena uses voice-interrupt (mic stays on during her
     * speech); otherwise tap-to-interrupt.
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
                        + "When connected, you can interrupt Selena by voice.")
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
