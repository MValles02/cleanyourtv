package com.gathin.tvhome;

import android.content.Context;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/** Text of the race-weekend panel, shared by Home and the screensaver. Text only: nothing animates. */
final class RacePanel {
    private static final int RACE_RED = 0xFFFF3B2F;

    private RacePanel() {}

    /** Fills the three lines for state s and shows the panel; s == null hides it. */
    static void bind(Context c, RaceWeekend.State s, View panel, TextView kicker, TextView headline, TextView sessions) {
        if (s == null) {
            panel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        kicker.setText(race.place.isEmpty()
                ? c.getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : c.getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("●  " + c.getString(R.string.race_live, s.live.title()));
            live.setSpan(new ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            headline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(s.untilNext);
            headline.setText(s.next.isRace() ? c.getString(R.string.race_lights_out, in)
                    : c.getString(R.string.race_next, s.next.title(), in));
        } else {
            headline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(c) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        SpannableStringBuilder row = new SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName()).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sessions.setText(row);
        panel.setVisibility(View.VISIBLE);
    }
}
