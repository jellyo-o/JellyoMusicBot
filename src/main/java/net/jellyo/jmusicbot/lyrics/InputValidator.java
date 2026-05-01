package com.jagrosh.jmusicbot.lyrics;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class InputValidator
{
    private static final int MAX_QUERY_CHARS = 300;
    private static final int MAX_LYRICS_CHARS = 50_000;
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\n\r\t]]");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern BRACKETED_TEXT = Pattern.compile("\\s*[\\[(][^\\])]*(official|video|audio|lyrics?|lyric video|visualizer|remaster|remastered|4k|hd|mv)[^\\])]*[\\])]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENIUS_URL = Pattern.compile("^(https?://)?(www\\.)?genius\\.com/[-a-zA-Z0-9_/.]*?-lyrics/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENIUS_PATH = Pattern.compile("^/[-a-zA-Z0-9_/.]*?-lyrics/?$", Pattern.CASE_INSENSITIVE);
    private static final String[] NOISE_PHRASES = {
            "official music video",
            "official video",
            "official lyric video",
            "official lyrics video",
            "lyric video",
            "lyrics video",
            "music video",
            "official audio",
            "audio only",
            "visualizer",
            "remastered",
            "remaster",
            "4k upgrade",
            "4k",
            "hd",
            "mv"
    };

    private InputValidator()
    {
    }

    public static String sanitizeQuery(String raw)
    {
        if(raw == null)
            return null;
        String q = raw.trim();
        q = CONTROL_CHARS.matcher(q).replaceAll(" ");
        q = q.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        q = q.replace("\r", " ").replace("\n", " ").replace("\t", " ").replaceAll(" +", " ").trim();
        if(q.isEmpty())
            return null;
        if(q.length() > MAX_QUERY_CHARS)
            q = q.substring(0, MAX_QUERY_CHARS).trim();
        return q.isEmpty() ? null : q;
    }

    public static boolean isValidGeniusUrl(String url)
    {
        return url != null && GENIUS_URL.matcher(url.trim()).matches();
    }

    public static boolean isValidGeniusPath(String path)
    {
        return path != null && GENIUS_PATH.matcher(path.trim()).matches();
    }

    public static String sanitizeLyrics(String lyrics)
    {
        if(lyrics == null)
            return null;
        String l = lyrics.replace("\r", "");
        l = CONTROL_CHARS.matcher(l).replaceAll(" ");
        l = l.replaceAll("\n{5,}", "\n\n\n");
        if(l.length() > MAX_LYRICS_CHARS)
            l = l.substring(0, MAX_LYRICS_CHARS);
        return l.trim();
    }

    public static String normalizeLookup(String raw)
    {
        if(raw == null)
            return "";
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFD);
        normalized = DIACRITICS.matcher(normalized).replaceAll("");
        normalized = normalized.toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replace("’", "'")
                .replace("‘", "'")
                .replace("“", "\"")
                .replace("”", "\"");
        normalized = normalized.replaceAll("[^a-z0-9]+", " ").replaceAll(" +", " ").trim();
        return normalized;
    }

    public static String cleanSongQuery(String raw)
    {
        String q = sanitizeQuery(raw);
        if(q == null)
            return null;
        q = BRACKETED_TEXT.matcher(q).replaceAll(" ");
        for(String phrase : NOISE_PHRASES)
            q = q.replaceAll("(?i)\\b" + Pattern.quote(phrase) + "\\b", " ");
        q = q.replaceAll("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*$", " ");
        q = q.replaceAll("[\"'`]", " ");
        q = q.replaceAll(" +", " ").trim();
        return q.isEmpty() ? null : q;
    }

    public static String[] splitArtistTitle(String raw)
    {
        String q = cleanSongQuery(raw);
        if(q == null || !q.contains(" - "))
            return null;
        String[] parts = q.split("\\s+-\\s+", 2);
        if(parts.length != 2)
            return null;
        String artist = parts[0].trim();
        String title = parts[1].trim();
        if(artist.isEmpty() || title.isEmpty())
            return null;
        return new String[]{artist, title};
    }

    public static Set<String> lookupTerms(String query)
    {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addLookupTerm(terms, query);
        String cleaned = cleanSongQuery(query);
        addLookupTerm(terms, cleaned);

        String[] artistTitle = splitArtistTitle(query);
        if(artistTitle != null)
        {
            addLookupTerm(terms, artistTitle[0] + " " + artistTitle[1]);
            addLookupTerm(terms, artistTitle[0] + " - " + artistTitle[1]);
            addLookupTerm(terms, artistTitle[1]);
        }

        return terms;
    }

    public static Set<String> providerQueries(String query)
    {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        addProviderQuery(queries, sanitizeQuery(query));
        addProviderQuery(queries, cleanSongQuery(query));

        String[] artistTitle = splitArtistTitle(query);
        if(artistTitle != null)
        {
            addProviderQuery(queries, artistTitle[0] + " " + artistTitle[1]);
            addProviderQuery(queries, artistTitle[1]);
        }

        return queries;
    }

    static void addLookupTerm(Set<String> terms, String raw)
    {
        String normalized = normalizeLookup(raw);
        if(!normalized.isEmpty())
            terms.add(normalized);
    }

    private static void addProviderQuery(Set<String> queries, String raw)
    {
        if(raw != null && !raw.isBlank())
            queries.add(raw.trim());
    }
}
