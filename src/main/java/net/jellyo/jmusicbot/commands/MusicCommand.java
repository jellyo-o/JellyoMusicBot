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
package com.jagrosh.jmusicbot.commands;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.exceptions.PermissionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public abstract class MusicCommand extends Command 
{
    private final static Logger LOG = LoggerFactory.getLogger(MusicCommand.class);

    protected final Bot bot;
    protected boolean bePlaying;
    protected boolean beListening;
    
    public MusicCommand(Bot bot)
    {
        this.bot = bot;
        this.guildOnly = true;
        this.category = new Category("Music");
    }
    
    @Override
    protected void execute(CommandEvent event) 
    {
        LOG.debug("Prefix music command '{}' invoked in guild {} ({}) by user {} ({})",
                name, event.getGuild().getName(), event.getGuild().getId(), event.getAuthor().getName(), event.getAuthor().getId());
        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        TextChannel tchannel = settings.getTextChannel(event.getGuild());
        if(tchannel!=null && !event.getTextChannel().equals(tchannel))
        {
            LOG.debug("Rejected prefix music command '{}' in guild {} ({}): wrong text channel {} (expected {})",
                    name, event.getGuild().getName(), event.getGuild().getId(), event.getTextChannel().getId(), tchannel.getId());
            try 
            {
                event.getMessage().delete().queue();
            } catch(PermissionException ignore){}
            event.replyInDm(event.getClient().getError()+" You can only use that command in "+tchannel.getAsMention()+"!");
            return;
        }
        bot.getPlayerManager().setUpHandler(event.getGuild()); // no point constantly checking for this later
        if(bePlaying && !((AudioHandler)event.getGuild().getAudioManager().getSendingHandler()).isMusicPlaying(event.getJDA()))
        {
            LOG.debug("Rejected prefix music command '{}' in guild {} ({}): no music playing",
                    name, event.getGuild().getName(), event.getGuild().getId());
            event.reply(event.getClient().getError()+" There must be music playing to use that!");
            return;
        }
        if(beListening)
        {
            AudioChannel current = event.getGuild().getSelfMember().getVoiceState().getChannel();
            if(current==null)
                current = settings.getVoiceChannel(event.getGuild());
            GuildVoiceState userState = event.getMember().getVoiceState();
            if(!userState.inAudioChannel() || userState.isDeafened() || (current!=null && !userState.getChannel().equals(current)))
            {
                LOG.debug("Rejected prefix music command '{}' in guild {} ({}): user voice mismatch; requiredChannel={}, userChannel={}, deafened={}",
                        name, event.getGuild().getName(), event.getGuild().getId(),
                        current == null ? "any" : current.getId(),
                        userState.inAudioChannel() ? userState.getChannel().getId() : "none",
                        userState.isDeafened());
                event.replyError("You must be listening in "+(current==null ? "a voice channel" : current.getAsMention())+" to use that!");
                return;
            }

            VoiceChannel afkChannel = userState.getGuild().getAfkChannel();
            if(afkChannel != null && afkChannel.equals(userState.getChannel()))
            {
                LOG.debug("Rejected prefix music command '{}' in guild {} ({}): user is in AFK channel {}",
                        name, event.getGuild().getName(), event.getGuild().getId(), afkChannel.getId());
                event.replyError("You cannot use that command in an AFK channel!");
                return;
            }

            if(!event.getGuild().getSelfMember().getVoiceState().inAudioChannel())
            {
                try 
                {
                    LOG.info("Opening audio connection for prefix command '{}' in guild {} ({}) to channel {} ({})",
                            name, event.getGuild().getName(), event.getGuild().getId(),
                            userState.getChannel().getName(), userState.getChannel().getId());
                    event.getGuild().getAudioManager().openAudioConnection(userState.getChannel());
                }
                catch(PermissionException ex) 
                {
                    LOG.warn("Failed to open audio connection for prefix command '{}' in guild {} ({}) to channel {} ({})",
                            name, event.getGuild().getName(), event.getGuild().getId(),
                            userState.getChannel().getName(), userState.getChannel().getId(), ex);
                    event.reply(event.getClient().getError()+" I am unable to connect to "+userState.getChannel().getAsMention()+"!");
                    return;
                }
            }
        }
        
        doCommand(event);
    }
    
    public abstract void doCommand(CommandEvent event);
}
