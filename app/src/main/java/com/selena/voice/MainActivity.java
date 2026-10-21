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
    private TextView logText;
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
        appendLogHtml("Selena", text, COLOR_SELENA);
    }

    /** Called by MessageReceiver when Nick's voice input was accepted. */
    public void onInputSent(String text) {
        VoiceService.log("log: showing Nick's message");
        appendLogHtml("You", text, COLOR_NICK);
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
     * Nick 2026-10-21: playback pause button state. The button is visible
     * only while audio is playing or paused. Label toggles ⏸️/▶️.
     */
    public void onPlaybackState(boolean playing, boolean paused) {
        playbackPaused = paused;
        runOnUiThread(() -> {
            if (playing || paused) {
                pauseButton.setVisibility(android.view.View.VISIBLE);
                pauseButton.setText(paused ? "▶️ Resume" : "⏸️ Pause");
            } else {
                pauseButton.setVisibility(android.view.View.GONE);
            }
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
        try {
            String vn = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.versionText)).setText("v" + vn);
        } catch (Exception ignored) {}
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        alwaysOnSwitch = findViewById(R.id.alwaysOnSwitch);
        pulseDot = findViewById(R.id.pulseDot);
        micButton = findViewById(R.id.micButton);
        pauseButton = findViewById(R.id.pauseButton);

        // Nick 2026-10-21: pause/resume button for playback. Toggles between
        // PAUSE_PLAYBACK and RESUME_PLAYBACK intents. Visibility driven by
        // the service's ACTION_PLAYBACK_STATE broadcasts.
        pauseButton.setOnClickListener(v -> {
            Intent i = new Intent(this, VoiceService.class);
            i.setAction(playbackPaused ? "RESUME_PLAYBACK" : "PAUSE_PLAYBACK");
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ UI helpers

    private void appendLogHtml(String who, String text, String color) {
        final CharSequence html = Html.fromHtml(
                "<font color=\"" + color + "\"><b>" + who + ":</b> "
                        + TextUtils.htmlEncode(text) + "</font><br><br>",
                Html.FROM_HTML_MODE_LEGACY);
        runOnUiThread(() -> {
            logText.append(html);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
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
