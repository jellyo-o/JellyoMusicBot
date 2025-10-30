package com.jagrosh.jmusicbot.lyrics;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class LyricsService {
    private final GeniusClient geniusClient = new GeniusClient();
    private final LyricsCache cache;
    public LyricsService(Path dbPath) throws Exception { this.cache = new LyricsCache(dbPath); this.cache.init(); }
    public Optional<LyricsCache.CachedLyrics> fetchAndCache(String rawQuery, boolean allowDifferentArtistFallback) throws IOException {
        String sanitized = InputValidator.sanitizeQuery(rawQuery);
        if (sanitized == null) return Optional.empty();
        boolean force = Boolean.getBoolean("lyrics.forceRefresh");
        if (!force) {
            try {
                Optional<LyricsCache.CachedLyrics> cached = getCachedFlexible(sanitized);
                if (cached.isPresent()) return cached; // serve from cache
            } catch (SQLException ignored) { }
        }
        try {
            Optional<String> pathOpt = geniusClient.findSongPath(sanitized, allowDifferentArtistFallback);
            if (pathOpt.isEmpty()) return Optional.empty();
            String path = pathOpt.get();
            if (!InputValidator.isValidGeniusPath(path)) return Optional.empty();
            Optional<String> lyricsOpt = geniusClient.fetchLyrics(path);
            if (lyricsOpt.isEmpty()) return Optional.empty();
            String slug = path.substring(path.lastIndexOf('/')+1);
            String core = slug.substring(0, slug.length()-"-lyrics".length());
            String[] segs = core.split("-");
            String artistGuess=""; String titleGuess="";
            if (segs.length>1) {
                titleGuess = capitalizeWords(segs[segs.length-1].replace('_',' '));
                artistGuess = capitalizeWords(String.join(" ", Arrays.copyOfRange(segs,0,segs.length-1)).replace('_',' '));
            } else {
                titleGuess = capitalizeWords(core.replace('_',' '));
            }
            String keywords = (sanitized+" "+core.replace('-',' ')+" "+artistGuess+" "+titleGuess).toLowerCase();
            return Optional.of(cache.insertOrUpdate(artistGuess, titleGuess, path, keywords, lyricsOpt.get(), "https://genius.com"+path));
        } catch (SQLException e) { return Optional.empty(); }
    }

    private Optional<LyricsCache.CachedLyrics> getCachedFlexible(String query) throws SQLException {
        if (query == null || query.isBlank()) return Optional.empty();
        String lowered = query.toLowerCase();
        // Direct path lookup
        if (query.startsWith("/")) {
            Optional<LyricsCache.CachedLyrics> r = cache.findByPath(query);
            if (r.isPresent()) return r;
        }
        // Genius URL path inside full url
        if (query.startsWith("http")) {
            String path = LyricsCache.extractPathFromUrl(query);
            if (path != null) {
                Optional<LyricsCache.CachedLyrics> r = cache.findByPath(path);
                if (r.isPresent()) return r;
            }
        }
        // Pattern: Artist - Title
        if (query.contains(" - ")) {
            String[] seg = query.split(" - ",2);
            if (seg.length==2) {
                Optional<LyricsCache.CachedLyrics> r = cache.findByArtistTitle(seg[0].trim(), seg[1].trim());
                if (r.isPresent()) return r;
            }
        }
        // Try partitions on spaces (first parts artist, rest title)
        String[] tokens = query.split(" ");
        if (tokens.length >= 2) {
            for (int split=1; split<tokens.length; split++) {
                String artist = String.join(" ", Arrays.copyOfRange(tokens,0,split));
                String title = String.join(" ", Arrays.copyOfRange(tokens,split,tokens.length));
                Optional<LyricsCache.CachedLyrics> r = cache.findByArtistTitle(artist, title);
                if (r.isPresent()) return r;
            }
        }
        // Keyword search with scoring instead of naive latest-first
        List<LyricsCache.CachedLyrics> list = cache.search(query, 10); // fetch several to score
        if (!list.isEmpty()) {
            LyricsCache.CachedLyrics best = null;
            double bestScore = -1d;
            for (LyricsCache.CachedLyrics c : list) {
                double s = scoreTitleMatch(lowered, c.title());
                // Light boost if title ends with the query phrase (common for multi-artist collabs)
                if (c.title().toLowerCase().endsWith(lowered)) s += 0.05;
                if (s > bestScore) { bestScore = s; best = c; }
            }
            if (best != null && bestScore >= 0.35) { // threshold to avoid poor matches
                return Optional.of(best);
            }
            // fallback to most recent first element if scoring inconclusive
            return Optional.of(list.get(0));
        }
        // Specific phrase fallback
        if (lowered.contains("what it sounds like")) {
            List<LyricsCache.CachedLyrics> phrase = cache.search("what it sounds like", 1);
            if (!phrase.isEmpty()) return Optional.of(phrase.get(0));
        }
        // Last updated heuristic
        Optional<LyricsCache.CachedLyrics> last = cache.lastUpdated();
        if (last.isPresent()) {
            String kw = last.get().keywords();
            String[] qTokens = lowered.replaceAll("[^a-z0-9 ]"," ").replaceAll(" +"," ").split(" ");
            int match=0; int total=0;
            for (String t: qTokens) { if (t.isBlank()) continue; total++; if (kw.contains(t)) match++; }
            if (total>0 && match >= Math.max(1, total-1)) return last;
        }
        return Optional.empty();
    }

    private double scoreTitleMatch(String queryLower, String title) {
        if (title == null || title.isBlank()) return 0d;
        String normTitle = title.toLowerCase().replaceAll("[^a-z0-9 ]"," ").replaceAll(" +"," ").trim();
        String normQuery = queryLower.replaceAll("[^a-z0-9 ]"," ").replaceAll(" +"," ").trim();
        if (normQuery.isEmpty() || normTitle.isEmpty()) return 0d;
        String[] qTokens = normQuery.split(" ");
        String[] tTokens = normTitle.split(" ");
        java.util.Set<String> qset = new java.util.LinkedHashSet<>(Arrays.asList(qTokens));
        java.util.Set<String> tset = new java.util.LinkedHashSet<>(Arrays.asList(tTokens));
        int intersection = 0;
        for (String q : qset) if (tset.contains(q)) intersection++;
        int union = qset.size() + tset.size() - intersection;
        if (union == 0) return 0d;
        double jaccard = intersection / (double) union;
        // bonus if title contains query as contiguous phrase
        if (normTitle.contains(normQuery)) jaccard += 0.25;
        // slight penalty for many extra leading tokens before matching phrase
        int idx = normTitle.indexOf(normQuery);
        if (idx > 0) {
            int leadingTokens = normTitle.substring(0, idx).split(" ").length;
            jaccard -= Math.min(0.15, leadingTokens * 0.02);
        }
        return jaccard;
    }
    public Optional<LyricsCache.CachedLyrics> fetchByGeniusUrl(String url) throws IOException { try { String path = LyricsCache.extractPathFromUrl(url); if (path==null || !InputValidator.isValidGeniusPath(path)) return Optional.empty(); Optional<String> lyricsOpt = geniusClient.fetchLyrics(path); if (lyricsOpt.isEmpty()) return Optional.empty(); String slug = path.substring(path.lastIndexOf('/')+1); String core = slug.substring(0, slug.length()-"-lyrics".length()); String[] segs = core.split("-"); String artistGuess=""; String titleGuess=""; if (segs.length>1) { titleGuess = capitalizeWords(segs[segs.length-1].replace('_',' ')); artistGuess = capitalizeWords(String.join(" ", java.util.Arrays.copyOfRange(segs,0,segs.length-1)).replace('_',' ')); } else { titleGuess = capitalizeWords(core.replace('_',' ')); } String keywords = (url+" "+core.replace('-',' ')+" "+artistGuess+" "+titleGuess).toLowerCase(); return Optional.of(cache.insertOrUpdate(artistGuess, titleGuess, path, keywords, lyricsOpt.get(), url)); } catch (SQLException e) { return Optional.empty(); } }
    public Optional<LyricsCache.CachedLyrics> replaceLastWithFetched(String url) throws IOException { try { String path = LyricsCache.extractPathFromUrl(url); if (path==null || !InputValidator.isValidGeniusPath(path)) return Optional.empty(); Optional<String> lyricsOpt = geniusClient.fetchLyrics(path); if (lyricsOpt.isEmpty()) return Optional.empty(); String slug = path.substring(path.lastIndexOf('/')+1); String core = slug.substring(0, slug.length()-"-lyrics".length()); String[] segs = core.split("-"); String artistGuess=""; String titleGuess=""; if (segs.length>1) { titleGuess = capitalizeWords(segs[segs.length-1].replace('_',' ')); artistGuess = capitalizeWords(String.join(" ", java.util.Arrays.copyOfRange(segs,0,segs.length-1)).replace('_',' ')); } else { titleGuess = capitalizeWords(core.replace('_',' ')); } String keywords = (url+" "+core.replace('-',' ')+" "+artistGuess+" "+titleGuess).toLowerCase(); return cache.replaceLastWith(artistGuess, titleGuess, path, keywords, lyricsOpt.get(), url); } catch (SQLException e) { return Optional.empty(); } }
    private String capitalizeWords(String s) { String[] parts = s.split(" "); StringBuilder sb = new StringBuilder(); for (String p: parts) { if (p.isEmpty()) continue; sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' '); } return sb.toString().trim(); }
}
