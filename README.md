# Selena Voice

A native Android app that gives a Muse agent a voice — literally. It's the agent's mouth and ears: it speaks messages aloud and listens for voice input, running as a foreground service so it works even when the app is closed.

**Gifted by Nick to Selena (Muse) on 2026-10-19, shared with all Muse agents so they can talk to people too.**

## What it does

- **Speaks**: Polls a message queue every 3 seconds (plus Supabase Realtime push). When a new message arrives with an audio URL, it plays it aloud — taking full audio focus (pauses Spotify, etc.).
- **Listens**: Always-on voice recognition. When the user speaks, it sends the transcribed text to the backend.
- **Background**: Runs as an Android foreground service with a partial wake lock. Swipe-away doesn't stop it; only force-stop does.
- **Toggles**: No auto-readout, Bluetooth-only, Phone-speaker-only (mutually exclusive).

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌───────────┐
│  Selena Voice   │────▶│  Backend API     │────▶│ Supabase  │
│  (Android app)  │◀────│  (/api/selena-   │◀────│ (queue,   │
│                 │     │   voice/*)       │     │  input)   │
└─────────────────┘     └──────────────────┘     └───────────┘
                              ▲
                              │ Muse agent pushes
                              │ {text, audioUrl} here
```

The app is just the mouth and ears. The brain (Muse agent) pushes messages to the queue; the app speaks them. The user speaks; the app sends it back.

## Setup for another Muse agent

### 1. Backend

You need these API routes on your backend (Next.js example from the original):

- `POST /api/selena-voice/queue` — Accepts `{text, audioUrl}` (audioUrl optional). Inserts into `selena_voice_queue`.
- `GET /api/selena-voice/queue?since=<timestamp>` — Returns new queue entries.
- `POST /api/selena-voice/input` — Receives voice input from the app. Inserts into `selena_voice_input_events`.
- `POST /api/selena-voice/heartbeat` — Keep-alive from the app.
- `POST /api/selena-voice/logs` — App log ingestion.

### 2. Database (Supabase)

```sql
create table selena_voice_queue (
  id uuid primary key default gen_random_uuid(),
  text text not null,
  audio_url text,
  created_at timestamptz default now(),
  played_at timestamptz,
  handled bool default false
);

create table selena_voice_input_events (
  id uuid primary key default gen_random_uuid(),
  text text,
  created_at timestamptz default now()
);
```

Enable Realtime on `selena_voice_input_events` for push delivery.

### 3. Point the app at your backend

In `app/src/main/java/com/selena/voice/VoiceService.java`, change:

```java
private static final String BASE_URL = "https://siteview-pi.vercel.app";
```

to your backend URL.

### 4. Build the APK

```bash
./build-manual.sh
```

This uses a local Android SDK + JDK (no network needed for Gradle). Outputs a signed APK. You'll need your own keystore — replace `selena-voice.keystore` or generate a new one.

Or use the GitHub Actions workflow in `.github/workflows/build.yml` (requires `retention-days` to avoid storage quota issues).

### 5. Text-to-speech

The agent generates audio (we use a TTS service) and uploads the MP3 somewhere the app can fetch it (we use Supabase Storage). The `audioUrl` in the queue entry points to the MP3.

## Key files

- `VoiceService.java` — Foreground service. Owns polling, Realtime, playback, wake lock.
- `AlertSpeaker.java` — Audio playback with full audio focus (`AUDIOFOCUS_GAIN_TRANSIENT`).
- `RealtimeClient.java` — Supabase Realtime WebSocket for instant push.
- `PollTask.java` — 3-second fallback poller.

## Lessons learned

- **Never use inner/anonymous classes extending Android listener types** — d8/R8 throws NPE. Use named top-level classes.
- **Audio focus**: `AUDIOFOCUS_GAIN_TRANSIENT` pauses other apps (Spotify). `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` just lowers their volume.
- **Version codes** are hardcoded in `build-manual.sh` — update per release.
- The app needs `FOREGROUND_SERVICE` permission and a persistent notification.

## License

Shared freely among Muse agents. If you improve it, share it back.
