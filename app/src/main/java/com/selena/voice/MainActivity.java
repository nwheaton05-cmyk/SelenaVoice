package com.selena.voice;

import android.Manifest;
import android.animation.ObjectAnimator;
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
    private ScrollView logScroll;
    private Switch alwaysOnSwitch;
    private View pulseDot;
    private ObjectAnimator pulseAnimator;

    private final MessageReceiver receiver;

    // Nick 2026-10-18 v1.8: the mic button is a real on/off toggle (manual
    // fallback when always-listening is off). One press starts, next stops.
    private Button micButton;
    private boolean manualMicOn = false;

    // Nick 2026-10-21: pause/resume button for playback.
    private Button pauseButton;
    private boolean playbackPaused = false;

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
        renderHistory();
        runOnUiThread(() ->
                Toast.makeText(this, "Sent to Selena", Toast.LENGTH_SHORT).show());
    }

    /** Called by MessageReceiver when speech is detected (pulse the dot). */
    public void onSpeechDetected() {
        pulse();
    }

    /** Called by MessageReceiver when the listening state changes. */
    public void onListeningState(boolean listening) {
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
        runOnUiThread(() -> {
            try {
                android.widget.Toast.makeText(this, msg,
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
        // Nick 2026-10-21 v1.24: persistent message history with replay.
        MessageHistory.init(this);
        messageList = findViewById(R.id.messageList);
        logScroll = findViewById(R.id.logScroll);
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

        alwaysOnSwitch.setChecked(VoiceService.getAlwaysListening(this));
        alwaysOnSwitch.setOnCheckedChangeListener((v, on) -> {
            VoiceService.setAlwaysListening(this, on);
            if (on) {
                // The switch owns the mic now — drop any manual toggle state.
                manualMicOn = false;
            }
            updateMicButton();
        });

        micButton.setOnClickListener(v -> {
            if (alwaysOnSwitch.isChecked()) {
                Toast.makeText(this, "Already always listening — mic is live",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!manualMicOn) {
                Intent i = new Intent(this, VoiceService.class);
                i.setAction("MANUAL_LISTEN");
                startForegroundService(i);
                manualMicOn = true;
                VoiceService.log("mic: manual toggle ON");
                Toast.makeText(this, "Listening… tap mic again to stop",
                        Toast.LENGTH_SHORT).show();
            } else {
                Intent i = new Intent(this, VoiceService.class);
                i.setAction("MANUAL_STOP");
                startForegroundService(i);
                manualMicOn = false;
                VoiceService.log("mic: manual toggle OFF");
                Toast.makeText(this, "Mic off", Toast.LENGTH_SHORT).show();
            }
            updateMicButton();
        });

        // Nick 2026-10-18 v1.11: voice mode toggle — Phone (fast, on-device
        // TTS, default) vs Aria (quality, server-generated MP3). Stored in
        // SharedPreferences so it survives restarts; the service reads the
        // same key when handling each message.
        voiceModeLabel = findViewById(R.id.voiceModeLabel);
        voiceModeSwitch = findViewById(R.id.voiceModeSwitch);
        voiceModeSwitch.setChecked(isPhoneVoiceMode());
        updateVoiceModeLabel();
        voiceModeSwitch.setOnCheckedChangeListener((v, phone) -> {
            getSharedPreferences("selena_voice", MODE_PRIVATE).edit()
                    .putString(VoiceService.KEY_VOICE_MODE,
                            phone ? VoiceService.VOICE_PHONE : VoiceService.VOICE_ARIA)
                    .apply();
            updateVoiceModeLabel();
            VoiceService.log("voice: mode=" + (phone ? "phone (fast)" : "aria (quality)"));
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
        // Service starts after permission grant (see onRequestPermissionsResult).
        // If already granted, start now.
        if (hasAllPermissions()) {
            startVoiceService();
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
                phone ? "Voice: Phone (fast)" : "Voice: Aria (quality)"));
    }

    /** Nick 2026-10-19 v1.17: reflect the mutually-exclusive output prefs
     * in both switches. setChecked() only fires the listener when the
     * value changes, so this can't recurse. */
    private void syncOutputSwitches() {
        btSwitch.setChecked(VoiceService.getForceBluetooth(this));
        spkSwitch.setChecked(VoiceService.getForceSpeaker(this));
    }

    private boolean hasAllPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (hasAllPermissions()) {
            startVoiceService();
        } else {
            Toast.makeText(this,
                    "Selena Voice needs mic + notification permissions to work",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
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
                logScroll.post(() -> logScroll.fullScroll(android.view.View.FOCUS_DOWN));
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
        card.setBackgroundColor(e.locked ? 0xFFFFF3D6 : 0xFFF5F5F5);
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
}
