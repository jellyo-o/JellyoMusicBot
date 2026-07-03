package com.jagrosh.jmusicbot.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

/**
 * Builds a canonical "Artist - Title" lyrics lookup key from track metadata, so
 * preload and lookup agree on the key and the artist disambiguates the match
 * (e.g. "Time" by NF resolves to NF, not "Drake - From Time").
 */
public final class LyricsQuery
{
    private LyricsQuery() {}

    public static String forTrack(AudioTrack track)
    {
        if(track == null || track.getInfo() == null)
            return "";
        return forTitleAndAuthor(track.getInfo().title, track.getInfo().author);
    }

    public static String forTitleAndAuthor(String title, String author)
    {
        String t = title == null ? "" : title.trim();
        if(t.isEmpty())
            return "";
        String artist = cleanArtist(author);
        if(artist.isEmpty())
            return t;
        String tl = t.toLowerCase();
        String al = artist.toLowerCase();
        // Don't prepend again if the title already has an "Artist - Title" delimiter, or
        // already contains the artist as a whole word. Use a space-padded token check so a
        // short artist like "Dr" isn't matched inside "Adrenaline".
        if(tl.contains(" - ") || (" " + tl + " ").contains(" " + al + " "))
            return t;
        return artist + " - " + t;
    }

    /** Strips YouTube auto-channel noise ("- Topic", "VEVO", "Official") from an
     *  uploader/author string so what remains is the artist name. */
    static String cleanArtist(String author)
    {
        if(author == null)
            return "";
        String a = author.trim();
        a = a.replaceAll("(?i)\\s*-\\s*topic\\s*$", ""); // "Artist - Topic"
        a = a.replaceAll("(?i)vevo\\s*$", "");           // concatenated "ArtistVEVO"
        a = a.replaceAll("(?i)\\bvevo\\b", " ");         // standalone "VEVO"
        a = a.replaceAll("(?i)\\bofficial\\b", " ");     // "... Official"
        a = a.replaceAll("(?i)\\btopic\\b", " ");        // stray "Topic"
        a = a.replaceAll("\\s{2,}", " ").trim();
        return a;
    }
}
