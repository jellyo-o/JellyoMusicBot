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
    private static final Pattern SECONDARY_DELIMITER = Pattern.compile("\\s+[|\\u2022\\u00B7]\\s+");
    private static final Pattern TRAILING_COVER_ATTRIBUTION = Pattern.compile("(?i)\\s+\\(?\\s*(cover(?:ed)?(?:\\s+by)?|originally\\s+by|version\\s+by)\\b.*$");
    private static final Pattern BRACKETED_COVER_ARTIST = Pattern.compile("[\\[(]([^\\])]{2,80}?)\\s+(cover|covered)[^\\])]*[\\])]", Pattern.CASE_INSENSITIVE);
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
        q = trimAtSecondaryDelimiter(q);
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
        for(String candidate : attributionCandidates(query))
            addLookupTerm(terms, candidate);

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
        for(String candidate : attributionCandidates(query))
            addProviderQuery(queries, candidate);

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

    private static Set<String> attributionCandidates(String raw)
    {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addAttributionCandidates(candidates, sanitizeQuery(raw));
        addAttributionCandidates(candidates, cleanSongQuery(raw));
        addBracketedCoverCandidates(candidates, sanitizeQuery(raw));
        return candidates;
    }

    private static void addAttributionCandidates(Set<String> candidates, String raw)
    {
        if(raw == null || raw.isBlank())
            return;

        String base = trimAtSecondaryDelimiter(raw);
        addProviderQuery(candidates, base);

        String[] parts = splitDashPair(base);
        if(parts == null)
            return;

        String left = cleanAttributionSide(parts[0]);
        String right = cleanAttributionSide(parts[1]);
        if(left.isEmpty() || right.isEmpty())
            return;

        addProviderQuery(candidates, left + " " + right);
        addProviderQuery(candidates, right + " " + left);
        addProviderQuery(candidates, right + " - " + left);
    }

    private static void addBracketedCoverCandidates(Set<String> candidates, String raw)
    {
        if(raw == null || raw.isBlank())
            return;

        java.util.regex.Matcher matcher = BRACKETED_COVER_ARTIST.matcher(raw);
        while(matcher.find())
        {
            String coverArtist = cleanAttributionSide(matcher.group(1));
            if(coverArtist.isEmpty())
                continue;

            String withoutBracket = BRACKETED_COVER_ARTIST.matcher(raw).replaceAll(" ").replaceAll(" +", " ").trim();
            String[] parts = splitDashPair(withoutBracket);
            String title = parts == null ? cleanAttributionSide(withoutBracket) : cleanAttributionSide(parts[1]);
            if(!title.isEmpty())
            {
                addProviderQuery(candidates, coverArtist + " " + title);
                addProviderQuery(candidates, coverArtist + " - " + title);
            }
        }
    }

    private static String trimAtSecondaryDelimiter(String raw)
    {
        if(raw == null)
            return null;
        String[] parts = SECONDARY_DELIMITER.split(raw, 2);
        return parts[0].trim();
    }

    private static String[] splitDashPair(String raw)
    {
        if(raw == null || !raw.contains(" - "))
            return null;
        String[] parts = raw.split("\\s+-\\s+", 2);
        if(parts.length != 2)
            return null;
        String left = parts[0].trim();
        String right = parts[1].trim();
        if(left.isEmpty() || right.isEmpty())
            return null;
        return new String[]{left, right};
    }

    private static String cleanAttributionSide(String raw)
    {
        if(raw == null)
            return "";
        String side = TRAILING_COVER_ATTRIBUTION.matcher(raw).replaceAll(" ");
        side = side.replaceAll("[\"'`]", " ");
        side = side.replaceAll(" +", " ").trim();
        return side;
    }
}
