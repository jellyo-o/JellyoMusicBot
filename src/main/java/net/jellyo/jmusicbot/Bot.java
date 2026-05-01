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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.jagrosh.jmusicbot.autoplay.AutoplayService;
import com.jagrosh.jmusicbot.audio.AloneInVoiceHandler;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.NowplayingHandler;
import com.jagrosh.jmusicbot.audio.PlayerManager;
import com.jagrosh.jmusicbot.dashboard.DashboardServer;
import com.jagrosh.jmusicbot.dashboard.DashboardStatsService;
import com.jagrosh.jmusicbot.gui.GUI;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService;
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
    private final BotConfig config;
    private final SettingsManager settings;
    private final PlayerManager players;
    private final PlaylistLoader playlists;
    private final UserPlaylistService userPlaylists;
    private final NowplayingHandler nowplaying;
    private final AloneInVoiceHandler aloneInVoiceHandler;
    private final AutoplayService autoplayService;
    private final DashboardStatsService dashboardStats;
    private final DashboardServer dashboardServer;
    
    private boolean shuttingDown = false;
    private JDA jda;
    private GUI gui;
    
    
    public Bot(EventWaiter waiter, BotConfig config, SettingsManager settings)
    {
        LOG.info("Initializing bot services");
        this.waiter = waiter;
        this.config = config;
        this.settings = settings;
        this.playlists = new PlaylistLoader(config);
        this.userPlaylists = new UserPlaylistService(Paths.get("playlists.db"));
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
        DashboardStatsService stats = null;
        DashboardServer dashboard = null;
        if(config.isDashboardEnabled())
        {
            stats = new DashboardStatsService(OtherUtil.getPath(config.getDashboardDatabase()));
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
        
        //Update config.txt before init
        // updateConfig();
        
        this.players = new PlayerManager(this, config);
        this.players.init();
        this.autoplayService = new AutoplayService(this);
        this.nowplaying = new NowplayingHandler(this);
        this.nowplaying.init();
        this.aloneInVoiceHandler = new AloneInVoiceHandler(this);
        this.aloneInVoiceHandler.init();
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

    public DashboardStatsService getDashboardStats()
    {
        return dashboardStats;
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
        threadpool.shutdownNow();
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
