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
package com.jagrosh.jmusicbot.commands;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.settings.Settings;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.exceptions.PermissionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared command gates for slash handlers that delegate to prefix command logic.
 */
public final class CommandChecks
{
    private final static Logger LOG = LoggerFactory.getLogger(CommandChecks.class);

    private CommandChecks()
    {
    }

    public static boolean checkMusicCommand(Bot bot, CommandContext event, boolean bePlaying, boolean beListening)
    {
        LOG.debug("Shared music command invoked; slash={}; guild={} ({}); user={} ({})",
                event.isSlashCommand(),
                event.getGuild().getName(), event.getGuild().getId(),
                event.getAuthor().getName(), event.getAuthor().getId());
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        TextChannel textChannel = settings.getTextChannel(event.getGuild());
        if(textChannel != null && !textChannel.equals(event.getChannel()))
        {
            LOG.debug("Rejected shared music command in guild {} ({}): wrong text channel {} (expected {})",
                    event.getGuild().getName(), event.getGuild().getId(), event.getChannel().getId(), textChannel.getId());
            event.replyErrorEphemeral("You can only use that command in " + textChannel.getAsMention() + "!");
            return false;
        }

        AudioHandler handler = bot.getPlayerManager().setUpHandler(event.getGuild());
        if(bePlaying && !handler.isMusicPlaying(event.getJDA()))
        {
            LOG.debug("Rejected shared music command in guild {} ({}): no music playing",
                    event.getGuild().getName(), event.getGuild().getId());
            event.replyErrorEphemeral("There must be music playing to use that!");
            return false;
        }

        if(beListening)
        {
            AudioChannel current = event.getSelfMember().getVoiceState().getChannel();
            if(current == null)
                current = settings.getVoiceChannel(event.getGuild());

            GuildVoiceState userState = event.getMember().getVoiceState();
            if(userState == null || !userState.inAudioChannel() || userState.isDeafened()
                    || (current != null && !userState.getChannel().equals(current)))
            {
                LOG.debug("Rejected shared music command in guild {} ({}): user voice mismatch; requiredChannel={}, userChannel={}, deafened={}",
                        event.getGuild().getName(), event.getGuild().getId(),
                        current == null ? "any" : current.getId(),
                        userState != null && userState.inAudioChannel() ? userState.getChannel().getId() : "none",
                        userState != null && userState.isDeafened());
                event.replyErrorEphemeral("You must be listening in " + (current == null ? "a voice channel" : current.getAsMention()) + " to use that!");
                return false;
            }

            VoiceChannel afkChannel = userState.getGuild().getAfkChannel();
            if(afkChannel != null && afkChannel.equals(userState.getChannel()))
            {
                LOG.debug("Rejected shared music command in guild {} ({}): user is in AFK channel {}",
                        event.getGuild().getName(), event.getGuild().getId(), afkChannel.getId());
                event.replyErrorEphemeral("You cannot use that command in an AFK channel!");
                return false;
            }

            if(!event.getSelfMember().getVoiceState().inAudioChannel())
            {
                try
                {
                    LOG.info("Opening audio connection for shared command in guild {} ({}) to channel {} ({})",
                            event.getGuild().getName(), event.getGuild().getId(),
                            userState.getChannel().getName(), userState.getChannel().getId());
                    event.getGuild().getAudioManager().openAudioConnection(userState.getChannel());
                }
                catch(PermissionException ex)
                {
                    LOG.warn("Failed to open audio connection for shared command in guild {} ({}) to channel {} ({})",
                            event.getGuild().getName(), event.getGuild().getId(),
                            userState.getChannel().getName(), userState.getChannel().getId(), ex);
                    event.replyErrorEphemeral("I am unable to connect to " + userState.getChannel().getAsMention() + "!");
                    return false;
                }
            }
        }

        return true;
    }

    public static boolean checkDJPermission(CommandContext event, Bot bot)
    {
        if(event.getAuthor().getId().equals(String.valueOf(bot.getConfig().getOwnerId())))
            return true;
        if(event.getGuild() == null)
            return true;
        if(event.getMember() == null)
            return false;
        if(event.getMember().hasPermission(Permission.MANAGE_SERVER))
            return true;
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        Role dj = settings.getRole(event.getGuild());
        return dj != null && (event.getMember().getRoles().contains(dj) || dj.getIdLong() == event.getGuild().getIdLong());
    }

    public static boolean checkAdminPermission(CommandContext event, Bot bot)
    {
        if(event.getAuthor().getId().equals(String.valueOf(bot.getConfig().getOwnerId())))
            return true;
        if(event.getGuild() == null)
            return true;
        return event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER);
    }
}
