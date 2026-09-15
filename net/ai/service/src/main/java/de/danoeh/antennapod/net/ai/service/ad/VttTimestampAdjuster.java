package de.danoeh.antennapod.net.ai.service.ad;

import java.util.Locale;

/**
 * Applies episode offsets to WebVTT timestamps emitted for individual audio chunks.
 */
public final class VttTimestampAdjuster {
    private VttTimestampAdjuster() {
    }

    public static String applyOffset(String vtt, double offsetSeconds) {
        if (vtt == null || vtt.isEmpty()) {
            return vtt;
        }
        String[] lines = vtt.split("\n");
        StringBuilder adjusted = new StringBuilder();
        for (String line : lines) {
            if (line.trim().equalsIgnoreCase("WEBVTT")) {
                continue;
            }
            if (line.contains("-->")) {
                String[] parts = line.split("-->");
                if (parts.length == 2) {
                    String start = parts[0].trim();
                    String end = parts[1].trim();
                    adjusted.append(formatTime(parseSeconds(start) + offsetSeconds))
                            .append(" --> ")
                            .append(formatTime(parseSeconds(end) + offsetSeconds))
                            .append('\n');
                    continue;
                }
            }
            adjusted.append(line).append('\n');
        }
        return adjusted.toString();
    }

    public static double parseSeconds(String timeString) {
        if (timeString == null) {
            return 0;
        }
        String[] parts = timeString.split(":");
        if (parts.length != 3) {
            return 0;
        }
        try {
            double hours = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = Double.parseDouble(parts[2].replace(',', '.'));
            return hours * 3600 + minutes * 60 + seconds;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String formatTime(double seconds) {
        int hours = (int) (seconds / 3600);
        seconds -= hours * 3600;
        int minutes = (int) (seconds / 60);
        seconds -= minutes * 60;
        return String.format(Locale.US, "%02d:%02d:%06.3f", hours, minutes, seconds);
    }
}
