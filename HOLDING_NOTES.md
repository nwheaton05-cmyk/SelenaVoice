# Holding-note library (Parity Item 3, Nick 2026-10-29 14:16 EDT)

When the assistant must research/work before answering, it first
speaks a quick holding note — instant, brief, in Selena's voice,
promising investigation only. The audio is PRE-RENDERED (a note
that waits on a fresh render defeats its purpose).

- 12 clips, Selena's voice (Aria, avocado_v2:MAI_01, speed 92),
  rendered 2026-10-29, stored in the OVH Supabase `selena-voice`
  bucket under `library/holding-note-NN.mp3`:
  https://api.siteviewoperations.com/storage/v1/object/public/selena-voice/library/holding-note-01.mp3 … -12.mp3
- Phrases: "Hold on — let me look into that." / "One moment — I'm
  checking on that." / "Let me look into that for you." / "Hold on
  while I check that." / "Give me a second — I'm looking into it." /
  "Let me check that distinction." / "I'm on it — let me verify
  that." / "Hold on — let me pull that up." / "One sec — checking
  the details on that." / "Let me research that and get back to
  you." / "Working on that — give me a moment." / "Let me dig into
  that for you."
- Use: `~/workspace/bin/selena-holding-note "<in-progress line>"`
  inserts one `selena_voice_queue` row {text: the in-progress line
  (topic goes here), audio_url: next clip in rotation,
  priority: "normal"}. Both surfaces hear it through the normal
  queue path (the standalone app speaks queue rows; the Siteview
  window mirrors the text). The real answer follows via the normal
  reply path when the research lands.
- Filenames deliberately avoid the `voice-msg-*` pattern so the
  voice flusher's cleanup never ages the library out.
