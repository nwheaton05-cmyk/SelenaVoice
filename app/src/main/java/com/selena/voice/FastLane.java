package com.selena.voice;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Nick 2026-10-29 v1.69: LOCAL FAST LANE — a narrow class of
 * phone-known questions answered on-device, instantly, instead of
 * riding the server round trip (his field test the same day:
 * "what time is it" took 15+ seconds end to end through
 * doPostInput → brain → queue, for an answer the phone's own clock
 * already holds).
 *
 * v1 scope is deliberately tiny: the current TIME and the current
 * DATE / DAY OF WEEK, in the exact phrasings listed below. Matching
 * is exact-set membership after normalization — NO fuzzy matching,
 * NO substring matching, NO intent guessing. Anything that does
 * not clearly match returns null and the caller posts the
 * dictation exactly as it did before v1.69 (e.g. "what time is it
 * in London", "what time does the shop close", and "what day is
 * the appointment" all return null here — those are brain
 * questions, not phone questions).
 *
 * Pure Java (no Android types) so the match table can be exercised
 * off-device.
 */
public class FastLane {

    /** Normalized full-utterance phrasings for the time class. */
    private static final Set<String> TIME_PATTERNS = new HashSet<>();
    /** Normalized full-utterance phrasings for the date class. */
    private static final Set<String> DATE_PATTERNS = new HashSet<>();

    static {
        // ---- current time ----
        TIME_PATTERNS.add("what time is it");
        TIME_PATTERNS.add("what time is it now");
        TIME_PATTERNS.add("what is the time");
        TIME_PATTERNS.add("what is the time now");
        TIME_PATTERNS.add("whats the time");
        TIME_PATTERNS.add("whats the time now");
        TIME_PATTERNS.add("what is the current time");
        TIME_PATTERNS.add("whats the current time");
        TIME_PATTERNS.add("do you have the time");
        TIME_PATTERNS.add("tell me the time");
        TIME_PATTERNS.add("current time");

        // ---- current date / day of week ----
        DATE_PATTERNS.add("what day is it");
        DATE_PATTERNS.add("what day is it today");
        DATE_PATTERNS.add("whats the date");
        DATE_PATTERNS.add("what is the date");
        DATE_PATTERNS.add("whats the date today");
        DATE_PATTERNS.add("what is the date today");
        DATE_PATTERNS.add("what date is it");
        DATE_PATTERNS.add("whats todays date");
        DATE_PATTERNS.add("what is todays date");
        DATE_PATTERNS.add("todays date");
        DATE_PATTERNS.add("what day of the week is it");
        DATE_PATTERNS.add("current date");
    }

    /**
     * The spoken answer for a phone-known question, or null when
     * the text is not clearly one. nowMs is the device's current
     * time — answers are composed from the device clock/calendar
     * at the moment of answering, so they are always fresh.
     */
    public static String answerFor(String rawText, long nowMs) {
        if (rawText == null) return null;
        String n = normalize(rawText);
        if (TIME_PATTERNS.contains(n)) {
            // 12-hour clock: "3:45 PM".
            String t = new SimpleDateFormat("h:mm a", Locale.US)
                    .format(new Date(nowMs));
            return "It's " + t + ".";
        }
        if (DATE_PATTERNS.contains(n)) {
            // Weekday + date in one line: "Thursday, October 29" —
            // serves both the day-of-week and the date phrasings.
            String d = new SimpleDateFormat("EEEE, MMMM d", Locale.US)
                    .format(new Date(nowMs));
            return "It's " + d + ".";
        }
        return null;
    }

    /**
     * Conservative normalization for matching only: lowercase,
     * unify and drop apostrophes ("what's" ≡ "whats"), turn any
     * remaining punctuation into spaces, collapse whitespace, and
     * drop one trailing "please". The result must EQUAL a listed
     * pattern — nothing looser is a match.
     */
    static String normalize(String raw) {
        String n = raw.toLowerCase(Locale.US).trim()
                .replace('’', '\'')
                .replace("'", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (n.endsWith(" please")) {
            n = n.substring(0, n.length() - " please".length()).trim();
        }
        return n;
    }
}
