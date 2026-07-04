/*
 * Copyright 2024 Jellyo.
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

import com.jagrosh.jdautilities.command.CommandEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.function.Consumer;

/**
 * Adapter that wraps a CommandEvent (message command) as a CommandContext.
 */
public class MessageCommandContext implements CommandContext
{
    private final CommandEvent event;
    
    public MessageCommandContext(CommandEvent event)
    {
        this.event = event;
    }
    
    public CommandEvent getEvent()
    {
        return event;
    }
    
    @Override
    public JDA getJDA()
    {
        return event.getJDA();
    }
    
    @Override
    public Guild getGuild()
    {
        return event.getGuild();
    }
    
    @Override
    public Member getMember()
    {
        return event.getMember();
    }
    
    @Override
    public Member getSelfMember()
    {
        return event.getSelfMember();
    }
    
    @Override
    public User getAuthor()
    {
        return event.getAuthor();
    }
    
    @Override
    public MessageChannel getChannel()
    {
        return event.getChannel();
    }
    
    @Override
    public String getArgs()
    {
        return event.getArgs();
    }
    
    @Override
    public String getOwnerId()
    {
        return event.getClient().getOwnerId();
    }
    
    @Override
    public String getSuccess()
    {
        return event.getClient().getSuccess();
    }
    
    @Override
    public String getWarning()
    {
        return event.getClient().getWarning();
    }
    
    @Override
    public String getError()
    {
        return event.getClient().getError();
    }
    
    @Override
    public String getPrefix()
    {
        return event.getClient().getPrefix();
    }
    
    @Override
    public void reply(String message)
    {
        event.reply(message);
    }
    
    @Override
    public void replySuccess(String message)
    {
        event.replySuccess(message);
    }
    
    @Override
    public void replyWarning(String message)
    {
        event.replyWarning(message);
    }
    
    @Override
    public void replyError(String message)
    {
        event.replyError(message);
    }

    @Override
    public void replyEphemeral(String message)
    {
        event.reply(message);
    }

    @Override
    public void replyErrorEphemeral(String message)
    {
        event.replyError(message);
    }
    
    @Override
    public void reply(MessageCreateData message)
    {
        event.reply(message);
    }
    
    @Override
    public void reply(MessageCreateData message, Consumer<Message> callback)
    {
        event.reply(message, callback);
    }

    @Override
    public void reply(MessageCreateData message, Consumer<Message> onSuccess, Consumer<Throwable> onFailure)
    {
        // Send to the channel directly so a failed send routes to onFailure (CommandEvent.reply is
        // success-only), letting the caller refund an already-escrowed wager.
        event.getChannel().sendMessage(message).queue(onSuccess, onFailure);
    }

    @Override
    public boolean isSlashCommand()
    {
        return false;
    }
}
