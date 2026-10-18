package com.selena.voice;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
 * - Status line, always-listening toggle, pulse dot (flashes when speech
 *   is detected), message log, and a mic button as manual fallback.
 * - Starts the VoiceService foreground service on launch.
 * - Requests RECORD_AUDIO + POST_NOTIFICATIONS at runtime.
 */
public class MainActivity extends Activity {
    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;
    private Switch alwaysOnSwitch;
    private View pulseDot;
    private ObjectAnimator pulseAnimator;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (VoiceService.ACTION_MESSAGE.equals(action)) {
                appendLog("Selena: " + intent.getStringExtra(VoiceService.EXTRA_TEXT));
            } else if (VoiceService.ACTION_INPUT_SENT.equals(action)) {
                appendLog("You: " + intent.getStringExtra(VoiceService.EXTRA_TEXT));
                Toast.makeText(MainActivity.this, "Sent to Selena", Toast.LENGTH_SHORT).show();
            } else if (VoiceService.ACTION_SPEECH_DETECTED.equals(action)) {
                pulse();
            } else if (VoiceService.ACTION_LISTENING_STATE.equals(action)) {
                updateListeningUi(intent.getBooleanExtra(VoiceService.EXTRA_STATE, false));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        alwaysOnSwitch = findViewById(R.id.alwaysOnSwitch);
        pulseDot = findViewById(R.id.pulseDot);
        Button micButton = findViewById(R.id.micButton);

        alwaysOnSwitch.setChecked(VoiceService.getAlwaysListening(this));
        alwaysOnSwitch.setOnCheckedChangeListener((v, on) ->
                VoiceService.setAlwaysListening(this, on));

        micButton.setOnClickListener(v -> {
            Intent i = new Intent(this, VoiceService.class);
            i.setAction("MANUAL_LISTEN");
            startForegroundService(i);
            Toast.makeText(this, "Listening… speak now", Toast.LENGTH_SHORT).show();
        });

        requestPermissions();
        // Service starts after permission grant (see onRequestPermissionsResult).
        // If already granted, start now.
        if (hasAllPermissions()) {
            startVoiceService();
        }
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
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    private void appendLog(String line) {
        runOnUiThread(() -> {
            logText.append(line + "\n\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void updateListeningUi(boolean listening) {
        runOnUiThread(() -> statusText.setText(
                listening ? "🎙️ Listening for Selena…" : "⏸️ Paused — toggle to resume"));
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
