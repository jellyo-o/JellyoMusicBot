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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * user plays something while a saved queue exists and nothing is currently
 * playing, {@link #captureRestoreOffer} moves that saved queue into a durable
 * pending slot and the requested song plays as normal; the user can then bring
 * the saved tracks back into the live queue with {@code /restore} or the Restore
 * button via {@link #restore}.
 */
public class CrashRecoveryService
{
    private static final Logger LOG = LoggerFactory.getLogger(CrashRecoveryService.class);
    private static final long SAVE_INTERVAL_SECONDS = 20;
    private static final int SAMPLE_TITLE_LIMIT = 60;
    /** A restore offer the user never acts on expires this many seconds after it was made. */
    public static final long PENDING_TTL_SECONDS = 300;

    private final Bot bot;
    private final QueueSnapshotStore store;
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
        try
        {
            int expired = store.deleteExpiredPending(Instant.now().getEpochSecond() - PENDING_TTL_SECONDS);
            if(expired > 0)
                LOG.debug("Expired {} stale pending restore(s)", expired);
        }
        catch(Exception ex)
        {
            LOG.debug("Failed to expire stale pending restores", ex);
        }
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
     * If a saved queue exists and nothing is currently playing, moves it into the
     * durable pending-restore slot so the requested song can play without losing
     * it, and returns an offer describing what can be brought back. Returns
     * {@code null} when there is nothing to offer (no saved queue, already
     * playing, recovery disabled, or a guess game is running).
     *
     * <p>The live snapshot row is deleted as the offer is captured, so a later
     * play request will not re-offer the same queue; the rolling sweep recreates
     * the snapshot from the newly-playing track.
     */
    public synchronized RestoreOffer captureRestoreOffer(Guild guild)
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
        // Atomically freeze the saved queue into the pending slot so the 20s autosave
        // (which will soon snapshot the song that is about to play) cannot overwrite it.
        List<Entry> entries = store.moveSnapshotToPending(guildId);
        if(entries.isEmpty())
            return null;
        return new RestoreOffer(entries.size(), entries.get(0).title);
    }

    /** Drops a pending restore without bringing it back (the "Dismiss" action). */
    public synchronized void discardPending(Guild guild)
    {
        if(store != null && guild != null)
            store.deletePending(guild.getIdLong());
    }

    /**
     * Drops the rolling crash-recovery snapshot for a guild whose queue has played
     * out naturally, so a fully-consumed queue is no longer treated as a restorable
     * "saved queue from before". Only the live snapshot is removed; any pending
     * restore offer the user has not yet acted on is left intact.
     */
    public synchronized void clearSnapshot(long guildId)
    {
        if(store != null)
            store.delete(guildId);
    }

    /**
     * Brings a saved queue back into the guild's handler (which must already be
     * connected). A pending restore takes priority and is <em>appended</em> to the
     * live queue (the user explicitly asked to merge it back in); otherwise the
     * rolling crash-recovery snapshot is used, but only when nothing is playing so
     * it cannot duplicate the live queue. Tracks are re-loaded by URL/query in
     * order.
     *
     * @return the number of tracks queued for restoration, 0 if there is nothing
     *         to restore, or -1 if only a stale snapshot exists and playback is
     *         already active.
     */
    public synchronized int restore(Guild guild)
    {
        if(store == null || guild == null)
            return 0;
        long guildId = guild.getIdLong();

        // A non-expired pending restore is an explicit append — bring it back on top of whatever is playing.
        if(pendingState(guildId) == PendingState.FRESH)
        {
            List<Entry> pending = store.loadPending(guildId);
            if(!pending.isEmpty())
            {
                int queued = enqueueAll(guild, pending);
                store.deletePending(guildId);
                return queued;
            }
        }

        // Cold restore from the rolling snapshot: refuse if a live queue exists, since the
        // sweep may already have overwritten the snapshot with the current queue (duplication).
        AudioHandler existing = guild.getAudioManager().getSendingHandler() instanceof AudioHandler
                ? (AudioHandler) guild.getAudioManager().getSendingHandler() : null;
        if(existing != null && (existing.getPlayer().getPlayingTrack() != null || !existing.getQueue().isEmpty()))
            return -1;
        List<Entry> entries = store.load(guildId);
        if(entries.isEmpty())
            return 0;
        int queued = enqueueAll(guild, entries);
        store.delete(guildId);
        return queued;
    }

    /**
     * Restores a non-expired pending queue and nothing else (the Restore button
     * path). Unlike {@link #restore}, it never falls back to the cold crash-recovery
     * snapshot, so pressing a stale button cannot bring back an unrelated queue.
     *
     * @return tracks queued for restoration (&gt;0), or 0 if the offer was missing
     *         or had already expired.
     */
    public synchronized int restorePending(Guild guild)
    {
        if(store == null || guild == null)
            return 0;
        long guildId = guild.getIdLong();
        if(pendingState(guildId) != PendingState.FRESH)
            return 0;
        List<Entry> pending = store.loadPending(guildId);
        if(pending.isEmpty())
            return 0;
        int queued = enqueueAll(guild, pending);
        store.deletePending(guildId);
        return queued;
    }

    /** @return the state of this guild's restore offer, expiring (removing) it if stale. */
    public synchronized PendingState pendingRestoreState(Guild guild)
    {
        if(store == null || guild == null)
            return PendingState.NONE;
        return pendingState(guild.getIdLong());
    }

    /** Peeks the pending restore, removing it when expired. Caller must hold this monitor. */
    private PendingState pendingState(long guildId)
    {
        Optional<Info> info = store.peekPending(guildId);
        if(!info.isPresent())
            return PendingState.NONE;
        if(isExpired(info.get().getSavedAt()))
        {
            store.deletePending(guildId);
            return PendingState.EXPIRED;
        }
        return PendingState.FRESH;
    }

    private static boolean isExpired(long savedAtEpochSeconds)
    {
        // Inclusive boundary: an offer is expired once the full TTL has elapsed.
        return Instant.now().getEpochSecond() - savedAtEpochSeconds >= PENDING_TTL_SECONDS;
    }

    /** Whether a guild's offered restore is still valid, already gone, or never existed. */
    public enum PendingState
    {
        FRESH, EXPIRED, NONE
    }

    /** Re-loads each saved entry by URL/query, in order, into the guild's handler. */
    private int enqueueAll(Guild guild, List<Entry> entries)
    {
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

    /** A saved queue the user has been offered to bring back into the live queue. */
    public static final class RestoreOffer
    {
        private final int count;
        private final String sampleTitle;

        RestoreOffer(int count, String sampleTitle)
        {
            this.count = count;
            this.sampleTitle = sampleTitle;
        }

        public int getCount()
        {
            return count;
        }

        public String getSampleTitle()
        {
            return sampleTitle;
        }

        /** Human-readable summary line shown above the Restore button. */
        public String describe()
        {
            String sample = sampleTitle == null || sampleTitle.isBlank()
                    ? "" : " (e.g. “" + truncate(sampleTitle) + "”)";
            return "♻️ You have a saved queue from before — **" + count + "** track"
                    + (count == 1 ? "" : "s") + sample + ".";
        }
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
