package com.jagrosh.jmusicbot.lyrics;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class LyricsCache
{
    private final Path dbPath;
    private Connection connection;

    public LyricsCache(Path dbPath)
    {
        this.dbPath = dbPath;
    }

    public synchronized void init() throws SQLException
    {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS songs ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "artist TEXT,"
                    + "title TEXT,"
                    + "path TEXT UNIQUE,"
                    + "keywords TEXT,"
                    + "lyrics TEXT,"
                    + "source_url TEXT,"
                    + "provider TEXT DEFAULT 'genius',"
                    + "source_id TEXT,"
                    + "normalized_artist TEXT,"
                    + "normalized_title TEXT,"
                    + "lookup_terms TEXT,"
                    + "created_at INTEGER,"
                    + "updated_at INTEGER"
                    + ")");
            // Negative cache: queries that returned no lyrics from any provider, so we
            // don't re-hit the (rate-limited) providers for the same song repeatedly.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS lyrics_misses ("
                    + "query TEXT PRIMARY KEY,"
                    + "created_at INTEGER NOT NULL"
                    + ")");
        }
        migrateColumns();
        ensureIndexes();
        backfillLookupColumns();
    }

    /** Records that {@code query} yielded no lyrics from any provider (negative cache). */
    public synchronized void recordMiss(String query) throws SQLException
    {
        if(query == null || query.isBlank())
            return;
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO lyrics_misses(query, created_at) VALUES(?, ?) "
                + "ON CONFLICT(query) DO UPDATE SET created_at=excluded.created_at"))
        {
            ps.setString(1, query);
            ps.setLong(2, Instant.now().toEpochMilli());
            ps.executeUpdate();
        }
    }

    /** True if {@code query} was recorded as a miss within the last {@code ttlMillis}. */
    public synchronized boolean isRecentMiss(String query, long ttlMillis) throws SQLException
    {
        if(query == null || query.isBlank())
            return false;
        try(PreparedStatement ps = connection.prepareStatement("SELECT created_at FROM lyrics_misses WHERE query=?"))
        {
            ps.setString(1, query);
            try(ResultSet rs = ps.executeQuery())
            {
                if(!rs.next())
                    return false;
                long age = Instant.now().toEpochMilli() - rs.getLong(1);
                return age >= 0 && age < ttlMillis;
            }
        }
    }

    /** Removes any negative-cache entry for {@code query} (e.g. once lyrics are found). */
    public synchronized void clearMiss(String query) throws SQLException
    {
        if(query == null || query.isBlank())
            return;
        try(PreparedStatement ps = connection.prepareStatement("DELETE FROM lyrics_misses WHERE query=?"))
        {
            ps.setString(1, query);
            ps.executeUpdate();
        }
    }

    public synchronized Optional<CachedLyrics> findByArtistTitle(String artist, String title) throws SQLException
    {
        return findByNormalizedArtistTitle(InputValidator.normalizeLookup(artist), InputValidator.normalizeLookup(title));
    }

    public synchronized Optional<CachedLyrics> findByPath(String path) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement("SELECT * FROM songs WHERE path=? LIMIT 1"))
        {
            ps.setString(1, path);
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                    return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<CachedLyrics> findBestMatch(String rawQuery) throws SQLException
    {
        String query = InputValidator.sanitizeQuery(rawQuery);
        if(query == null)
            return Optional.empty();

        Optional<CachedLyrics> direct = findDirectSource(query);
        if(direct.isPresent())
            return direct;

        for(String term : InputValidator.lookupTerms(query))
        {
            Optional<CachedLyrics> byTerm = findByLookupTerm(term);
            if(byTerm.isPresent())
                return byTerm;
        }

        String[] artistTitle = InputValidator.splitArtistTitle(query);
        if(artistTitle != null)
        {
            Optional<CachedLyrics> byArtistTitle = findByNormalizedArtistTitle(
                    InputValidator.normalizeLookup(artistTitle[0]),
                    InputValidator.normalizeLookup(artistTitle[1])
            );
            if(byArtistTitle.isPresent())
                return byArtistTitle;
        }

        Optional<CachedLyrics> titleOnly = findUniqueTitleMatch(query);
        if(titleOnly.isPresent())
            return titleOnly;
        if(artistTitle == null)
            return Optional.empty();

        List<CachedLyrics> candidates = search(query, 10);
        CachedLyrics best = null;
        double bestScore = -1d;
        for(CachedLyrics candidate : candidates)
        {
            double score = score(query, candidate);
            if(score > bestScore)
            {
                best = candidate;
                bestScore = score;
            }
        }
        if(best != null && bestScore >= 0.50d)
            return Optional.of(best);

        return Optional.empty();
    }

    public synchronized Optional<CachedLyrics> findExactTargetForCorrection(String rawQuery) throws SQLException
    {
        String query = InputValidator.sanitizeQuery(rawQuery);
        if(query == null)
            return Optional.empty();

        for(String term : InputValidator.lookupTerms(query))
        {
            Optional<CachedLyrics> byTerm = findByLookupTerm(term);
            if(byTerm.isPresent())
                return byTerm;
        }

        String[] artistTitle = InputValidator.splitArtistTitle(query);
        if(artistTitle != null)
            return findByNormalizedArtistTitle(InputValidator.normalizeLookup(artistTitle[0]), InputValidator.normalizeLookup(artistTitle[1]));

        return findUniqueTitleMatch(query);
    }

    public synchronized List<CachedLyrics> search(String term, int limit) throws SQLException
    {
        String like = "%" + InputValidator.normalizeLookup(term) + "%";
        String rawLike = "%" + term.toLowerCase() + "%";
        String sql = "SELECT * FROM songs "
                + "WHERE lower(artist) LIKE ? OR lower(title) LIKE ? OR keywords LIKE ? OR lookup_terms LIKE ? "
                + "ORDER BY updated_at DESC LIMIT ?";
        List<CachedLyrics> list = new ArrayList<>();
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setString(1, rawLike);
            ps.setString(2, rawLike);
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setInt(5, limit);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    list.add(map(rs));
            }
        }
        return list;
    }

    public synchronized CachedLyrics insertOrUpdate(String artist, String title, String path, String keywords,
                                                   String lyrics, String sourceUrl) throws SQLException
    {
        String provider = path != null && path.startsWith("lrclib:") ? "lrclib" : "genius";
        return insertOrUpdate(provider, path, path, artist, title, path, keywords, lyrics, "", sourceUrl, Set.of());
    }

    public synchronized CachedLyrics insertOrUpdate(LyricsResult result, Collection<String> extraLookupTerms) throws SQLException
    {
        if(result == null || !result.hasLyrics())
            throw new SQLException("Cannot cache empty lyrics result");
        Set<String> extra = new LinkedHashSet<>();
        if(extraLookupTerms != null)
            extra.addAll(extraLookupTerms);
        extra.addAll(result.aliases());
        return insertOrUpdate(
                result.provider(),
                result.sourceId(),
                result.sourceKey(),
                result.artist(),
                result.title(),
                result.sourceKey(),
                "",
                result.lyrics(),
                result.syncedLyrics(),
                result.sourceUrl(),
                extra
        );
    }

    public synchronized Optional<CachedLyrics> replaceForQuery(String query, LyricsResult result,
                                                               Collection<String> extraLookupTerms) throws SQLException
    {
        Optional<CachedLyrics> target = findExactTargetForCorrection(query);
        Set<String> terms = new LinkedHashSet<>();
        if(extraLookupTerms != null)
            terms.addAll(extraLookupTerms);
        terms.add(query);
        CachedLyrics updated = insertOrUpdate(result, terms);
        if(target.isPresent() && target.get().id() != updated.id())
            deleteById(target.get().id());
        return Optional.of(updated);
    }

    public synchronized Optional<CachedLyrics> lastUpdated() throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement("SELECT * FROM songs ORDER BY updated_at DESC LIMIT 1");
            ResultSet rs = ps.executeQuery())
        {
            if(rs.next())
                return Optional.of(map(rs));
        }
        return Optional.empty();
    }

    @Deprecated
    public synchronized Optional<CachedLyrics> correctLast(String geniusUrl, String lyrics) throws SQLException
    {
        Optional<CachedLyrics> last = lastUpdated();
        if(last.isEmpty())
            return Optional.empty();
        CachedLyrics previous = last.get();
        String path = extractPathFromUrl(geniusUrl);
        if(path == null)
            path = previous.path();
        String safeLyrics = InputValidator.sanitizeLyrics(lyrics);
        LyricsResult result = new LyricsResult("genius", path, path, geniusUrl, previous.artist, previous.title,
                safeLyrics == null ? previous.lyrics : safeLyrics, Set.of(previous.artist + " " + previous.title));
        return Optional.of(insertOrUpdate(result, Set.of(geniusUrl)));
    }

    @Deprecated
    public synchronized Optional<CachedLyrics> replaceLastWith(String artist, String title, String path, String keywords,
                                                               String lyrics, String sourceUrl) throws SQLException
    {
        Optional<CachedLyrics> last = lastUpdated();
        CachedLyrics newRow = insertOrUpdate(artist, title, path, keywords, lyrics, sourceUrl);
        if(last.isPresent() && last.get().id() != newRow.id() && !last.get().path().equals(path))
            deleteById(last.get().id());
        return Optional.of(newRow);
    }

    public static String extractPathFromUrl(String url)
    {
        if(url == null)
            return null;
        String clean = url.trim();
        int query = clean.indexOf('?');
        int hash = clean.indexOf('#');
        int cut = -1;
        if(query >= 0)
            cut = query;
        if(hash >= 0)
            cut = cut < 0 ? hash : Math.min(cut, hash);
        if(cut >= 0)
            clean = clean.substring(0, cut);
        while(clean.endsWith("/") && clean.length() > 1)
            clean = clean.substring(0, clean.length() - 1);

        int idx = clean.indexOf("genius.com/");
        if(idx >= 0)
        {
            String after = clean.substring(idx + "genius.com".length());
            if(!after.startsWith("/"))
                after = "/" + after;
            return after.endsWith("-lyrics") ? after : null;
        }
        if(clean.startsWith("/") && clean.endsWith("-lyrics"))
            return clean;
        return null;
    }

    private CachedLyrics insertOrUpdate(String provider, String sourceId, String sourceKey, String artist, String title,
                                        String path, String keywords, String lyrics, String syncedLyrics, String sourceUrl,
                                        Collection<String> extraLookupTerms) throws SQLException
    {
        long now = Instant.now().toEpochMilli();
        String safePath = sourceKey == null || sourceKey.isBlank() ? path : sourceKey.trim();
        if(safePath == null || safePath.isBlank())
            throw new SQLException("Invalid source key");

        String safeLyrics = InputValidator.sanitizeLyrics(lyrics);
        if(safeLyrics == null || safeLyrics.isBlank())
            throw new SQLException("Invalid lyrics");

        String safeSynced = syncedLyrics == null ? "" : syncedLyrics.trim();
        String safeArtist = artist == null ? "" : artist.trim();
        String safeTitle = title == null ? "" : title.trim();
        String safeProvider = provider == null || provider.isBlank() ? inferProvider(safePath) : provider.trim();
        String safeSource = sourceUrl == null ? "" : sourceUrl.trim();
        String safeSourceId = sourceId == null || sourceId.isBlank() ? safePath : sourceId.trim();
        String safeKeywords = buildKeywords(safeArtist, safeTitle, safePath, keywords, safeSource, extraLookupTerms);
        String normalizedArtist = InputValidator.normalizeLookup(safeArtist);
        String normalizedTitle = InputValidator.normalizeLookup(safeTitle);
        String lookupTerms = buildLookupTerms(safeArtist, safeTitle, safePath, safeSource, extraLookupTerms);

        String sql = "INSERT INTO songs(artist,title,path,keywords,lyrics,synced_lyrics,source_url,provider,source_id,"
                + "normalized_artist,normalized_title,lookup_terms,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(path) DO UPDATE SET "
                + "artist=excluded.artist,"
                + "title=excluded.title,"
                + "keywords=excluded.keywords,"
                + "lyrics=excluded.lyrics,"
                + "synced_lyrics=excluded.synced_lyrics,"
                + "source_url=excluded.source_url,"
                + "provider=excluded.provider,"
                + "source_id=excluded.source_id,"
                + "normalized_artist=excluded.normalized_artist,"
                + "normalized_title=excluded.normalized_title,"
                + "lookup_terms=excluded.lookup_terms,"
                + "updated_at=excluded.updated_at";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setString(1, safeArtist);
            ps.setString(2, safeTitle);
            ps.setString(3, safePath);
            ps.setString(4, safeKeywords);
            ps.setString(5, safeLyrics);
            ps.setString(6, safeSynced);
            ps.setString(7, safeSource);
            ps.setString(8, safeProvider);
            ps.setString(9, safeSourceId);
            ps.setString(10, normalizedArtist);
            ps.setString(11, normalizedTitle);
            ps.setString(12, lookupTerms);
            ps.setLong(13, now);
            ps.setLong(14, now);
            ps.executeUpdate();
        }
        return findByPath(safePath).orElseThrow();
    }

    private Optional<CachedLyrics> findDirectSource(String query) throws SQLException
    {
        String geniusPath = extractPathFromUrl(query);
        if(geniusPath != null)
        {
            Optional<CachedLyrics> byPath = findByPath(geniusPath);
            if(byPath.isPresent())
                return byPath;
        }
        if(query.startsWith("/") || query.startsWith("lrclib:"))
        {
            Optional<CachedLyrics> byPath = findByPath(query);
            if(byPath.isPresent())
                return byPath;
        }
        try(PreparedStatement ps = connection.prepareStatement("SELECT * FROM songs WHERE source_url=? LIMIT 1"))
        {
            ps.setString(1, query);
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                    return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    private Optional<CachedLyrics> findByLookupTerm(String normalizedTerm) throws SQLException
    {
        String term = InputValidator.normalizeLookup(normalizedTerm);
        if(term.isEmpty())
            return Optional.empty();
        try(PreparedStatement ps = connection.prepareStatement("SELECT * FROM songs WHERE lookup_terms LIKE ? ORDER BY updated_at DESC LIMIT 2"))
        {
            ps.setString(1, "%\n" + term + "\n%");
            try(ResultSet rs = ps.executeQuery())
            {
                CachedLyrics first = null;
                int count = 0;
                while(rs.next())
                {
                    count++;
                    if(first == null)
                        first = map(rs);
                }
                if(count == 1 && first != null)
                    return Optional.of(first);
            }
        }
        return Optional.empty();
    }

    private Optional<CachedLyrics> findByNormalizedArtistTitle(String artist, String title) throws SQLException
    {
        if(artist.isBlank() || title.isBlank())
            return Optional.empty();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM songs WHERE normalized_artist=? AND normalized_title=? ORDER BY updated_at DESC LIMIT 1"))
        {
            ps.setString(1, artist);
            ps.setString(2, title);
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                    return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    private Optional<CachedLyrics> findUniqueTitleMatch(String query) throws SQLException
    {
        Set<String> possibleTitles = InputValidator.lookupTerms(query);
        for(String title : possibleTitles)
        {
            try(PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM songs WHERE normalized_title=? ORDER BY updated_at DESC LIMIT 2"))
            {
                ps.setString(1, title);
                try(ResultSet rs = ps.executeQuery())
                {
                    CachedLyrics first = null;
                    int count = 0;
                    while(rs.next())
                    {
                        count++;
                        if(first == null)
                            first = map(rs);
                    }
                    if(count == 1 && first != null)
                        return Optional.of(first);
                }
            }
        }
        return Optional.empty();
    }

    private double score(String query, CachedLyrics candidate)
    {
        String normalizedQuery = InputValidator.normalizeLookup(query);
        if(normalizedQuery.isEmpty())
            return 0d;
        String candidateText = (candidate.lookupTerms + " " + candidate.keywords + " "
                + candidate.normalizedArtist + " " + candidate.normalizedTitle).trim();
        if(candidate.lookupTerms.contains("\n" + normalizedQuery + "\n"))
            return 1.0d;
        if(candidate.normalizedTitle.equals(normalizedQuery))
            return 0.85d;
        if((candidate.normalizedArtist + " " + candidate.normalizedTitle).equals(normalizedQuery))
            return 1.0d;
        return tokenScore(normalizedQuery, candidateText);
    }

    private double tokenScore(String query, String candidate)
    {
        Set<String> q = new LinkedHashSet<>();
        Set<String> c = new LinkedHashSet<>();
        for(String token : query.split(" "))
            if(!token.isBlank())
                q.add(token);
        for(String token : candidate.split("[ \n]+"))
            if(!token.isBlank())
                c.add(token);
        if(q.isEmpty() || c.isEmpty())
            return 0d;

        int intersection = 0;
        for(String token : q)
            if(c.contains(token))
                intersection++;
        int union = q.size() + c.size() - intersection;
        double score = union == 0 ? 0d : intersection / (double) union;
        if(candidate.contains(query))
            score += 0.20d;
        return score;
    }

    private void migrateColumns() throws SQLException
    {
        addColumnIfMissing("provider", "TEXT DEFAULT 'genius'");
        addColumnIfMissing("source_id", "TEXT");
        addColumnIfMissing("normalized_artist", "TEXT");
        addColumnIfMissing("normalized_title", "TEXT");
        addColumnIfMissing("lookup_terms", "TEXT");
        addColumnIfMissing("synced_lyrics", "TEXT");
    }

    private void addColumnIfMissing(String name, String definition) throws SQLException
    {
        if(hasColumn(name))
            return;
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("ALTER TABLE songs ADD COLUMN " + name + " " + definition);
        }
    }

    private boolean hasColumn(String name) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement("PRAGMA table_info(songs)");
            ResultSet rs = ps.executeQuery())
        {
            while(rs.next())
                if(name.equalsIgnoreCase(rs.getString("name")))
                    return true;
        }
        return false;
    }

    private void ensureIndexes() throws SQLException
    {
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_songs_artist_title ON songs(artist,title)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_songs_keywords ON songs(keywords)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_songs_normalized_artist_title ON songs(normalized_artist,normalized_title)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_songs_normalized_title ON songs(normalized_title)");
        }
    }

    private void backfillLookupColumns() throws SQLException
    {
        List<CachedLyrics> rows = new ArrayList<>();
        try(PreparedStatement ps = connection.prepareStatement("SELECT * FROM songs");
            ResultSet rs = ps.executeQuery())
        {
            while(rs.next())
                rows.add(map(rs));
        }

        for(CachedLyrics row : rows)
        {
            if(!row.normalizedArtist.isBlank() && !row.normalizedTitle.isBlank() && !row.lookupTerms.isBlank()
                    && !row.provider.isBlank() && !row.sourceId.isBlank())
                continue;
            String normalizedArtist = InputValidator.normalizeLookup(row.artist);
            String normalizedTitle = InputValidator.normalizeLookup(row.title);
            String provider = row.provider.isBlank() ? inferProvider(row.path) : row.provider;
            String sourceId = row.sourceId.isBlank() ? row.path : row.sourceId;
            String lookupTerms = buildLookupTerms(row.artist, row.title, row.path, row.sourceUrl, Set.of(row.keywords));
            try(PreparedStatement ps = connection.prepareStatement(
                    "UPDATE songs SET provider=?, source_id=?, normalized_artist=?, normalized_title=?, lookup_terms=? WHERE id=?"))
            {
                ps.setString(1, provider);
                ps.setString(2, sourceId);
                ps.setString(3, normalizedArtist);
                ps.setString(4, normalizedTitle);
                ps.setString(5, lookupTerms);
                ps.setLong(6, row.id);
                ps.executeUpdate();
            }
        }
    }

    private String buildKeywords(String artist, String title, String path, String keywords, String sourceUrl,
                                 Collection<String> extraLookupTerms)
    {
        Set<String> terms = new LinkedHashSet<>();
        InputValidator.addLookupTerm(terms, artist);
        InputValidator.addLookupTerm(terms, title);
        InputValidator.addLookupTerm(terms, artist + " " + title);
        InputValidator.addLookupTerm(terms, path);
        InputValidator.addLookupTerm(terms, sourceUrl);
        if(keywords != null)
            InputValidator.addLookupTerm(terms, keywords);
        if(extraLookupTerms != null)
            for(String term : extraLookupTerms)
                InputValidator.addLookupTerm(terms, term);
        return String.join(" ", terms);
    }

    private String buildLookupTerms(String artist, String title, String path, String sourceUrl,
                                    Collection<String> extraLookupTerms)
    {
        Set<String> terms = new LinkedHashSet<>();
        addAll(terms, InputValidator.lookupTerms(artist + " " + title));
        addAll(terms, InputValidator.lookupTerms(artist + " - " + title));
        addAll(terms, InputValidator.lookupTerms(title));
        InputValidator.addLookupTerm(terms, path);
        InputValidator.addLookupTerm(terms, sourceUrl);
        if(extraLookupTerms != null)
            for(String term : extraLookupTerms)
                addAll(terms, InputValidator.lookupTerms(term));

        StringBuilder sb = new StringBuilder("\n");
        for(String term : terms)
            sb.append(term).append('\n');
        return sb.toString();
    }

    private void addAll(Set<String> target, Set<String> values)
    {
        for(String value : values)
            if(value != null && !value.isBlank())
                target.add(value);
    }

    private String inferProvider(String path)
    {
        return path != null && path.startsWith("lrclib:") ? "lrclib" : "genius";
    }

    private void deleteById(long id) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement("DELETE FROM songs WHERE id=?"))
        {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private CachedLyrics map(ResultSet rs) throws SQLException
    {
        return new CachedLyrics(
                rs.getLong("id"),
                nullToEmpty(rs.getString("artist")),
                nullToEmpty(rs.getString("title")),
                nullToEmpty(rs.getString("path")),
                nullToEmpty(rs.getString("keywords")),
                nullToEmpty(rs.getString("lyrics")),
                nullToEmpty(rs.getString("synced_lyrics")),
                nullToEmpty(rs.getString("source_url")),
                nullToEmpty(rs.getString("provider")),
                nullToEmpty(rs.getString("source_id")),
                nullToEmpty(rs.getString("normalized_artist")),
                nullToEmpty(rs.getString("normalized_title")),
                nullToEmpty(rs.getString("lookup_terms")),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    public static class CachedLyrics
    {
        private final long id;
        private final String artist;
        private final String title;
        private final String path;
        private final String keywords;
        private final String lyrics;
        private final String syncedLyrics;
        private final String sourceUrl;
        private final String provider;
        private final String sourceId;
        private final String normalizedArtist;
        private final String normalizedTitle;
        private final String lookupTerms;
        private final long createdAt;
        private final long updatedAt;

        public CachedLyrics(long id, String artist, String title, String path, String keywords, String lyrics,
                            String sourceUrl, String provider, String sourceId, String normalizedArtist,
                            String normalizedTitle, String lookupTerms, long createdAt, long updatedAt)
        {
            this(id, artist, title, path, keywords, lyrics, "", sourceUrl, provider, sourceId,
                    normalizedArtist, normalizedTitle, lookupTerms, createdAt, updatedAt);
        }

        public CachedLyrics(long id, String artist, String title, String path, String keywords, String lyrics,
                            String syncedLyrics, String sourceUrl, String provider, String sourceId,
                            String normalizedArtist, String normalizedTitle, String lookupTerms,
                            long createdAt, long updatedAt)
        {
            this.id = id;
            this.artist = artist;
            this.title = title;
            this.path = path;
            this.keywords = keywords;
            this.lyrics = lyrics;
            this.syncedLyrics = syncedLyrics == null ? "" : syncedLyrics;
            this.sourceUrl = sourceUrl;
            this.provider = provider;
            this.sourceId = sourceId;
            this.normalizedArtist = normalizedArtist;
            this.normalizedTitle = normalizedTitle;
            this.lookupTerms = lookupTerms;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public long id()
        {
            return id;
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

        public String keywords()
        {
            return keywords;
        }

        public String lyrics()
        {
            return lyrics;
        }

        /** Raw LRC-format time-synced lyrics for this song, or empty if none were cached. */
        public String syncedLyrics()
        {
            return syncedLyrics;
        }

        public boolean hasSyncedLyrics()
        {
            return syncedLyrics != null && !syncedLyrics.isBlank();
        }

        public String sourceUrl()
        {
            return sourceUrl;
        }

        public String provider()
        {
            return provider;
        }

        public String sourceId()
        {
            return sourceId;
        }

        public long createdAt()
        {
            return createdAt;
        }

        public long updatedAt()
        {
            return updatedAt;
        }
    }
}
