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
package com.jagrosh.jmusicbot.economy;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically samples every voice channel the bot is playing in and credits
 * listening time (and the XP / coins it earns) to every non-bot, non-deafened
 * member present. This is what powers "minutes listened" stats and time-based
 * achievements like Night Owl for passive listeners, not just requesters.
 *
 * <p>Guess-the-song snippets and paused playback are skipped so they never
 * inflate listening stats.
 */
public class ListeningRewardService
{
    private static final Logger LOG = LoggerFactory.getLogger(ListeningRewardService.class);
    static final long INTERVAL_SECONDS = 60;

    private final Bot bot;
    private ScheduledExecutorService scheduler;

    public ListeningRewardService(Bot bot)
    {
        this.bot = bot;
    }

    public void init()
    {
        EconomyService economy = bot.getEconomyService();
        if(economy == null || !economy.isEnabled())
        {
            LOG.debug("Listening rewards inactive (economy disabled)");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread thread = new Thread(r, "listening-reward-sampler");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::sweep, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.info("Listening reward sampler started ({}s interval)", INTERVAL_SECONDS);
    }

    private void sweep()
    {
        try
        {
            JDA jda = bot.getJDA();
            EconomyService economy = bot.getEconomyService();
            if(jda == null || economy == null || !economy.isEnabled())
                return;
            long deltaMs = INTERVAL_SECONDS * 1000L;
            for(Guild guild : jda.getGuilds())
                creditGuild(guild, economy, deltaMs);
        }
        catch(Exception ex)
        {
            LOG.warn("Listening reward sweep failed", ex);
        }
    }

    private void creditGuild(Guild guild, EconomyService economy, long deltaMs)
    {
        if(guild.getAudioManager().getConnectedChannel() == null)
            return;
        if(!(guild.getAudioManager().getSendingHandler() instanceof AudioHandler))
            return;
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        // A normal guess game suppresses listening XP entirely. A HOSTED game is the exception: the host
        // wants players rewarded only for listening (never for guessing), so everyone in voice still earns
        // the normal listening rate while a hosted game runs.
        boolean hosted = bot.getGuessMusicService().isHostedListeningActive(guild);
        if(handler.isInGuessMusicMode() && !hosted)
            return;
        AudioPlayer player = handler.getPlayer();
        if(!hosted)
        {
            if(player == null || player.isPaused())
                return;
            AudioTrack track = player.getPlayingTrack();
            if(track == null)
                return;
            RequestMetadata metadata = track.getUserData(RequestMetadata.class);
            if(metadata != null && metadata.isGuessGame())
                return;
        }

        guild.getAudioManager().getConnectedChannel().getMembers().forEach(member ->
        {
            if(member.getUser().isBot())
                return;
            if(member.getVoiceState() != null && member.getVoiceState().isDeafened())
                return;
            // In a hosted game everyone listening earns XP; otherwise only members who queued a song this
            // session do.
            if(!hosted && !handler.isSessionContributor(member.getIdLong()))
                return;
            economy.creditListening(member.getIdLong(), deltaMs, displayName(member),
                    member.getEffectiveAvatarUrl());
        });
    }

    private static String displayName(Member member)
    {
        String name = member.getEffectiveName();
        return name == null || name.isBlank() ? member.getUser().getName() : name;
    }

    public void shutdown()
    {
        if(scheduler != null)
            scheduler.shutdownNow();
    }
}
