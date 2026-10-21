package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WallpaperRotation {
    static List<File> images(File directory) {
        List<File> result = new ArrayList<>();
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            long cap = name.endsWith(".mp4") ? 1024L * 1024 * 1024 : 10 * 1024 * 1024;
            if (entry.isFile() && entry.length() > 0 && entry.length() <= cap
                    && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".mp4"))) {
                result.add(entry);
                if (result.size() == 100) break;
            }
        }
        return result;
    }

    static File find(List<File> images, String name) {
        for (File image : images) if (image.getName().equals(name)) return image;
        return null;
    }

    static File pick(List<File> images, String previous, Random random) {
        List<File> candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    private static final Pattern VARIANT = Pattern.compile("^(.+)\\.(day|night)\\.[a-z0-9]+$");

    /** The entry a file belongs to: "dusk" for dusk.day.jpg and dusk.night.mp4, else the file name itself. */
    static String key(String fileName) {
        Matcher m = VARIANT.matcher(fileName.toLowerCase(Locale.ROOT));
        return m.matches() ? fileName.substring(0, m.end(1)) : fileName;
    }

    static boolean nightVariant(String fileName) {
        Matcher m = VARIANT.matcher(fileName.toLowerCase(Locale.ROOT));
        return m.matches() && "night".equals(m.group(2));
    }

    static boolean video(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".mp4");
    }

    static String pickKey(List<String> keys, String previous, Random random) {
        List<String> candidates = new ArrayList<>(keys);
        if (candidates.size() > 1) candidates.remove(previous);
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    static boolean due(long previous, long now, long interval) {
        return interval > 0 && (previous == 0 || now < previous || now - previous >= interval);
    }
}
