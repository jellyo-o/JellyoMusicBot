package com.jagrosh.jmusicbot.lyrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class LyricsCache {
    private final Path dbPath; private Connection connection;
    public LyricsCache(Path dbPath) { this.dbPath = dbPath; }
    public synchronized void init() throws SQLException { boolean first = !Files.exists(dbPath); connection = DriverManager.getConnection("jdbc:sqlite:"+dbPath.toAbsolutePath()); if (first) { try (Statement st = connection.createStatement()) { st.executeUpdate("CREATE TABLE IF NOT EXISTS songs (id INTEGER PRIMARY KEY AUTOINCREMENT, artist TEXT, title TEXT, path TEXT UNIQUE, keywords TEXT, lyrics TEXT, source_url TEXT, created_at INTEGER, updated_at INTEGER)"); st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_songs_artist_title ON songs(artist,title)"); st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_songs_keywords ON songs(keywords)"); } } }
    public synchronized Optional<CachedLyrics> findByArtistTitle(String artist, String title) throws SQLException { String sql = "SELECT * FROM songs WHERE lower(artist)=? AND lower(title)=? LIMIT 1"; try (PreparedStatement ps = connection.prepareStatement(sql)) { ps.setString(1, artist.toLowerCase()); ps.setString(2, title.toLowerCase()); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return Optional.of(map(rs)); } } return Optional.empty(); }
    public synchronized Optional<CachedLyrics> findByPath(String path) throws SQLException { try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM songs WHERE path=? LIMIT 1")) { ps.setString(1, path); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return Optional.of(map(rs)); } } return Optional.empty(); }
    public synchronized List<CachedLyrics> search(String term, int limit) throws SQLException { String like = "%"+term.toLowerCase()+"%"; String sql = "SELECT * FROM songs WHERE lower(artist) LIKE ? OR lower(title) LIKE ? OR keywords LIKE ? ORDER BY updated_at DESC LIMIT ?"; List<CachedLyrics> list = new ArrayList<>(); try (PreparedStatement ps = connection.prepareStatement(sql)) { ps.setString(1, like); ps.setString(2, like); ps.setString(3, like); ps.setInt(4, limit); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); } } return list; }
    public synchronized CachedLyrics insertOrUpdate(String artist, String title, String path, String keywords, String lyrics, String sourceUrl) throws SQLException { long now = Instant.now().toEpochMilli(); if (!InputValidator.isValidGeniusPath(path)) throw new SQLException("Invalid path"); String safeLyrics = InputValidator.sanitizeLyrics(lyrics); String safeArtist = artist==null?"":artist.trim(); String safeTitle = title==null?"":title.trim(); String safeSource = sourceUrl==null?"":sourceUrl.trim(); String safeKeywords = keywords==null?"":keywords.toLowerCase().replaceAll("[^a-z0-9 ]"," ").replaceAll(" +"," ").trim(); String sql = "INSERT INTO songs(artist,title,path,keywords,lyrics,source_url,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET artist=excluded.artist,title=excluded.title,keywords=excluded.keywords,lyrics=excluded.lyrics,source_url=excluded.source_url,updated_at=excluded.updated_at"; try (PreparedStatement ps = connection.prepareStatement(sql)) { ps.setString(1, safeArtist); ps.setString(2, safeTitle); ps.setString(3, path); ps.setString(4, safeKeywords); ps.setString(5, safeLyrics); ps.setString(6, safeSource); ps.setLong(7, now); ps.setLong(8, now); ps.executeUpdate(); } return findByPath(path).orElseThrow(); }
    public synchronized Optional<CachedLyrics> lastUpdated() throws SQLException { try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM songs ORDER BY updated_at DESC LIMIT 1"); ResultSet rs = ps.executeQuery()) { if (rs.next()) return Optional.of(map(rs)); } return Optional.empty(); }
    public synchronized Optional<CachedLyrics> correctLast(String geniusUrl, String lyrics) throws SQLException { Optional<CachedLyrics> last = lastUpdated(); if (last.isEmpty()) return Optional.empty(); CachedLyrics prev = last.get(); String path = extractPathFromUrl(geniusUrl); if (path == null) path = prev.path(); if (!InputValidator.isValidGeniusPath(path)) return Optional.empty(); String safeLyrics = InputValidator.sanitizeLyrics(lyrics); String newKeywords = (prev.keywords()+" "+geniusUrl).toLowerCase(); CachedLyrics updated = insertOrUpdate(prev.artist, prev.title, path, newKeywords, safeLyrics==null?prev.lyrics:safeLyrics, geniusUrl); return Optional.of(updated); }
    public synchronized Optional<CachedLyrics> replaceLastWith(String artist, String title, String path, String keywords, String lyrics, String sourceUrl) throws SQLException { Optional<CachedLyrics> last = lastUpdated(); CachedLyrics newRow = insertOrUpdate(artist, title, path, keywords, lyrics, sourceUrl); if (last.isPresent() && last.get().id()!=newRow.id() && !last.get().path().equals(path)) { try (PreparedStatement ps = connection.prepareStatement("DELETE FROM songs WHERE id=?")) { ps.setLong(1, last.get().id()); ps.executeUpdate(); } } return Optional.of(newRow); }
    public static String extractPathFromUrl(String url) { if (url==null) return null; int idx = url.indexOf("genius.com/"); if (idx>=0) { String after = url.substring(idx+"genius.com".length()); if (!after.startsWith("/")) after = "/"+after; if (!after.endsWith("-lyrics")) return null; return after; } if (url.startsWith("/") && url.endsWith("-lyrics")) return url; return null; }
    public static class CachedLyrics {
        private final long id; private final String artist; private final String title; private final String path; private final String keywords; private final String lyrics; private final String sourceUrl; private final long createdAt; private final long updatedAt;
        public CachedLyrics(long id, String artist, String title, String path, String keywords, String lyrics, String sourceUrl, long createdAt, long updatedAt) {
            this.id=id; this.artist=artist; this.title=title; this.path=path; this.keywords=keywords; this.lyrics=lyrics; this.sourceUrl=sourceUrl; this.createdAt=createdAt; this.updatedAt=updatedAt;
        }
        public long id(){ return id; }
        public String artist(){ return artist; }
        public String title(){ return title; }
        public String path(){ return path; }
        public String keywords(){ return keywords; }
        public String lyrics(){ return lyrics; }
        public String sourceUrl(){ return sourceUrl; }
        public long createdAt(){ return createdAt; }
        public long updatedAt(){ return updatedAt; }
    }
    private CachedLyrics map(ResultSet rs) throws SQLException { return new CachedLyrics(rs.getLong("id"), rs.getString("artist"), rs.getString("title"), rs.getString("path"), rs.getString("keywords"), rs.getString("lyrics"), rs.getString("source_url"), rs.getLong("created_at"), rs.getLong("updated_at")); }
}
