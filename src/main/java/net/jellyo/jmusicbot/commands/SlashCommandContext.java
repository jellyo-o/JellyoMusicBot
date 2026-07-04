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

import com.jagrosh.jmusicbot.Bot;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.function.Consumer;

/**
 * Adapter that wraps a SlashCommandInteractionEvent as a CommandContext.
 */
public class SlashCommandContext implements CommandContext
{
    private final SlashCommandInteractionEvent event;
    private final Bot bot;
    private final String args;
    private InteractionHook hook;
    private boolean deferred = false;
    private boolean replied = false;
    
    public SlashCommandContext(SlashCommandInteractionEvent event, Bot bot, String args)
    {
        this.event = event;
        this.bot = bot;
        this.args = args != null ? args : "";
    }
    
    public SlashCommandInteractionEvent getEvent()
    {
        return event;
    }
    
    public void setHook(InteractionHook hook)
    {
        this.hook = hook;
        this.deferred = true;
    }
    
    public boolean isDeferred()
    {
        return deferred;
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
        return event.getGuild() != null ? event.getGuild().getSelfMember() : null;
    }
    
    @Override
    public User getAuthor()
    {
        return event.getUser();
    }
    
    @Override
    public MessageChannel getChannel()
    {
        return event.getChannel();
    }
    
    @Override
    public String getArgs()
    {
        return args;
    }
    
    @Override
    public String getOwnerId()
    {
        return String.valueOf(bot.getConfig().getOwnerId());
    }
    
    @Override
    public String getSuccess()
    {
        return bot.getConfig().getSuccess();
    }
    
    @Override
    public String getWarning()
    {
        return bot.getConfig().getWarning();
    }
    
    @Override
    public String getError()
    {
        return bot.getConfig().getError();
    }
    
    @Override
    public String getPrefix()
    {
        return "/";
    }
    
    private void doReply(String message)
    {
        doReply(message, false);
    }

    private void doReply(String message, boolean ephemeral)
    {
        if(deferred && hook != null)
        {
            hook.editOriginal(message).queue();
        }
        else if(!replied)
        {
            event.reply(message).setEphemeral(ephemeral).queue();
            replied = true;
        }
        else
        {
            event.getHook().sendMessage(message).setEphemeral(ephemeral).queue();
        }
    }
    
    @Override
    public void reply(String message)
    {
        doReply(message);
    }
    
    @Override
    public void replySuccess(String message)
    {
        doReply(getSuccess() + " " + message);
    }
    
    @Override
    public void replyWarning(String message)
    {
        doReply(getWarning() + " " + message);
    }
    
    @Override
    public void replyError(String message)
    {
        doReply(getError() + " " + message);
    }

    @Override
    public void replyEphemeral(String message)
    {
        doReply(message, true);
    }

    @Override
    public void replyErrorEphemeral(String message)
    {
        doReply(getError() + " " + message, true);
    }
    
    @Override
    public void reply(MessageCreateData message)
    {
        if(deferred && hook != null)
        {
            hook.editOriginal(net.dv8tion.jda.api.utils.messages.MessageEditData.fromCreateData(message))
                    .queue();
        }
        else if(!replied)
        {
            event.reply(message).queue();
            replied = true;
        }
        else
        {
            event.getHook().sendMessage(message).queue();
        }
    }
    
    @Override
    public void reply(MessageCreateData message, Consumer<Message> callback)
    {
        if(deferred && hook != null)
        {
            hook.editOriginal(net.dv8tion.jda.api.utils.messages.MessageEditData.fromCreateData(message))
                    .queue(m -> callback.accept(m));
        }
        else if(!replied)
        {
            event.reply(message).queue(ih -> ih.retrieveOriginal().queue(callback));
            replied = true;
        }
        else
        {
            event.getHook().sendMessage(message).queue(callback);
        }
    }

    @Override
    public void reply(MessageCreateData message, Consumer<Message> onSuccess, Consumer<Throwable> onFailure)
    {
        if(deferred && hook != null)
        {
            hook.editOriginal(net.dv8tion.jda.api.utils.messages.MessageEditData.fromCreateData(message))
                    .queue(onSuccess, onFailure);
        }
        else if(!replied)
        {
            event.reply(message).queue(ih -> ih.retrieveOriginal().queue(onSuccess, onFailure), onFailure);
            replied = true;
        }
        else
        {
            event.getHook().sendMessage(message).queue(onSuccess, onFailure);
        }
    }

    @Override
    public boolean isSlashCommand()
    {
        return true;
    }
    
    /**
     * Get an option value as string
     */
    public String getOption(String name)
    {
        OptionMapping opt = event.getOption(name);
        return opt != null ? opt.getAsString() : null;
    }
    
    /**
     * Get an option value as int
     */
    public int getOptionAsInt(String name, int defaultValue)
    {
        OptionMapping opt = event.getOption(name);
        return opt != null ? (int)opt.getAsLong() : defaultValue;
    }
}
