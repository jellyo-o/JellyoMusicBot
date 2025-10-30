package com.jagrosh.jmusicbot.lyrics;

import java.util.regex.Pattern;

public final class InputValidator {
    private InputValidator() {}
    private static final int MAX_QUERY_CHARS = 300;
    private static final int MAX_LYRICS_CHARS = 50_000;
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\n\r\t]]");
    private static final Pattern GENIUS_URL = Pattern.compile("^(https?://)?(www\\.)?genius\\.com/[-a-zA-Z0-9_/.]*?-lyrics/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENIUS_PATH = Pattern.compile("^/[-a-zA-Z0-9_/.]*?-lyrics/?$", Pattern.CASE_INSENSITIVE);

    public static String sanitizeQuery(String raw) {
        if (raw == null) return null; String q = raw.trim();
        q = CONTROL_CHARS.matcher(q).replaceAll(" ");
        q = q.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        q = q.replaceAll("\r", " ").replaceAll("\n", " ").replaceAll("\t", " ").replaceAll(" +", " ").trim();
        if (q.isEmpty()) return null; if (q.length() > MAX_QUERY_CHARS) q = q.substring(0, MAX_QUERY_CHARS); if (q.contains(": ")) return null; return q;
    }
    public static boolean isValidGeniusUrl(String url) { return url!=null && GENIUS_URL.matcher(url.trim()).matches(); }
    public static boolean isValidGeniusPath(String path) { return path!=null && GENIUS_PATH.matcher(path.trim()).matches(); }
    public static String sanitizeLyrics(String lyrics) {
        if (lyrics == null) return null; String l = lyrics.replace("\r", ""); l = CONTROL_CHARS.matcher(l).replaceAll(" "); l = l.replaceAll("\n{5,}", "\n\n\n"); if (l.length() > MAX_LYRICS_CHARS) l = l.substring(0, MAX_LYRICS_CHARS); return l.trim(); }
}
