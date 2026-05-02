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
package com.jagrosh.jmusicbot.autoplay;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.playlist.PlaylistTrack;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistException;
import com.jagrosh.jmusicbot.settings.AutoplayMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoplayService
{
    private static final Logger LOG = LoggerFactory.getLogger(AutoplayService.class);
    private static final int MAX_PLAYLIST_ITEM_ATTEMPTS = 6;
    private static final List<String> COVER_INDICATORS = Arrays.asList(
            "cover", "covers", "covered by", "karaoke", "tribute", "instrumental",
            "guitar cover", "piano cover", "drum cover", "acoustic cover", "vocal cover");
    private static final List<String> NON_ORIGINAL_INDICATORS = Arrays.asList(
            "reaction", "review", "nightcore", "sped up", "slowed", "8d audio", "remix", "mashup");
    private static final List<String> OFFICIAL_INDICATORS = Arrays.asList(
            "official music video", "official video", "official audio", "official lyric video", "official lyrics", "official");

    private final Bot bot;
    private final PlayHistoryStore historyStore;
    private final Random random = new Random();

    public AutoplayService(Bot bot)
    {
        this(bot, new PlayHistoryStore());
    }

    public AutoplayService(Bot bot, PlayHistoryStore historyStore)
    {
        this.bot = bot;
        this.historyStore = historyStore;
    }

    public boolean startNext(AudioHandler handler, AudioTrack previousTrack, Runnable fallback)
    {
        Settings settings = bot.getSettingsManager().getSettings(handler.getGuildId());
        AutoplayMode mode = settings.getAutoplayMode();
        if(mode == AutoplayMode.OFF || previousTrack == null)
            return false;

        List<AutoplaySource> sources = sourcesFor(mode, handler, previousTrack);
        if(sources.isEmpty())
            return false;

        LOG.info("Attempting autoplay for guild {}; mode={}; previous={}",
                handler.getGuildId(), mode, describeTrack(previousTrack));
        new Session(handler, previousTrack, fallback, sources).tryNextSource();
        return true;
    }

    public void recordIfEligible(long guildId, AudioTrack track)
    {
        RequestMetadata metadata = track == null ? null : track.getUserData(RequestMetadata.class);
        if(metadata != null && metadata.shouldRecordInHistory())
            historyStore.record(guildId, track);
    }

    PlayHistoryStore getHistoryStore()
    {
        return historyStore;
    }

    private List<AutoplaySource> sourcesFor(AutoplayMode mode, AudioHandler handler, AudioTrack previousTrack)
    {
        switch(mode)
        {
            case SMART:
                return smartSourcesFor(handler, previousTrack);
            case RELATED:
                return Collections.singletonList(AutoplaySource.RELATED);
            case ARTIST:
                return Collections.singletonList(AutoplaySource.ARTIST);
            case PLAYLIST:
                return Collections.singletonList(AutoplaySource.PLAYLIST);
            case SERVER:
                return Collections.singletonList(AutoplaySource.SERVER);
            default:
                return Collections.emptyList();
        }
    }

    private List<AutoplaySource> smartSourcesFor(AudioHandler handler, AudioTrack previousTrack)
    {
        List<AutoplaySource> sources = new ArrayList<>();
        if(handler.getLastPlaylistId() > 0L)
            addSource(sources, AutoplaySource.PLAYLIST);
        if(previousTrack.getInfo() != null && hasText(previousTrack.getInfo().author))
            addSource(sources, AutoplaySource.ARTIST);
        addSource(sources, AutoplaySource.RELATED);
        addSource(sources, AutoplaySource.SERVER);
        return sources;
    }

    private static void addSource(List<AutoplaySource> sources, AutoplaySource source)
    {
        if(!sources.contains(source))
            sources.add(source);
    }

    private enum AutoplaySource
    {
        RELATED,
        ARTIST,
        PLAYLIST,
        SERVER
    }

    private class Session
    {
        private final AudioHandler handler;
        private final AudioTrack previousTrack;
        private final Runnable fallback;
        private final List<AutoplaySource> sources;
        private int sourceIndex = 0;

        private Session(AudioHandler handler, AudioTrack previousTrack, Runnable fallback, List<AutoplaySource> sources)
        {
            this.handler = handler;
            this.previousTrack = previousTrack;
            this.fallback = fallback;
            this.sources = sources;
        }

        private void tryNextSource()
        {
            if(!isIdle())
                return;
            if(!isAutoplayEnabled())
            {
                fallback.run();
                return;
            }

            if(sourceIndex >= sources.size())
            {
                LOG.info("Autoplay found no candidate for guild {}; falling back to default empty-queue behavior",
                        handler.getGuildId());
                fallback.run();
                return;
            }

            AutoplaySource source = sources.get(sourceIndex++);
            switch(source)
            {
                case SERVER:
                    if(!tryServerFavorite())
                        tryNextSource();
                    break;
                case PLAYLIST:
                    if(!tryPlaylist())
                        tryNextSource();
                    break;
                case ARTIST:
                    if(!tryArtist())
                        tryNextSource();
                    break;
                case RELATED:
                    if(!tryRelated())
                        tryNextSource();
                    break;
                default:
                    tryNextSource();
                    break;
            }
        }

        private boolean tryServerFavorite()
        {
            Optional<TrackReference> reference = historyStore.chooseWeighted(handler.getGuildId(), handler.getPlaybackSessionTrackKeys(), random);
            if(!reference.isPresent())
                return false;

            TrackReference track = reference.get();
            String identifier = track.getUri();
            if(identifier == null || identifier.isEmpty())
                identifier = searchQuery(track.getTitle(), track.getAuthor());
            if(identifier == null || identifier.isEmpty())
                return false;

            String query = track.getUri() == null || track.getUri().isEmpty()
                    ? "ytsearch:" + identifier
                    : identifier;
            loadItem(query, AutoplaySource.SERVER, "server favorites", query);
            return true;
        }

        private boolean tryPlaylist()
        {
            long playlistId = handler.getLastPlaylistId();
            String playlistName = handler.getLastPlaylistName();
            if(playlistId <= 0L)
                return false;
            if(playlistName == null || playlistName.isBlank())
                playlistName = Long.toString(playlistId);

            List<PlaylistTrack> items;
            try
            {
                items = new ArrayList<>(bot.getUserPlaylistService().listItems(playlistId));
            }
            catch(PlaylistException ex)
            {
                LOG.debug("Autoplay playlist source could not read playlist {} for guild {}: {}",
                        playlistId, handler.getGuildId(), ex.getMessage());
                return false;
            }
            items.removeIf(item -> !isStoredPlaylistDurationAcceptable(item, bot.getConfig().getAutoplayMaxDurationMillis()));
            items.removeIf(item -> handler.hasPlayedThisSession(item.getQuery(), item.getUrl(), item.getTitle(), item.getAuthor()));
            if(items.isEmpty())
                return false;

            Collections.shuffle(items, random);
            List<PlaylistTrack> attempts = items.stream()
                    .limit(MAX_PLAYLIST_ITEM_ATTEMPTS)
                    .collect(Collectors.toList());
            if(attempts.isEmpty())
                return false;

            loadPlaylistItems(playlistName, attempts, 0);
            return true;
        }

        private void loadPlaylistItems(String playlistName, List<PlaylistTrack> attempts, int index)
        {
            if(!isIdle())
                return;
            if(index >= attempts.size())
            {
                tryNextSource();
                return;
            }

            PlaylistTrack item = attempts.get(index);
            String query = item.getLoadQuery();
            bot.getPlayerManager().loadItemOrdered("autoplay-" + handler.getGuildId(), query,
                    new ResultHandler(AutoplaySource.PLAYLIST, "playlist:" + playlistName, query,
                            () -> loadPlaylistItems(playlistName, attempts, index + 1)));
        }

        private boolean tryArtist()
        {
            if(previousTrack.getInfo() == null)
                return false;
            String author = artistSearchName(previousTrack.getInfo().author);
            if(!hasText(author))
                return false;
            String query = searchQuery("official music video", author);
            if(query == null)
                return false;
            loadItem("ytsearch:" + query, AutoplaySource.ARTIST, "artist", query);
            return true;
        }

        private boolean tryRelated()
        {
            if(previousTrack.getInfo() == null)
                return false;
            String query = searchQuery(previousTrack.getInfo().title + " official", artistSearchName(previousTrack.getInfo().author));
            if(query == null)
                return false;
            loadItem("ytsearch:" + query, AutoplaySource.RELATED, "related", query);
            return true;
        }

        private void loadItem(String identifier, AutoplaySource source, String sourceName, String query)
        {
            bot.getPlayerManager().loadItemOrdered("autoplay-" + handler.getGuildId(), identifier,
                    new ResultHandler(source, sourceName, query, this::tryNextSource));
        }

        private boolean startTrack(AudioTrack track, String query)
        {
            if(!isIdle())
                return true;
            if(!isAutoplayEnabled())
                return false;
            if(bot.getConfig().isTooLong(track))
            {
                LOG.debug("Skipping autoplay candidate above configured maxtime for guild {}; track={}",
                        handler.getGuildId(), describeTrack(track));
                return false;
            }
            if(!isAutoplayDurationAcceptable(track, bot.getConfig().getAutoplayMaxDurationMillis()))
            {
                LOG.debug("Skipping long-form autoplay candidate for guild {}; maxDuration={}; track={}",
                        handler.getGuildId(), bot.getConfig().getAutoplayMaxTime(), describeTrack(track));
                return false;
            }
            if(handler.hasPlayedThisSession(track))
            {
                LOG.debug("Skipping already-played autoplay candidate for guild {}; track={}",
                        handler.getGuildId(), describeTrack(track));
                return false;
            }

            LOG.info("Starting autoplay track for guild {}; query='{}'; track={}",
                    handler.getGuildId(), query, describeTrack(track));
            handler.addTrack(new QueuedTrack(track, RequestMetadata.autoplay(query, track)));
            return true;
        }

        private boolean isAcceptableCandidate(AudioTrack track)
        {
            if(bot.getConfig().isTooLong(track))
            {
                LOG.debug("Skipping autoplay candidate above configured maxtime for guild {}; track={}",
                        handler.getGuildId(), describeTrack(track));
                return false;
            }
            if(!isAutoplayDurationAcceptable(track, bot.getConfig().getAutoplayMaxDurationMillis()))
            {
                LOG.debug("Skipping long-form autoplay candidate for guild {}; maxDuration={}; track={}",
                        handler.getGuildId(), bot.getConfig().getAutoplayMaxTime(), describeTrack(track));
                return false;
            }
            if(handler.hasPlayedThisSession(track))
            {
                LOG.debug("Skipping already-played autoplay candidate for guild {}; track={}",
                        handler.getGuildId(), describeTrack(track));
                return false;
            }
            return true;
        }

        private AudioTrack chooseCandidate(List<AudioTrack> tracks, AutoplaySource source)
        {
            if(tracks.isEmpty())
                return null;

            if(!prefersOriginals(source))
                return tracks.get(random.nextInt(tracks.size()));

            String previousArtist = previousTrack.getInfo() == null ? null : previousTrack.getInfo().author;
            int bestScore = tracks.stream()
                    .mapToInt(track -> originalityScore(track, previousArtist))
                    .max()
                    .orElse(Integer.MIN_VALUE);
            List<AudioTrack> bestTracks = tracks.stream()
                    .filter(track -> originalityScore(track, previousArtist) == bestScore)
                    .collect(Collectors.toList());
            return bestTracks.get(random.nextInt(bestTracks.size()));
        }

        private boolean prefersOriginals(AutoplaySource source)
        {
            return source == AutoplaySource.ARTIST || source == AutoplaySource.RELATED;
        }

        private boolean isIdle()
        {
            return handler.getPlayer().getPlayingTrack() == null && handler.getQueue().isEmpty();
        }

        private boolean isAutoplayEnabled()
        {
            return bot.getSettingsManager().getSettings(handler.getGuildId()).getAutoplayMode() != AutoplayMode.OFF;
        }

        private class ResultHandler implements AudioLoadResultHandler
        {
            private final AutoplaySource source;
            private final String sourceName;
            private final String query;
            private final Runnable failed;

            private ResultHandler(AutoplaySource source, String sourceName, String query, Runnable failed)
            {
                this.source = source;
                this.sourceName = sourceName;
                this.query = query;
                this.failed = failed;
            }

            @Override
            public void trackLoaded(AudioTrack track)
            {
                if(!startTrack(track, query))
                    failed.run();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                List<AudioTrack> tracks = playlist.getTracks().stream()
                        .filter(this::isResultCandidate)
                        .collect(Collectors.toList());
                if(tracks.isEmpty())
                {
                    failed.run();
                    return;
                }

                AudioTrack selected = playlist.getSelectedTrack();
                if(prefersOriginals(source) || selected == null || !isResultCandidate(selected))
                    selected = chooseCandidate(tracks, source);

                if(!startTrack(selected, query))
                    failed.run();
            }

            private boolean isResultCandidate(AudioTrack track)
            {
                return Session.this.isAcceptableCandidate(track);
            }

            @Override
            public void noMatches()
            {
                LOG.debug("Autoplay {} source found no matches for guild {}; query='{}'",
                        sourceName, handler.getGuildId(), query);
                failed.run();
            }

            @Override
            public void loadFailed(FriendlyException throwable)
            {
                LOG.warn("Autoplay {} source failed for guild {}; query='{}'; severity={}; message={}",
                        sourceName, handler.getGuildId(), query, throwable.severity, throwable.getMessage(), throwable);
                failed.run();
            }
        }
    }

    private static String searchQuery(String title, String author)
    {
        StringBuilder builder = new StringBuilder();
        if(author != null && !author.trim().isEmpty())
            builder.append(author.trim());
        if(title != null && !title.trim().isEmpty())
        {
            if(builder.length() > 0)
                builder.append(' ');
            builder.append(title.trim());
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private static String artistSearchName(String author)
    {
        if(author == null)
            return null;

        String clean = author.trim();
        if(clean.isEmpty())
            return null;

        clean = clean.replaceAll("(?i)\\s*-\\s*topic$", "")
                .replaceAll("(?i)\\s+topic$", "")
                .replaceAll("(?i)\\s+official$", "")
                .replaceAll("(?i)vevo$", "")
                .trim();
        return clean.isEmpty() ? author.trim() : clean;
    }

    private static boolean hasText(String value)
    {
        return value != null && !value.trim().isEmpty();
    }

    static boolean isAutoplayDurationAcceptable(AudioTrack track)
    {
        return isAutoplayDurationAcceptable(track, BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS);
    }

    static boolean isAutoplayDurationAcceptable(AudioTrack track, long maxDurationMillis)
    {
        if(track == null || track.getInfo() == null)
            return false;
        if(track.getInfo().isStream)
            return false;
        if(maxDurationMillis <= 0)
            maxDurationMillis = BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS;
        long duration = track.getDuration();
        return duration <= 0 || duration <= maxDurationMillis;
    }

    private static boolean isStoredPlaylistDurationAcceptable(PlaylistTrack item, long maxDurationMillis)
    {
        if(maxDurationMillis <= 0)
            maxDurationMillis = BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS;
        return item.getDuration() <= 0 || item.getDuration() <= maxDurationMillis;
    }

    static boolean isLikelyCover(AudioTrack track)
    {
        return containsAnyPhrase(trackText(track), COVER_INDICATORS);
    }

    static int originalityScore(AudioTrack track, String previousArtist)
    {
        String text = trackText(track);
        int score = 0;

        if(mentionsArtist(text, previousArtist))
            score += 80;
        else if(!artistKey(previousArtist).isEmpty())
            score -= 15;

        if(isLikelyCover(track))
            score -= 100;
        if(containsAnyPhrase(text, OFFICIAL_INDICATORS))
            score += 25;
        if(containsAnyPhrase(text, NON_ORIGINAL_INDICATORS))
            score -= 35;
        if(containsPhrase(text, "live"))
            score -= 8;

        return score;
    }

    private static boolean mentionsArtist(String normalizedTrackText, String artist)
    {
        String artistKey = artistKey(artist);
        if(artistKey.isEmpty())
            return false;
        return normalizedTrackText.replace(" ", "").contains(artistKey);
    }

    private static String artistKey(String artist)
    {
        String key = normalizeForMatching(artist).replace(" ", "");
        if(key.isEmpty())
            return "";

        for(String suffix : Arrays.asList("official", "music", "vevo", "topic"))
            if(key.endsWith(suffix) && key.length() > suffix.length() + 2)
                key = key.substring(0, key.length() - suffix.length());
        if(key.endsWith("ny") && key.length() > 8)
            key = key.substring(0, key.length() - 2);
        return key;
    }

    private static boolean containsAnyPhrase(String normalizedText, List<String> phrases)
    {
        for(String phrase : phrases)
            if(containsPhrase(normalizedText, phrase))
                return true;
        return false;
    }

    private static boolean containsPhrase(String normalizedText, String phrase)
    {
        String normalizedPhrase = normalizeForMatching(phrase);
        return !normalizedPhrase.isEmpty() && (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

    private static String trackText(AudioTrack track)
    {
        if(track == null || track.getInfo() == null)
            return "";
        return normalizeForMatching(track.getInfo().title + " " + track.getInfo().author);
    }

    private static String normalizeForMatching(String value)
    {
        if(value == null)
            return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String describeTrack(AudioTrack track)
    {
        if(track == null)
            return "none";
        String source = track.getSourceManager() == null ? "unknown" : track.getSourceManager().getSourceName();
        return "'" + track.getInfo().title + "' by '" + track.getInfo().author + "'"
                + " [id=" + track.getIdentifier()
                + ", source=" + source
                + ", duration=" + TimeUtil.formatTime(track.getDuration()) + "]";
    }
}
