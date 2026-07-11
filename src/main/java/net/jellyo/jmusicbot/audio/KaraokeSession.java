package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.lyrics.LrcLyrics;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.awt.Color;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A live karaoke view for one song in one guild. On a fixed tick it reads the current
 * playback position, works out which line should be showing, and edits a single message
 * so the lyrics advance line-by-line in place. It stops itself when the song ends, is
 * replaced, or the message goes away.
 *
 * <p>The tick runs on the shared single-thread scheduler ({@link Bot#getThreadpool()}):
 * it only reads the position, builds a small embed, and fires an async edit, so it never
 * blocks that thread. Line rendering is delegated to the pure {@link KaraokeRenderer}.
 */
class KaraokeSession
{
    private static final int WINDOW_BEFORE = 1;
    private static final int WINDOW_AFTER = 2;
    private static final long TICK_MILLIS = Math.max(300L, Long.getLong("jmusicbot.karaoke.tickMillis", 1000L));
    private static final long MIN_EDIT_MILLIS = Math.max(1000L, Long.getLong("jmusicbot.karaoke.minEditMillis", 1500L));

    private final Bot bot;
    private final long guildId;
    private final long channelId;
    private final long messageId;
    private final String trackIdentifier;
    private final LrcLyrics lrc;
    private final String titleLine;
    private final String sourceUrl;
    private final Color color;

    private volatile boolean stopped = false;
    private volatile ScheduledFuture<?> future;
    private int lastRenderedIndex = Integer.MIN_VALUE;
    private long lastEditAt = 0L;

    KaraokeSession(Bot bot, long guildId, long channelId, long messageId, String trackIdentifier,
                   LrcLyrics lrc, String titleLine, String sourceUrl, Color color)
    {
        this.bot = bot;
        this.guildId = guildId;
        this.channelId = channelId;
        this.messageId = messageId;
        this.trackIdentifier = trackIdentifier;
        this.lrc = lrc;
        this.titleLine = titleLine;
        this.sourceUrl = sourceUrl;
        this.color = color;
    }

    /** Builds the karaoke embed for a window centered on {@code currentIndex}. */
    static MessageEmbed buildEmbed(LrcLyrics lrc, int currentIndex, String titleLine, String sourceUrl, Color color)
    {
        String desc = KaraokeRenderer.window(lrc, currentIndex, WINDOW_BEFORE, WINDOW_AFTER);
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle(titleLine == null || titleLine.isBlank() ? "Karaoke" : titleLine,
                        sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl)
                .setDescription(desc)
                .setFooter("Karaoke");
        if(color != null)
            eb.setColor(color);
        return eb.build();
    }

    private MessageEmbed embedFor(int currentIndex)
    {
        return buildEmbed(lrc, currentIndex, titleLine, sourceUrl, color);
    }

    /** Current line index for the live playback position, or -1 if the song has no player yet. */
    int currentIndex()
    {
        AudioTrack track = playingTrack();
        return track == null ? -1 : lrc.lineIndexAt(track.getPosition());
    }

    void start()
    {
        future = bot.getThreadpool().scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    void stop()
    {
        stopped = true;
        ScheduledFuture<?> f = future;
        if(f != null)
            f.cancel(false);
    }

    private void tick()
    {
        if(stopped)
            return;
        try
        {
            AudioTrack track = playingTrack();
            if(track == null)
            {
                stop();
                return;
            }
            // A different song is playing now; the manager starts a fresh session for it.
            if(trackIdentifier != null && !trackIdentifier.equals(identifierOf(track)))
            {
                stop();
                return;
            }

            int idx = lrc.lineIndexAt(track.getPosition());
            if(idx == lastRenderedIndex)
                return;
            long now = System.currentTimeMillis();
            if(lastRenderedIndex != Integer.MIN_VALUE && now - lastEditAt < MIN_EDIT_MILLIS)
                return; // throttle Discord edits; the next tick will apply the pending line

            Guild guild = bot.getJDA() == null ? null : bot.getJDA().getGuildById(guildId);
            TextChannel channel = guild == null ? null : guild.getTextChannelById(channelId);
            if(channel == null)
            {
                stop();
                return;
            }
            channel.editMessageEmbedsById(messageId, embedFor(idx)).queue(m -> {}, t -> stop());
            lastRenderedIndex = idx;
            lastEditAt = now;
        }
        catch(Exception ignored)
        {
        }
    }

    private AudioTrack playingTrack()
    {
        Guild guild = bot.getJDA() == null ? null : bot.getJDA().getGuildById(guildId);
        if(guild == null)
            return null;
        Object handler = guild.getAudioManager().getSendingHandler();
        if(!(handler instanceof AudioHandler))
            return null;
        return ((AudioHandler) handler).getPlayer().getPlayingTrack();
    }

    static String identifierOf(AudioTrack track)
    {
        if(track == null || track.getInfo() == null)
            return null;
        return track.getInfo().identifier;
    }
}
