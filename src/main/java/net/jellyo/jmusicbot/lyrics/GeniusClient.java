package com.jagrosh.jmusicbot.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public class GeniusClient
{
    private static final String BASE = "https://genius.com";
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final boolean DEBUG = Boolean.getBoolean("lyrics.debug") || "true".equalsIgnoreCase(System.getenv("LYRICS_DEBUG"));
    private static final RateLimiter RATE_LIMITER = new RateLimiter(Long.getLong("lyrics.rateMillis", 10_000L));
    private static final String[] STOP_WORDS = {
            "official", "video", "music", "clip", "audio", "lyrics", "lyric", "hd", "4k",
            "remastered", "prod", "prod.", "ft", "feat", "live", "mv", "teaser", "trailer", "visualizer"
    };

    private static boolean isAllowDifferentArtist()
    {
        return Boolean.getBoolean("lyrics.allowDifferentArtist");
    }

    public Optional<String> findSongPath(String query) throws IOException
    {
        return findSong(query, isAllowDifferentArtist()).map(GeniusSong::path);
    }

    public Optional<String> findSongPath(String query, boolean allowDifferentArtist) throws IOException
    {
        return findSong(query, allowDifferentArtist).map(GeniusSong::path);
    }

    public Optional<GeniusSong> findSong(String query, boolean allowDifferentArtist) throws IOException
    {
        String sanitized = InputValidator.sanitizeQuery(query);
        if(sanitized == null)
            return Optional.empty();

        String expectedArtist = extractLeadingPossessiveArtist(sanitized);
        java.util.List<String> prioritized = new java.util.ArrayList<>();
        java.util.List<String> others = new java.util.ArrayList<>();
        for(String v : generateQueryVariants(sanitized))
        {
            if(expectedArtist != null && v.toLowerCase().contains(expectedArtist.toLowerCase()))
                prioritized.add(v);
            else
                others.add(v);
        }

        if(DEBUG)
        {
            System.out.println("[DEBUG] Expected artist: " + expectedArtist);
            System.out.println("[DEBUG] RateLimiter acquire (search)");
        }
        RATE_LIMITER.acquire(false);

        for(String candidate : prioritized)
        {
            Optional<GeniusSong> song = searchBestSong(candidate, candidate, expectedArtist, allowDifferentArtist);
            if(song.isPresent())
                return song;
        }
        for(String candidate : others)
        {
            Optional<GeniusSong> song = searchBestSong(candidate, candidate, expectedArtist, allowDifferentArtist);
            if(song.isPresent())
                return song;
        }
        return Optional.empty();
    }

    public Optional<String> fetchLyrics(String songPath) throws IOException
    {
        return fetchSongPage(songPath).map(GeniusSong::lyrics);
    }

    public Optional<GeniusSong> fetchSongPage(String songPath) throws IOException
    {
        if(!InputValidator.isValidGeniusPath(songPath))
            return Optional.empty();
        String url = BASE + songPath;
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build();
        if(DEBUG)
            System.out.println("[DEBUG] RateLimiter acquire (lyrics fetch)");
        RATE_LIMITER.acquire(false);
        try(Response response = CLIENT.newCall(request).execute())
        {
            if(!response.isSuccessful() || response.body() == null)
                return Optional.empty();
            String html = response.body().string();
            Document doc = Jsoup.parse(html);
            String lyrics = extractLyrics(doc);
            if(lyrics.isBlank())
                return Optional.empty();
            String[] titleParts = extractTitleParts(doc, songPath);
            return Optional.of(new GeniusSong(
                    titleParts[0],
                    titleParts[1],
                    songPath,
                    url,
                    lyrics
            ));
        }
    }

    private Optional<GeniusSong> searchBestSong(String candidate, String scoringBasis, String expectedArtist,
                                                boolean allowDifferentArtist) throws IOException
    {
        HttpUrl url = HttpUrl.parse(BASE + "/api/search/multi").newBuilder()
                .addQueryParameter("q", candidate)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build();
        try(Response response = CLIENT.newCall(request).execute())
        {
            if(!response.isSuccessful() || response.body() == null)
                return Optional.empty();

            JsonNode root = MAPPER.readTree(response.body().byteStream());
            JsonNode sections = root.path("response").path("sections");
            if(!sections.isArray())
                return Optional.empty();

            double bestScore = -1d;
            GeniusSong bestSong = null;
            double bestArtistScore = -1d;
            GeniusSong bestArtistSong = null;
            String cleanedOriginal = normalizeForScore(scoringBasis);
            String expectedArtistLc = expectedArtist == null ? null : expectedArtist.toLowerCase();

            for(JsonNode section : sections)
            {
                if(!"song".equals(section.path("type").asText()))
                    continue;
                JsonNode hits = section.path("hits");
                if(!hits.isArray())
                    continue;
                for(JsonNode hit : hits)
                {
                    JsonNode result = hit.path("result");
                    String path = result.path("path").asText(null);
                    if(path == null || !path.endsWith("-lyrics"))
                        continue;

                    String title = result.path("title").asText("");
                    String artist = result.path("primary_artist").path("name").asText("");
                    String sourceUrl = result.path("url").asText(BASE + path);
                    String composite = (artist + " " + title).trim();
                    double score = scoreCandidate(cleanedOriginal, composite, result.path("full_title").asText(""), expectedArtist);
                    String artistLc = artist.toLowerCase();
                    boolean artistMatches = artistMatches(expectedArtistLc, artistLc);
                    if(expectedArtistLc != null && !allowDifferentArtist && !artistMatches)
                        continue;
                    if(expectedArtistLc != null)
                        score += artistMatches ? 0.30d : -0.35d;

                    GeniusSong song = new GeniusSong(artist, title, path, sourceUrl, null);
                    if(score > bestScore)
                    {
                        bestScore = score;
                        bestSong = song;
                    }
                    if(expectedArtistLc != null && artistMatches && score > bestArtistScore)
                    {
                        bestArtistScore = score;
                        bestArtistSong = song;
                    }
                }
            }

            if(bestArtistSong != null && bestArtistScore >= 0.08d)
                return Optional.of(bestArtistSong);
            if(expectedArtistLc != null && !allowDifferentArtist)
                return Optional.empty();
            if(bestSong != null && bestScore >= 0.10d)
                return Optional.of(bestSong);
            return Optional.empty();
        }
    }

    private boolean artistMatches(String expectedArtistLc, String artistLc)
    {
        if(expectedArtistLc == null)
            return false;
        if(artistLc.equals(expectedArtistLc) || artistLc.contains(expectedArtistLc))
            return true;

        String[] expTokens = expectedArtistLc.split("[ \t]+");
        int tokenMatches = 0;
        int total = 0;
        for(String token : expTokens)
        {
            if(token.isBlank())
                continue;
            total++;
            if(artistLc.contains(token))
                tokenMatches++;
        }
        return total > 0 && tokenMatches >= Math.max(1, (int) Math.ceil(total * 0.5d));
    }

    private Iterable<String> generateQueryVariants(String raw)
    {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        String normalized = raw.replace("\\\"", "\"");
        String cleanedQuotes = normalized.replace("“", "\"").replace("”", "\"").replace("‘", "'").replace("’", "'");
        variants.add(cleanedQuotes.trim());

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]{2,})\"").matcher(cleanedQuotes);
        if(m.find())
        {
            String quoted = m.group(1).trim();
            if(!quoted.isEmpty())
            {
                variants.add(quoted);
                variants.add(quoted.replaceAll("(?i)\\b(song|official|video|lyrics?)$", "").trim());
                String possibleArtist = extractLeadingPossessiveArtist(raw);
                if(possibleArtist != null)
                {
                    variants.add(possibleArtist + " " + quoted);
                    variants.add((possibleArtist + " " + quoted).toLowerCase());
                }
            }
        }

        String noParens = cleanedQuotes.replaceAll("[“”]", "\"")
                .replaceAll("\\(([^)]*)\\)|\\[([^]]*)\\]", " ")
                .replaceAll(" +", " ")
                .trim();
        variants.add(noParens);
        if(cleanedQuotes.toLowerCase().contains("what it sounds like"))
            variants.add("what it sounds like");
        if(cleanedQuotes.contains(" - "))
        {
            String[] parts = cleanedQuotes.split(" - ");
            if(parts.length >= 2)
            {
                String right = parts[parts.length - 1];
                if(right.toLowerCase().matches(".*(official|clip|video|lyrics).*"))
                {
                    StringBuilder mid = new StringBuilder();
                    for(int i = 1; i < parts.length - 1; i++)
                        mid.append(parts[i]).append(' ');
                    variants.add(mid.toString().trim());
                }
            }
        }
        if(cleanedQuotes.contains("\""))
        {
            int firstQuote = cleanedQuotes.indexOf('"');
            int secondQuote = cleanedQuotes.indexOf('"', firstQuote + 1);
            if(firstQuote >= 0 && secondQuote > firstQuote)
                variants.add(cleanedQuotes.substring(firstQuote + 1, secondQuote));
        }

        String noStops = removeStopWords(noParens);
        variants.add(noStops);
        variants.add(noStops.replaceAll("(?i)\\b(song|official|video|lyrics?)$", " ").trim());
        java.util.List<String> lowercaseVariants = new java.util.ArrayList<>();
        for(String v : variants)
            lowercaseVariants.add(v.toLowerCase());
        variants.addAll(lowercaseVariants);
        variants.addAll(InputValidator.providerQueries(raw));

        LinkedHashSet<String> finalVariants = new LinkedHashSet<>();
        for(String v : variants)
        {
            String simple = v.replaceAll("[\\p{Punct}]", " ").replaceAll(" +", " ").trim();
            if(!simple.isEmpty())
                finalVariants.add(simple);
        }
        return finalVariants;
    }

    private String extractLeadingPossessiveArtist(String raw)
    {
        String trimmed = raw.trim();
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]{1,50})['’]s\\b").matcher(trimmed);
        if(m1.find())
        {
            String artist = m1.group(1);
            if(artist.length() >= 2)
                return artist;
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("^([A-Za-z0-9][A-Za-z0-9 .&'’_-]{1,60}) - ").matcher(trimmed);
        if(m2.find())
        {
            String artist = m2.group(1).trim();
            if(artist.length() >= 2 && artist.split(" ").length <= 6)
                return artist;
        }
        return null;
    }

    private String removeStopWords(String input)
    {
        String lc = input.toLowerCase();
        for(String sw : STOP_WORDS)
            lc = lc.replaceAll("\\b" + java.util.regex.Pattern.quote(sw) + "\\b", " ");
        return lc.replaceAll(" +", " ").trim();
    }

    private String normalizeForScore(String s)
    {
        return removeStopWords(s).toLowerCase().replaceAll("[\\p{Punct}]", " ").replaceAll(" +", " ").trim();
    }

    private double scoreCandidate(String cleanedOriginal, String composite, String fullTitle, String expectedArtist)
    {
        String target = normalizeForScore(composite + " " + fullTitle);
        if(target.isEmpty() || cleanedOriginal.isEmpty())
            return 0d;
        Set<String> origTokens = new LinkedHashSet<>(java.util.Arrays.asList(cleanedOriginal.split(" ")));
        Set<String> tgtTokens = new LinkedHashSet<>(java.util.Arrays.asList(target.split(" ")));
        int intersection = 0;
        for(String token : origTokens)
            if(tgtTokens.contains(token))
                intersection++;
        double jaccard = intersection / (double) (origTokens.size() + tgtTokens.size() - intersection + 1e-9);
        if(target.contains(cleanedOriginal))
            jaccard += 0.15d;
        return jaccard;
    }

    private String extractLyrics(Document doc)
    {
        Elements containers = doc.select("div[data-lyrics-container=true]");
        if(containers.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder();
        for(Element c : containers)
        {
            Element working = c.clone();
            working.select("div[class^=LyricsHeader__]").remove();
            working.select("div[class^=SongBioPreview__]").remove();
            working.select("div[class^=Dropdown__]").remove();
            working.select("button").remove();
            working.select("ul,li").remove();

            String inner = working.html();
            inner = inner.replaceAll("(?i)<br\\s*/?>", "\n");
            inner = inner.replaceAll("<script[\\s\\S]*?</script>", "");
            inner = inner.replaceAll("<style[\\s\\S]*?</style>", "");
            inner = inner.replaceAll("<[^>]+>", "");
            inner = htmlEntityDecode(inner).replace("\r", "");
            inner = inner.replaceAll("\n{3,}", "\n\n");

            StringBuilder block = new StringBuilder();
            for(String line : inner.split("\n"))
            {
                String trimmed = line.stripTrailing();
                if(trimmed.isEmpty())
                    block.append('\n');
                else
                    block.append(trimmed).append('\n');
            }
            String textBlock = block.toString().replaceAll("(\n){3,}", "\n\n").trim();
            if(textBlock.isEmpty() || textBlock.equalsIgnoreCase("[Music Video]"))
                continue;
            textBlock = stripInlineMetadata(textBlock);
            if(!textBlock.isEmpty())
                sb.append(textBlock).append("\n\n");
        }

        String result = sb.toString().trim();
        if(result.isEmpty())
            return "";
        result = cleanLeadingMetadata(result);
        return formatSections(result);
    }

    private String[] extractTitleParts(Document doc, String path)
    {
        String title = "";
        String artist = "";
        Element meta = doc.selectFirst("meta[property=og:title]");
        if(meta != null)
            title = meta.attr("content");
        if(title.isBlank())
            title = doc.title();

        title = title.replaceAll("(?i)\\s*\\|\\s*Genius Lyrics\\s*$", "")
                .replaceAll("(?i)\\s+Lyrics\\s*$", "")
                .trim();
        String[] split = title.split("\\s+[–-]\\s+", 2);
        if(split.length == 2)
        {
            artist = split[0].trim();
            title = split[1].trim();
        }
        if(title.isBlank() || title.equalsIgnoreCase("Genius"))
        {
            String[] fallback = inferTitlePartsFromPath(path);
            artist = fallback[0];
            title = fallback[1];
        }
        return new String[]{artist, title};
    }

    private String[] inferTitlePartsFromPath(String path)
    {
        String slug = path.substring(path.lastIndexOf('/') + 1);
        String core = slug.substring(0, slug.length() - "-lyrics".length());
        String title = capitalizeWords(core.replace('-', ' ').replace('_', ' '));
        return new String[]{"", title};
    }

    private String htmlEntityDecode(String text)
    {
        return text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");
    }

    private String cleanLeadingMetadata(String text)
    {
        String[] lines = text.split("\n");
        int firstStruct = -1;
        for(int i = 0; i < lines.length; i++)
        {
            String l = lines[i].trim();
            if(l.matches("^\\[(?i)(intro|verse|verse [0-9]+|chorus|bridge|refrain|hook|interlude|outro|pre-chorus|post-chorus|part [0-9]+)\\].*$"))
            {
                firstStruct = i;
                break;
            }
        }
        if(firstStruct > 3 && firstStruct != -1)
        {
            boolean drop = true;
            for(int i = 0; i < firstStruct; i++)
            {
                String l = lines[i].trim();
                if(l.isEmpty())
                    continue;
                String lower = l.toLowerCase();
                boolean languageLike = lower.matches("^[a-zA-Zçéèíöőüûäåøñßœæğşıãáõçčžýðúíőű'`.-]{2,}(?: [a-zA-Zçéèíöőüûäåøñßœæğşıãáõçčžýðúíőű'`.-]{2,}){0,3}$") && l.length() <= 25;
                boolean meta = languageLike || lower.contains("contributors") || lower.startsWith("translations")
                        || lower.startsWith("read more") || lower.endsWith(" lyrics");
                if(!meta)
                {
                    drop = false;
                    break;
                }
            }
            if(drop)
            {
                StringBuilder sb = new StringBuilder();
                for(int i = firstStruct; i < lines.length; i++)
                    sb.append(lines[i].trim()).append('\n');
                return sb.toString().replaceAll("(\n){3,}", "\n\n").trim();
            }
        }
        return text;
    }

    private String stripInlineMetadata(String block)
    {
        String[] lines = block.split("\n");
        int start = 0;
        boolean foundStruct = false;
        for(int i = 0; i < lines.length; i++)
        {
            String l = lines[i].trim();
            if(l.isEmpty())
                continue;
            if(l.matches("^\\[[^\\]]+\\]$"))
            {
                foundStruct = true;
                start = i;
                break;
            }
            String lower = l.toLowerCase();
            boolean languageLike = lower.matches("^[a-zA-Zçéèíöőüûäåøñßœæğşıãáõçčžýðúíőű'`.-]{2,}(?: [a-zA-Zçéèíöőüûäåøñßœæğşıãáõçčžýðúíőű'`.-]{2,}){0,3}$") && l.length() <= 25;
            boolean meta = languageLike || lower.contains("contributors") || lower.startsWith("translations")
                    || lower.startsWith("read more") || lower.endsWith(" lyrics");
            if(!meta)
            {
                start = i;
                break;
            }
        }
        if(start == 0)
            return block;
        if(!foundStruct && start > 0)
            return String.join("\n", lines);
        StringBuilder sb = new StringBuilder();
        for(int i = start; i < lines.length; i++)
            sb.append(lines[i].trim()).append('\n');
        return sb.toString().trim();
    }

    private String formatSections(String text)
    {
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        StringBuilder current = new StringBuilder();
        boolean inSection = false;
        for(String raw : lines)
        {
            String line = raw.trim();
            if(line.isEmpty())
            {
                if(inSection && current.length() > 0 && current.charAt(current.length() - 1) != '\n')
                    current.append('\n');
                continue;
            }
            boolean header = line.matches("^\\[[^\\]]+\\]$");
            if(header)
            {
                if(current.length() > 0)
                {
                    while(current.length() > 0 && current.charAt(current.length() - 1) == '\n')
                        current.setLength(current.length() - 1);
                    out.append(current).append("\n\n");
                    current.setLength(0);
                }
                current.append(line).append('\n');
                inSection = true;
            }
            else
            {
                if(!inSection)
                    inSection = true;
                current.append(line).append('\n');
            }
        }
        if(current.length() > 0)
        {
            while(current.length() > 0 && current.charAt(current.length() - 1) == '\n')
                current.setLength(current.length() - 1);
            out.append(current).append('\n');
        }
        String formatted = out.toString().replaceAll("(\n){3,}", "\n\n").trim();
        return formatted + '\n';
    }

    private String capitalizeWords(String s)
    {
        String[] parts = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for(String p : parts)
        {
            if(p.isEmpty())
                continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    public static class GeniusSong
    {
        private final String artist;
        private final String title;
        private final String path;
        private final String sourceUrl;
        private final String lyrics;

        GeniusSong(String artist, String title, String path, String sourceUrl, String lyrics)
        {
            this.artist = artist == null ? "" : artist;
            this.title = title == null ? "" : title;
            this.path = path;
            this.sourceUrl = sourceUrl;
            this.lyrics = lyrics;
        }

        public String artist()
        {
            return artist;
        }

        public String title()
        {
            return title;
        }

        public String path()
        {
            return path;
        }

        public String sourceUrl()
        {
            return sourceUrl;
        }

        public String lyrics()
        {
            return lyrics;
        }
    }
}
