/*
 * Copyright 2026 Jellyo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.recovery;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.recovery.QueueSnapshotStore.Entry;
import com.jagrosh.jmusicbot.recovery.QueueSnapshotStore.Info;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps each guild's playing track + queue persisted so they survive a crash,
 * restart or everyone leaving voice, and restores them on demand.
 *
 * <p>Snapshots are taken on a periodic sweep and on graceful shutdown. When a
 * user tries to play something while a saved queue exists and nothing is
 * currently playing, {@link #promptIfRestorePending} asks them to choose between
 * restoring and starting fresh (running the play command again starts fresh).
 */
public class CrashRecoveryService
{
    private static final Logger LOG = LoggerFactory.getLogger(CrashRecoveryService.class);
    private static final long SAVE_INTERVAL_SECONDS = 20;
    private static final int SAMPLE_TITLE_LIMIT = 60;

    private final Bot bot;
    private final QueueSnapshotStore store;
    private final Set<Long> offered = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;

    public CrashRecoveryService(Bot bot, QueueSnapshotStore store)
    {
        this.bot = bot;
        this.store = store;
    }

    public boolean isEnabled()
    {
        return store != null;
    }

    public QueueSnapshotStore getStore()
    {
        return store;
    }

    public void init()
    {
        if(store == null)
            return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread thread = new Thread(r, "queue-snapshot-saver");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::snapshotAll, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.info("Crash recovery snapshots enabled ({}s interval)", SAVE_INTERVAL_SECONDS);
    }

    private void snapshotAll()
    {
        JDA jda = bot.getJDA();
        if(jda == null || store == null)
            return;
        for(Guild guild : jda.getGuilds())
        {
            try
            {
                snapshotGuild(guild);
            }
            catch(Exception ex)
            {
                LOG.debug("Failed to snapshot queue for guild {}", guild.getId(), ex);
            }
        }
    }

    /** Persists the guild's current playback state; leaves any prior snapshot intact when idle. */
    public boolean snapshotGuild(Guild guild)
    {
        if(store == null || guild == null)
            return false;
        if(!(guild.getAudioManager().getSendingHandler() instanceof AudioHandler))
            return false;
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        if(handler.isInGuessMusicMode())
            return false;
        AudioTrack current = handler.getPlayer().getPlayingTrack();
        // Defensive copy: the queue's backing list is mutated by the audio thread.
        List<QueuedTrack> queued = new java.util.ArrayList<>(handler.getQueue().getList());
        if(current == null && queued.isEmpty())
            return false; // idle — keep the last good snapshot for restoring

        List<Entry> entries = new java.util.ArrayList<>();
        if(current != null)
            entries.add(entryOf(current, Math.max(0, current.getPosition())));
        if(queued != null)
            for(QueuedTrack qt : queued)
                if(qt != null && qt.getTrack() != null)
                    entries.add(entryOf(qt.getTrack(), 0));
        store.save(guild.getIdLong(), entries);
        offered.remove(guild.getIdLong()); // active again — re-arm the restore prompt for a future crash
        return true;
    }

    /** Synchronously snapshot every guild — called during graceful shutdown. */
    public void saveAllSnapshots()
    {
        JDA jda = bot.getJDA();
        if(jda == null || store == null)
            return;
        for(Guild guild : jda.getGuilds())
        {
            try
            {
                snapshotGuild(guild);
            }
            catch(Exception ex)
            {
                LOG.debug("Failed to snapshot queue on shutdown for guild {}", guild.getId(), ex);
            }
        }
    }

    private static Entry entryOf(AudioTrack track, long positionMs)
    {
        Entry e = new Entry();
        if(track.getInfo() != null)
        {
            e.url = blankToNull(track.getInfo().uri);
            e.title = blankToNull(track.getInfo().title);
            e.author = blankToNull(track.getInfo().author);
        }
        RequestMetadata md = track.getUserData(RequestMetadata.class);
        if(md != null)
        {
            if(md.requestInfo != null)
                e.query = blankToNull(md.requestInfo.query);
            e.requesterId = md.getOwner();
            if(md.user != null)
            {
                e.requesterName = md.user.username;
                e.requesterAvatar = md.user.avatar;
            }
        }
        e.positionMs = Math.max(0, positionMs);
        return e;
    }

    /**
     * @return a prompt to show (and abort the play command) if a saved queue is
     *         pending and nothing is playing; null to proceed normally. The
     *         second consecutive play attempt discards the snapshot and proceeds.
     */
    public String promptIfRestorePending(Guild guild)
    {
        if(store == null || guild == null)
            return null;
        long guildId = guild.getIdLong();
        AudioHandler handler = guild.getAudioManager().getSendingHandler() instanceof AudioHandler
                ? (AudioHandler) guild.getAudioManager().getSendingHandler() : null;
        if(handler != null && handler.isInGuessMusicMode())
            return null;
        boolean active = handler != null
                && (handler.getPlayer().getPlayingTrack() != null || !handler.getQueue().isEmpty());
        if(active)
            return null;
        Optional<Info> info = store.peek(guildId);
        if(!info.isPresent())
            return null;
        if(offered.contains(guildId))
        {
            // Already prompted; let the request proceed. The saved queue is left
            // intact on purpose — a request that fails validation (e.g. the user
            // is not in voice) must not silently destroy the snapshot. It is
            // naturally replaced once new playback is snapshotted, and
            // snapshotGuild() clears 'offered' when the guild is active again.
            return null;
        }
        offered.add(guildId);
        Info i = info.get();
        String sample = i.getSampleTitle() == null || i.getSampleTitle().isBlank()
                ? "" : " (e.g. “" + truncate(i.getSampleTitle()) + "”)";
        return "♻️ You have a saved queue from before — **" + i.getCount() + "** track"
                + (i.getCount() == 1 ? "" : "s") + sample + ".\n"
                + "Use `/restore` to bring it back, or run your request again to start fresh.";
    }

    /**
     * Restores the saved queue into the guild's handler (which must already be
     * connected). Tracks are re-loaded by URL/query in order. Returns the number
     * of tracks queued for restoration, 0 if there is nothing to restore, or -1
     * if playback is already active (restoring would duplicate the live queue).
     */
    public synchronized int restore(Guild guild)
    {
        if(store == null || guild == null)
            return 0;
        // Don't append a stale snapshot on top of a live queue (the periodic sweep
        // may already have overwritten the snapshot with the current queue).
        AudioHandler existing = guild.getAudioManager().getSendingHandler() instanceof AudioHandler
                ? (AudioHandler) guild.getAudioManager().getSendingHandler() : null;
        if(existing != null && (existing.getPlayer().getPlayingTrack() != null || !existing.getQueue().isEmpty()))
            return -1;
        List<Entry> entries = store.load(guild.getIdLong());
        if(entries.isEmpty())
            return 0;
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        String orderKey = "restore-" + guild.getIdLong();
        int queued = 0;
        for(Entry entry : entries)
        {
            String identifier = entry.loadIdentifier();
            if(identifier == null || identifier.isBlank())
                continue;
            bot.getPlayerManager().loadItemOrdered(orderKey, identifier, new RestoreHandler(handler, entry));
            queued++;
        }
        store.delete(guild.getIdLong());
        offered.remove(guild.getIdLong());
        return queued;
    }

    public void shutdown()
    {
        if(scheduler != null)
            scheduler.shutdownNow();
    }

    private static String blankToNull(String s)
    {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s)
    {
        return s.length() <= SAMPLE_TITLE_LIMIT ? s : s.substring(0, SAMPLE_TITLE_LIMIT - 1) + "…";
    }

    /** Re-queues one restored track, preserving requester and resume position. */
    private static final class RestoreHandler implements AudioLoadResultHandler
    {
        private final AudioHandler handler;
        private final Entry entry;

        private RestoreHandler(AudioHandler handler, Entry entry)
        {
            this.handler = handler;
            this.entry = entry;
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            add(track);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            if(playlist.getSelectedTrack() != null)
                add(playlist.getSelectedTrack());
            else if(!playlist.getTracks().isEmpty())
                add(playlist.getTracks().get(0));
        }

        @Override
        public void noMatches()
        {
            LOG.debug("No matches restoring track: {}", entry.loadIdentifier());
        }

        @Override
        public void loadFailed(FriendlyException exception)
        {
            LOG.debug("Failed to restore track {}: {}", entry.loadIdentifier(), exception.getMessage());
        }

        private void add(AudioTrack track)
        {
            handler.addTrack(new QueuedTrack(track, RequestMetadata.restored(entry.requesterId, entry.requesterName,
                    entry.requesterAvatar, entry.query, entry.url, entry.positionMs)));
        }
    }
}
