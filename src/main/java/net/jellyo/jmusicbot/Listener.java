/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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

import com.jagrosh.jmusicbot.utils.DependencyUpdateChecker;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class Listener extends ListenerAdapter
{
    private final static Logger LOG = LoggerFactory.getLogger(Listener.class);

    private final Bot bot;
    
    public Listener(Bot bot)
    {
        this.bot = bot;
    }
    
    @Override
    public void onReady(ReadyEvent event)
    {
        LOG.info("JDA ready as {} ({}); guilds={}",
                event.getJDA().getSelfUser().getName(), event.getJDA().getSelfUser().getId(), event.getJDA().getGuilds().size());
        if(event.getJDA().getGuildCache().isEmpty())
        {
            LOG.warn("This bot is not on any guilds! Use the following link to add the bot to your guilds!");
            LOG.warn(event.getJDA().getInviteUrl(JMusicBot.RECOMMENDED_PERMS));
        }
        credit(event.getJDA());
        event.getJDA().getGuilds().forEach((guild) -> 
        {
            try
            {
                String defpl = bot.getSettingsManager().getSettings(guild).getDefaultPlaylist();
                VoiceChannel vc = bot.getSettingsManager().getSettings(guild).getVoiceChannel(guild);
                if(defpl!=null && vc!=null && bot.getPlayerManager().setUpHandler(guild).playFromDefault())
                {
                    LOG.info("Auto-joining configured voice channel {} ({}) in guild {} ({}) for default playlist '{}'",
                            vc.getName(), vc.getId(), guild.getName(), guild.getId(), defpl);
                    guild.getAudioManager().openAudioConnection(vc);
                }
                else
                {
                    LOG.debug("No startup voice auto-join for guild {} ({}); defaultPlaylist={}; voiceChannel={}",
                            guild.getName(), guild.getId(), defpl, vc == null ? "none" : vc.getId());
                }
            }
            catch(Exception ex)
            {
                LOG.warn("Failed startup default-playlist voice join for guild {} ({})",
                        guild.getName(), guild.getId(), ex);
            }
        });
        if(bot.getConfig().useUpdateAlerts())
        {
            LOG.debug("Update alerts enabled; scheduling update check");
            bot.getThreadpool().scheduleWithFixedDelay(() -> 
            {
                try
                {
                    String currentVersion = OtherUtil.getCurrentVersion();
                    String latestVersion = OtherUtil.getLatestVersion();

                    if (latestVersion != null && OtherUtil.compareVersions(latestVersion, currentVersion) > 0)
                    {
                        String msg = String.format(OtherUtil.NEW_VERSION_AVAILABLE, currentVersion, latestVersion);
                        LOG.warn("{}", msg);
                        try
                        {
                            User owner = bot.getJDA().retrieveUserById(bot.getConfig().getOwnerId()).complete();
                            owner.openPrivateChannel().queue(pc -> pc.sendMessage(msg).queue());
                        }
                        catch(Exception ex)
                        {
                            LOG.warn("Failed to send update alert DM to owner", ex);
                        }
                    }

                    List<DependencyUpdateChecker.DependencyUpdate> dependencyUpdates = DependencyUpdateChecker.checkForUpdates();
                    if(!dependencyUpdates.isEmpty())
                    {
                        LOG.warn("{}", DependencyUpdateChecker.formatUpdates(dependencyUpdates));
                    }
                }
                catch(Exception ex)
                {
                    LOG.warn("Failed to check for updates", ex);
                }
            }, 0, 24, TimeUnit.HOURS);
        }
        else
        {
            LOG.debug("Update alerts disabled");
        }
    }
    
    @Override
    public void onMessageDelete(MessageDeleteEvent event)
    {
        bot.getNowplayingHandler().onMessageDelete(event.getGuild(), event.getMessageIdLong());
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event)
    {
        bot.getAloneInVoiceHandler().onVoiceUpdate(event);
    }

    @Override
    public void onShutdown(ShutdownEvent event)
    {
        LOG.warn("JDA shutdown event received with close code {}", event.getCloseCode());
        bot.shutdown();
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) 
    {
        LOG.info("Joined guild {} ({})", event.getGuild().getName(), event.getGuild().getId());
        credit(event.getJDA());
    }
    
    // make sure people aren't adding clones to dbots
    private void credit(JDA jda)
    {
        Guild dbots = jda.getGuildById(110373943822540800L);
        if(dbots==null)
            return;
        if(bot.getConfig().getDBots())
            return;
        jda.getTextChannelById(119222314964353025L)
                .sendMessage("This account is running JMusicBot. Please do not list bot clones on this server, <@"+bot.getConfig().getOwnerId()+">.").complete();
        dbots.leave().queue();
    }
}
