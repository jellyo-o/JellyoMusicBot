package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.lyrics.LrcLyrics;
import com.jagrosh.jmusicbot.lyrics.LyricsCache;
import com.jagrosh.jmusicbot.lyrics.LyricsQuery;
import com.jagrosh.jmusicbot.lyrics.LyricsService;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.awt.Color;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Owns the single per-guild "lyrics message" that auto-lyrics and {@code /karaoke} share.
 *
 * <p>On a song start it fetches the lyrics, and if the song has time-synced (LRC) lyrics it
 * runs a live {@link KaraokeSession} that advances line-by-line; otherwise it posts the full
 * plain lyrics once. Either way the message is <em>sticky</em>: the previous lyrics message is
 * edited in place across songs, unless it has scrolled past a threshold of newer messages, in
 * which case a fresh one is posted at the bottom. A looping/repeating song does not re-post the
 * plain lyrics (it is the same text), while karaoke restarts so the window tracks the replay.
 */
public class KaraokeManager
{
    // Repost the lyrics message once this many newer messages sit below it (it has scrolled away).
    // Mirrors the now-playing panel's system property so both behave the same by default.
    private static final int REPOST_THRESHOLD = Math.min(100, Math.max(1,
            Integer.getInteger("jmusicbot.autolyrics.moveAfterMessages", 5)));
    // Embed descriptions cap at 4096 chars; leave a little headroom for the plain fallback.
    private static final int MAX_PLAIN_DESC = 3900;

    private final Bot bot;
    private final Map<Long, KaraokeSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, Anchor> anchors = new ConcurrentHashMap<>();
    private final Map<Long, String> lastShownTrack = new ConcurrentHashMap<>();

    public KaraokeManager(Bot bot)
    {
        this.bot = bot;
    }

    /** Auto-lyrics entry point: a (non-stream) song started and the guild has auto-lyrics on. */
    public void onSongStart(Guild guild, AudioTrack track, TextChannel channel)
    {
        present(guild, track, channel, false, ignored -> {});
    }

    /** Playback stopped or the queue emptied: end any live karaoke, but keep the sticky anchor. */
    public void onSongEnd(long guildId)
    {
        stopSessionFor(guildId);
    }

    /** Manual {@code /karaoke}: force karaoke for the current song, reporting back via {@code feedback}. */
    public void startManual(Guild guild, AudioTrack track, TextChannel channel, Consumer<String> feedback)
    {
        present(guild, track, channel, true, feedback);
    }

    /** Stops every live session (bot shutdown). */
    public void shutdown()
    {
        sessions.values().forEach(KaraokeSession::stop);
        sessions.clear();
    }

    private void present(Guild guild, AudioTrack track, TextChannel channel, boolean manual, Consumer<String> feedback)
    {
        if(guild == null || track == null || channel == null)
        {
            if(manual)
                feedback.accept("There must be music playing to use that.");
            return;
        }
        LyricsService service = bot.getLyricsService();
        if(service == null)
        {
            if(manual)
                feedback.accept("Lyrics service failed to initialize.");
            return;
        }
        final long guildId = guild.getIdLong();
        final long channelId = channel.getIdLong();
        final String identifier = KaraokeSession.identifierOf(track);
        final String query = LyricsQuery.forTrack(track);
        if(query.isEmpty())
        {
            if(manual)
                feedback.accept("Could not work out the song name to search lyrics.");
            return;
        }
        final Color color = guild.getSelfMember().getColor();

        bot.getLyricsExecutor().submit(() ->
        {
            Optional<LyricsCache.CachedLyrics> found;
            try
            {
                found = service.fetchAndCache(query, true);
            }
            catch(Exception e)
            {
                found = Optional.empty();
            }
            if(found.isEmpty())
            {
                if(manual)
                    feedback.accept("Lyrics for `" + query + "` could not be found.");
                return;
            }
            LyricsCache.CachedLyrics lyrics = found.get();
            Guild g = bot.getJDA() == null ? null : bot.getJDA().getGuildById(guildId);
            if(g == null)
                return;
            TextChannel ch = g.getTextChannelById(channelId);
            if(ch == null)
                return;

            String titleLine = (lyrics.artist() == null || lyrics.artist().isBlank() ? "" : lyrics.artist() + " - ")
                    + lyrics.title();
            LrcLyrics lrc = LrcLyrics.parse(lyrics.syncedLyrics());
            if(!lrc.isEmpty())
            {
                startKaraoke(g, ch, guildId, identifier, lrc, titleLine, lyrics.sourceUrl(), color);
                if(manual)
                    feedback.accept("Karaoke started for **" + titleLine + "**.");
            }
            else
            {
                // Same song looping in auto mode: the plain lyrics are unchanged, so leave the message be.
                if(!manual && identifier != null && identifier.equals(lastShownTrack.get(guildId)))
                    return;
                plainPost(ch, guildId, titleLine, lyrics, color);
                lastShownTrack.put(guildId, identifier == null ? "" : identifier);
                if(manual)
                    feedback.accept("No time-synced lyrics for **" + titleLine + "**. Posted the full lyrics instead.");
            }
        });
    }

    private void startKaraoke(Guild guild, TextChannel channel, long guildId, String identifier, LrcLyrics lrc,
                              String titleLine, String sourceUrl, Color color)
    {
        int idx = currentIndexFor(guild, lrc);
        MessageEmbed initial = KaraokeSession.buildEmbed(lrc, idx, titleLine, sourceUrl, color);
        lastShownTrack.put(guildId, identifier == null ? "" : identifier);
        placeMessage(guildId, channel, initial, messageId ->
        {
            KaraokeSession session = new KaraokeSession(bot, guildId, channel.getIdLong(), messageId,
                    identifier, lrc, titleLine, sourceUrl, color);
            KaraokeSession old = sessions.put(guildId, session);
            if(old != null)
                old.stop();
            session.start();
        });
    }

    private void plainPost(TextChannel channel, long guildId, String titleLine, LyricsCache.CachedLyrics lyrics, Color color)
    {
        String content = plainDescription(lyrics.lyrics(), MAX_PLAIN_DESC);
        if(content.isBlank())
            return;
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle(titleLine, blankToNull(lyrics.sourceUrl()))
                .setDescription(content);
        if(color != null)
            eb.setColor(color);
        stopSessionFor(guildId); // a plain song after a karaoke one ends the live view
        placeMessage(guildId, channel, eb.build(), messageId -> {});
    }

    /**
     * Edits the guild's sticky lyrics message in place, unless it has scrolled past
     * {@link #REPOST_THRESHOLD} newer messages, in which case a fresh one is sent. Calls
     * {@code onPlaced} with the resulting message id.
     */
    private void placeMessage(long guildId, TextChannel channel, MessageEmbed embed, LongConsumer onPlaced)
    {
        Anchor anchor = anchors.get(guildId);
        if(anchor != null && anchor.channelId == channel.getIdLong())
        {
            try
            {
                channel.getHistoryAfter(anchor.messageId, REPOST_THRESHOLD).queue(history ->
                {
                    if(NowplayingHandler.shouldMovePanel(history.size(), REPOST_THRESHOLD))
                        sendNew(guildId, channel, embed, onPlaced);
                    else
                        editExisting(guildId, channel, anchor.messageId, embed, onPlaced);
                }, t -> sendNew(guildId, channel, embed, onPlaced));
            }
            catch(RuntimeException ex)
            {
                sendNew(guildId, channel, embed, onPlaced);
            }
        }
        else
        {
            sendNew(guildId, channel, embed, onPlaced);
        }
    }

    private void editExisting(long guildId, TextChannel channel, long messageId, MessageEmbed embed, LongConsumer onPlaced)
    {
        try
        {
            channel.editMessageEmbedsById(messageId, embed)
                    .queue(m -> onPlaced.accept(messageId), t -> sendNew(guildId, channel, embed, onPlaced));
        }
        catch(RuntimeException ex)
        {
            sendNew(guildId, channel, embed, onPlaced);
        }
    }

    private void sendNew(long guildId, TextChannel channel, MessageEmbed embed, LongConsumer onPlaced)
    {
        try
        {
            channel.sendMessageEmbeds(embed).queue(m ->
            {
                anchors.put(guildId, new Anchor(channel.getIdLong(), m.getIdLong()));
                onPlaced.accept(m.getIdLong());
            }, t -> {});
        }
        catch(RuntimeException ignored)
        {
        }
    }

    private int currentIndexFor(Guild guild, LrcLyrics lrc)
    {
        Object handler = guild.getAudioManager().getSendingHandler();
        if(handler instanceof AudioHandler)
        {
            AudioTrack track = ((AudioHandler) handler).getPlayer().getPlayingTrack();
            if(track != null)
                return KaraokeSession.lineIndexFor(lrc, track.getPosition());
        }
        return -1;
    }

    private void stopSessionFor(long guildId)
    {
        KaraokeSession session = sessions.remove(guildId);
        if(session != null)
            session.stop();
    }

    /**
     * Fits plain lyrics into a single embed description, trimming on a line boundary and
     * appending an ellipsis when they are too long.
     */
    static String plainDescription(String lyrics, int maxLen)
    {
        String t = lyrics == null ? "" : lyrics.strip();
        if(t.length() <= maxLen)
            return t;
        int cut = maxLen - 1; // room for the ellipsis
        int newline = t.lastIndexOf('\n', cut);
        if(newline >= maxLen / 2)
            cut = newline;
        return t.substring(0, cut).stripTrailing() + "…";
    }

    private static String blankToNull(String s)
    {
        return s == null || s.isBlank() ? null : s;
    }

    private static final class Anchor
    {
        private final long channelId;
        private final long messageId;

        private Anchor(long channelId, long messageId)
        {
            this.channelId = channelId;
            this.messageId = messageId;
        }
    }
}
