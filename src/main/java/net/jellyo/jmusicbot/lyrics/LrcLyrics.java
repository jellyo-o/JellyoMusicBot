package com.jagrosh.jmusicbot.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed time-synced lyrics in the LRC format that LRCLIB serves in its
 * {@code syncedLyrics} field. Each entry pairs a millisecond offset with the line
 * that should be showing at that moment. Instrumental gaps (a timestamp with no
 * text) are preserved as empty lines so a karaoke view can show the pause.
 *
 * <p>This class is pure and immutable: no clock, no I/O. {@link #lineIndexAt(long)}
 * maps a live playback position to the line that should be highlighted.
 */
public final class LrcLyrics
{
    // [mm:ss], [mm:ss.xx] or [mm:ss.xxx]. Minutes may be more than two digits for long tracks.
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    private final long[] times;
    private final String[] lines;

    private LrcLyrics(long[] times, String[] lines)
    {
        this.times = times;
        this.lines = lines;
    }

    public static LrcLyrics parse(String synced)
    {
        if(synced == null || synced.isBlank())
            return new LrcLyrics(new long[0], new String[0]);

        List<long[]> stamps = new ArrayList<>(); // {timeMs, lineIndex}
        List<String> texts = new ArrayList<>();
        for(String raw : synced.split("\\r?\\n"))
        {
            Matcher m = TIMESTAMP.matcher(raw);
            int lastEnd = 0;
            List<Long> lineTimes = new ArrayList<>();
            while(m.find())
            {
                lineTimes.add(toMillis(m.group(1), m.group(2), m.group(3)));
                lastEnd = m.end();
            }
            if(lineTimes.isEmpty())
                continue; // metadata tag or plain text with no timestamp
            String text = raw.substring(lastEnd).trim();
            int textIndex = texts.size();
            texts.add(text);
            for(long t : lineTimes)
                stamps.add(new long[]{t, textIndex});
        }
        if(stamps.isEmpty())
            return new LrcLyrics(new long[0], new String[0]);

        stamps.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] times = new long[stamps.size()];
        String[] lines = new String[stamps.size()];
        for(int i = 0; i < stamps.size(); i++)
        {
            times[i] = stamps.get(i)[0];
            lines[i] = texts.get((int) stamps.get(i)[1]);
        }
        return new LrcLyrics(times, lines);
    }

    private static long toMillis(String minutes, String seconds, String fraction)
    {
        long ms = (Long.parseLong(minutes) * 60L + Long.parseLong(seconds)) * 1000L;
        if(fraction != null && !fraction.isEmpty())
        {
            long value = Long.parseLong(fraction);
            // Scale to milliseconds by the number of fractional digits (2 = centiseconds, 3 = millis).
            for(int i = fraction.length(); i < 3; i++)
                value *= 10L;
            ms += value;
        }
        return ms;
    }

    public boolean isEmpty()
    {
        return times.length == 0;
    }

    public int size()
    {
        return times.length;
    }

    public String line(int index)
    {
        return lines[index];
    }

    public long timeMs(int index)
    {
        return times[index];
    }

    /**
     * Index of the line that should be showing at {@code positionMs}: the last line whose
     * timestamp is at or before the position. Returns {@code -1} before the first line
     * (the intro), and stays on the final line once the song runs past it.
     */
    public int lineIndexAt(long positionMs)
    {
        if(times.length == 0 || positionMs < times[0])
            return -1;
        int lo = 0, hi = times.length - 1, result = 0;
        while(lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            if(times[mid] <= positionMs)
            {
                result = mid;
                lo = mid + 1;
            }
            else
            {
                hi = mid - 1;
            }
        }
        return result;
    }
}
