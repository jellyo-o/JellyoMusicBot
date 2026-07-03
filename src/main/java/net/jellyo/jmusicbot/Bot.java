/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.jagrosh.jmusicbot.achievements.AchievementService;
import com.jagrosh.jmusicbot.autoplay.AutoplayService;
import com.jagrosh.jmusicbot.audio.AloneInVoiceHandler;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.AvoidStore;
import com.jagrosh.jmusicbot.audio.NowplayingHandler;
import com.jagrosh.jmusicbot.audio.PlaybackHistoryStore;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.dashboard.DashboardServer;
import com.jagrosh.jmusicbot.dashboard.DashboardStatsService;
import com.jagrosh.jmusicbot.database.Database;
import com.jagrosh.jmusicbot.database.DatabaseMigrator;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyStore;
import com.jagrosh.jmusicbot.economy.ListeningRewardService;
import com.jagrosh.jmusicbot.gui.GUI;
import com.jagrosh.jmusicbot.guessmusic.GuessMusicService;
import com.jagrosh.jmusicbot.lyrics.LyricsPreloader;
import com.jagrosh.jmusicbot.lyrics.LyricsService;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService;
import com.jagrosh.jmusicbot.recovery.CrashRecoveryService;
import com.jagrosh.jmusicbot.recovery.QueueSnapshotStore;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import java.util.Objects;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/* */
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.regex.*;
import java.text.SimpleDateFormat;
import java.util.Date;
/* */

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class Bot
{
    private final static Logger LOG = LoggerFactory.getLogger(Bot.class);

    private final EventWaiter waiter;
    private final ScheduledExecutorService threadpool;
    private final ExecutorService blockingThreadpool;
    private final BotConfig config;
    private final SettingsManager settings;
    private final PlayerManager players;
    private final PlaylistLoader playlists;
    private final UserPlaylistService userPlaylists;
    private final NowplayingHandler nowplaying;
    private final AloneInVoiceHandler aloneInVoiceHandler;
    private final AutoplayService autoplayService;
    private final GuessMusicService guessMusicService;
    private final PlaybackHistoryStore playbackHistoryStore;
    private final DashboardStatsService dashboardStats;
    private final DashboardServer dashboardServer;
    private final EconomyStore economyStore;
    private final EconomyService economyService;
    private final AchievementService achievementService;
    private final ListeningRewardService listeningRewardService;
    private final AvoidStore avoidStore;
    private final CrashRecoveryService crashRecoveryService;
    private final LyricsService lyricsService;
    private final LyricsPreloader lyricsPreloader;
    private final ExecutorService lyricsPreloadPool;

    private boolean shuttingDown = false;
    private JDA jda;
    private GUI gui;
    
    
    public Bot(EventWaiter waiter, BotConfig config, SettingsManager settings)
    {
        LOG.info("Initializing bot services");
        this.waiter = waiter;
        this.config = config;
        this.settings = settings;

        // All persistent state lives in one unified SQLite database. Merge any legacy
        // split databases (playlists, playback history, guess-music highlights, dashboard
        // telemetry) into it once, idempotently, before any store opens a connection.
        Path databasePath = Database.defaultPath();
        DatabaseMigrator.merge(databasePath, Arrays.asList(
                new DatabaseMigrator.LegacySource("playlists", Paths.get("playlists.db")),
                new DatabaseMigrator.LegacySource("playback-history", Paths.get("playback-history.db")),
                new DatabaseMigrator.LegacySource("guess-music-highlights", Paths.get("guess-music-highlights.db")),
                new DatabaseMigrator.LegacySource("dashboard", OtherUtil.getPath(config.getDashboardDatabase()))));

        this.playlists = new PlaylistLoader(config);
        this.userPlaylists = new UserPlaylistService(databasePath);
        try
        {
            this.userPlaylists.init();
            this.userPlaylists.importLegacyPlaylists(config, config.getOwnerId());
        }
        catch(Exception ex)
        {
            LOG.warn("Failed to initialize user playlist storage", ex);
        }
        this.threadpool = Executors.newSingleThreadScheduledExecutor();
        // Dedicated pool for blocking I/O (e.g. the Spotify public-page fallback fetch) so a slow
        // network call never stalls the single-thread scheduler that drives timers, fades, etc.
        this.blockingThreadpool = Executors.newFixedThreadPool(2, r ->
        {
            Thread thread = new Thread(r, "jmusicbot-blocking-io");
            thread.setDaemon(true);
            return thread;
        });
        PlaybackHistoryStore historyStore = new PlaybackHistoryStore(databasePath);
        try
        {
            historyStore.init();
        }
        catch(Exception ex)
        {
            LOG.warn("Failed to initialize playback history storage", ex);
            historyStore = null;
        }
        this.playbackHistoryStore = historyStore;
        DashboardStatsService stats = null;
        DashboardServer dashboard = null;
        if(config.isDashboardEnabled())
        {
            stats = new DashboardStatsService(databasePath);
            try
            {
                stats.init();
                dashboard = new DashboardServer(this, stats, config.getDashboardBindAddress(), config.getDashboardPort());
                dashboard.start();
            }
            catch(Exception ex)
            {
                LOG.warn("Dashboard is enabled but failed to start; Discord bot startup will continue", ex);
                stats.close();
                stats = null;
                dashboard = null;
            }
        }
        this.dashboardStats = stats;
        this.dashboardServer = dashboard;

        // Global per-user economy (currency, XP, achievements, games) in the unified database.
        EconomyStore economy = new EconomyStore(databasePath);
        EconomyService economyService;
        try
        {
            economy.init();
            economyService = new EconomyService(economy, config.isEconomyEnabled());
        }
        catch(Exception ex)
        {
            LOG.warn("Failed to initialize economy storage; economy features disabled", ex);
            economy = null;
            economyService = new EconomyService(null, false);
        }
        this.economyStore = economy;
        this.economyService = economyService;
        this.achievementService = new AchievementService(this);
        this.economyService.setObserver(this.achievementService);

        // Per-guild persistent avoid list (songs autoplay must never pick).
        AvoidStore avoid = new AvoidStore(databasePath);
        try
        {
            avoid.init();
        }
        catch(Exception ex)
        {
            LOG.warn("Failed to initialize avoid storage; /avoid disabled", ex);
            avoid = null;
        }
        this.avoidStore = avoid;

        // Crash recovery: persist queues so they survive crashes/restarts and can be /restore-d.
        QueueSnapshotStore snapshotStore = new QueueSnapshotStore(databasePath);
        CrashRecoveryService crashRecovery;
        try
        {
            snapshotStore.init();
            crashRecovery = new CrashRecoveryService(this, snapshotStore);
        }
        catch(Exception ex)
        {
            LOG.warn("Failed to initialize crash recovery storage; /restore disabled", ex);
            crashRecovery = new CrashRecoveryService(this, null);
        }
        this.crashRecoveryService = crashRecovery;
        this.crashRecoveryService.init();

        //Update config.txt before init
        // updateConfig();
        
        this.players = new PlayerManager(this, config);
        this.players.init();
        this.autoplayService = new AutoplayService(this);
        this.guessMusicService = new GuessMusicService(this);
        this.nowplaying = new NowplayingHandler(this);
        this.nowplaying.init();
        this.aloneInVoiceHandler = new AloneInVoiceHandler(this);
        this.aloneInVoiceHandler.init();
        this.listeningRewardService = new ListeningRewardService(this);
        this.listeningRewardService.init();

        // Shared lyrics service (one SQLite-backed cache) used by /lyrics, the panel
        // button, preloading, and auto-show. Failure here must not abort startup.
        LyricsService ls = null;
        try
        {
            ls = new LyricsService(Paths.get("lyrics-cache.db"));
        }
        catch(Exception ex)
        {
            LOG.warn("Failed to initialize lyrics service; lyrics features disabled", ex);
        }
        this.lyricsService = ls;
        // Dedicated single-thread pool so speculative preloads never contend with
        // real-time /lyrics, economy writes, or dashboard I/O on the blocking pool.
        this.lyricsPreloadPool = Executors.newSingleThreadExecutor(r ->
        {
            Thread thread = new Thread(r, "jmusicbot-lyrics-preload");
            thread.setDaemon(true);
            return thread;
        });
        final LyricsService lsFinal = ls;
        this.lyricsPreloader = (ls == null) ? null : new LyricsPreloader(lyricsPreloadPool, query ->
        {
            try { lsFinal.preloadPrimary(query); }
            catch(Exception ignored) {}
        }, 256);

        LOG.info("Bot services initialized");
    }
    
    public BotConfig getConfig()
    {
        return config;
    }
    
    public SettingsManager getSettingsManager()
    {
        return settings;
    }
    
    public EventWaiter getWaiter()
    {
        return waiter;
    }
    
    public ScheduledExecutorService getThreadpool()
    {
        return threadpool;
    }

    /** Pool for blocking I/O work that must never run on the single-thread {@link #getThreadpool()} scheduler. */
    public ExecutorService getBlockingThreadpool()
    {
        return blockingThreadpool;
    }

    /** Shared lyrics service (may be {@code null} if initialization failed). */
    public LyricsService getLyricsService()
    {
        return lyricsService;
    }

    /** Preloader that warms lyrics for upcoming songs (may be {@code null} if lyrics are unavailable). */
    public LyricsPreloader getLyricsPreloader()
    {
        return lyricsPreloader;
    }
    
    public PlayerManager getPlayerManager()
    {
        return players;
    }
    
    public PlaylistLoader getPlaylistLoader()
    {
        return playlists;
    }

    public UserPlaylistService getUserPlaylistService()
    {
        return userPlaylists;
    }
    
    public NowplayingHandler getNowplayingHandler()
    {
        return nowplaying;
    }

    public AloneInVoiceHandler getAloneInVoiceHandler()
    {
        return aloneInVoiceHandler;
    }

    public AutoplayService getAutoplayService()
    {
        return autoplayService;
    }

    public GuessMusicService getGuessMusicService()
    {
        return guessMusicService;
    }

    public PlaybackHistoryStore getPlaybackHistoryStore()
    {
        return playbackHistoryStore;
    }

    public DashboardStatsService getDashboardStats()
    {
        return dashboardStats;
    }

    public EconomyStore getEconomyStore()
    {
        return economyStore;
    }

    public EconomyService getEconomyService()
    {
        return economyService;
    }

    public AchievementService getAchievementService()
    {
        return achievementService;
    }

    public AvoidStore getAvoidStore()
    {
        return avoidStore;
    }

    public CrashRecoveryService getCrashRecoveryService()
    {
        return crashRecoveryService;
    }

    public ListeningRewardService getListeningRewardService()
    {
        return listeningRewardService;
    }
    
    public JDA getJDA()
    {
        return jda;
    }
    
    public void closeAudioConnection(long guildId)
    {
        if(jda == null)
        {
            LOG.warn("Requested audio disconnect for guild {} before JDA was initialized", guildId);
            return;
        }

        Guild guild = jda.getGuildById(guildId);
        if(guild == null)
        {
            LOG.warn("Requested audio disconnect for unknown guild {}", guildId);
            return;
        }

        AudioChannel channel = guild.getAudioManager().getConnectedChannel();
        LOG.info("Scheduling audio disconnect for guild {} ({}) from channel {} ({})",
                guild.getName(), guild.getId(), channel == null ? "none" : channel.getName(), channel == null ? "none" : channel.getId());
        nowplaying.collapsePanels(guild, "Bot disconnected. Music panel closed.");
        threadpool.submit(() ->
        {
            try
            {
                guild.getAudioManager().closeAudioConnection();
                LOG.debug("Audio disconnect executed for guild {} ({})", guild.getName(), guild.getId());
            }
            catch(Exception ex)
            {
                LOG.warn("Failed to close audio connection for guild {} ({})", guild.getName(), guild.getId(), ex);
            }
        });
    }
    
    public void resetGame()
    {
        Activity game = config.getGame()==null || config.getGame().getName().equalsIgnoreCase("none") ? null : config.getGame();
        if(!Objects.equals(jda.getPresence().getActivity(), game))
            jda.getPresence().setActivity(game);
    }

    public void shutdown()
    {
        if(shuttingDown)
            return;
        shuttingDown = true;
        LOG.warn("Bot shutdown requested");
        if(crashRecoveryService != null)
            crashRecoveryService.saveAllSnapshots();
        threadpool.shutdownNow();
        blockingThreadpool.shutdownNow();
        lyricsPreloadPool.shutdownNow();
        if(jda.getStatus()!=JDA.Status.SHUTTING_DOWN)
        {
            jda.getGuilds().stream().forEach(g -> 
            {
                AudioChannel channel = g.getAudioManager().getConnectedChannel();
                LOG.info("Closing audio connection during shutdown for guild {} ({}) from channel {} ({})",
                        g.getName(), g.getId(), channel == null ? "none" : channel.getName(), channel == null ? "none" : channel.getId());
                g.getAudioManager().closeAudioConnection();
                AudioHandler ah = (AudioHandler)g.getAudioManager().getSendingHandler();
                if(ah!=null)
                {
                    ah.stopAndClear();
                    ah.getPlayer().destroy();
                }
            });
            jda.shutdown();
        }
        if(gui!=null)
            gui.dispose();
        if(dashboardServer != null)
            dashboardServer.stop();
        if(dashboardStats != null)
            dashboardStats.close();
        if(guessMusicService != null)
            guessMusicService.close();
        if(playbackHistoryStore != null)
            playbackHistoryStore.close();
        if(listeningRewardService != null)
            listeningRewardService.shutdown();
        if(economyStore != null)
            economyStore.close();
        if(avoidStore != null)
            avoidStore.close();
        if(crashRecoveryService != null)
        {
            crashRecoveryService.shutdown();
            if(crashRecoveryService.getStore() != null)
                crashRecoveryService.getStore().close();
        }
        System.exit(0);
    }

    public void setJDA(JDA jda)
    {
        this.jda = jda;
    }
    
    public void setGUI(GUI gui)
    {
        this.gui = gui;
    }
    
    private void updateConfig() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        String currentTime = sdf.format(new Date());

        try {
            Process process = new ProcessBuilder("docker", "run", "quay.io/invidious/youtube-trusted-session-generator")
                    .redirectErrorStream(true)
                    .start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();

            String dockerOutput = output.toString();
            Pattern poTokenPattern = Pattern.compile("po_token:\\s*([^\\s]+)");
            Pattern visitorDataPattern = Pattern.compile("visitor_data:\\s*([^\\s]+)");

            Matcher poTokenMatcher = poTokenPattern.matcher(dockerOutput);
            Matcher visitorDataMatcher = visitorDataPattern.matcher(dockerOutput);

            if (poTokenMatcher.find() && visitorDataMatcher.find()) {
                String poToken = poTokenMatcher.group(1);
                String visitorData = visitorDataMatcher.group(1);

                Path tokensFilePath = Paths.get("tokens.txt");
                String tokenData = "ytpotoken=" + poToken + "\nytvisitordata=" + visitorData;

                Files.writeString(tokensFilePath, tokenData);

                System.out.println("[" + currentTime + "] [INFO] ytpotoken = " + poToken);
                System.out.println("[" + currentTime + "] [INFO] ytvisitordata = " + visitorData);
                System.out.println("[" + currentTime + "] [INFO] tokens.txt successfully updated!");
            } else {
                System.err.println("[" + currentTime + "] [ERROR]: Failed to find po_token or visitor_data in Docker result.");
                System.err.println("[" + currentTime + "] [ERROR]: !!! ENTER MANUALLY TO TOKENS.TXT !!!");
            }
        } catch (Exception e) {
            System.err.println("[" + currentTime + "] [ERROR]: Error while updating tokens.txt " + e.getMessage());
        }
    }
}
