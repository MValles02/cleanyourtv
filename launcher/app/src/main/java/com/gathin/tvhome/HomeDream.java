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
    }

    @Override
    public void onDetachedFromWindow() {
        if (wallpaper != null) wallpaper.close();
        super.onDetachedFromWindow();
    }
}
