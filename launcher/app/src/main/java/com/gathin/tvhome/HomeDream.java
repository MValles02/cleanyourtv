package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Screensaver that is Home without the dock and the buttons: the wallpaper Home shows (images only; a
 * video or no wallpaper leaves the plain background), the greeting line, the clock and the date.
 */
public class HomeDream extends DreamService {
    /** What Home's greeting line shows right now (set by MainActivity), so a message carries over. */
    static String greeting = "";
    private WallpaperPlayer wallpaper;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.List<RaceWeekend.Race> calendar = java.util.Collections.emptyList();
    private boolean always;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setContentView(R.layout.dream_home);
        Config cfg = Config.load(this);
        FrameLayout root = findViewById(R.id.dreamRoot);
        root.setBackgroundColor(cfg.backgroundColor);
        wallpaper = new WallpaperPlayer(this, root, cfg, true);
        ((TextView) findViewById(R.id.greeting)).setText(greeting);
        if (cfg.shamsiDate) {
            TextView shamsi = findViewById(R.id.shamsiDate);
            shamsi.setText(Shamsi.today());
            shamsi.setVisibility(View.VISIBLE);
        }
        calendar = cfg.raceCalendar;
        always = cfg.raceAlways;
        tick.run();
    }

    /** Rebinds the race panel, then again at the next minute boundary (the clock's tick). */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!calendar.isEmpty()) {
                RacePanel.bind(HomeDream.this, RaceWeekend.at(calendar, java.time.Instant.now(), always),
                        findViewById(R.id.racePanel), findViewById(R.id.raceKicker),
                        findViewById(R.id.raceHeadline), findViewById(R.id.raceSessions));
            }
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        if (wallpaper != null) wallpaper.close();
        super.onDetachedFromWindow();
    }
}
