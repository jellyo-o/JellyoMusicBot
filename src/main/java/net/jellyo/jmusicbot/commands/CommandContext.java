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

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.function.Consumer;

/**
 * Unified command context that works for both message commands and slash commands.
 * This allows command logic to be shared between both input methods.
 */
public interface CommandContext
{
    /**
     * Get the JDA instance
     */
    JDA getJDA();
    
    /**
     * Get the guild where the command was executed (null if DM)
     */
    Guild getGuild();
    
    /**
     * Get the member who executed the command (null if DM)
     */
    Member getMember();
    
    /**
     * Get the self member (bot) in the guild
     */
    Member getSelfMember();
    
    /**
     * Get the user who executed the command
     */
    User getAuthor();

    /**
     * Resolve a user referenced in this command (a slash USER option or a message mention) by id.
     * Uses the interaction's or message's resolved data so it works even when the user is not in
     * JDA's cache (the bot runs without the GUILD_MEMBERS intent). Falls back to the JDA cache, and
     * returns null when the user cannot be resolved.
     */
    User resolveUser(long userId);
    
    /**
     * Get the channel where the command was executed
     */
    MessageChannel getChannel();
    
    /**
     * Get the command arguments as a string
     */
    String getArgs();
    
    /**
     * Get the bot owner ID
     */
    String getOwnerId();
    
    /**
     * Get the success emoji
     */
    String getSuccess();
    
    /**
     * Get the warning emoji
     */
    String getWarning();
    
    /**
     * Get the error emoji
     */
    String getError();
    
    /**
     * Get the command prefix
     */
    String getPrefix();
    
    /**
     * Reply to the command
     */
    void reply(String message);
    
    /**
     * Reply with a success message
     */
    void replySuccess(String message);
    
    /**
     * Reply with a warning message
     */
    void replyWarning(String message);
    
    /**
     * Reply with an error message
     */
    void replyError(String message);

    /**
     * Reply to the command privately when supported.
     */
    void replyEphemeral(String message);

    /**
     * Reply with an error message privately when supported.
     */
    void replyErrorEphemeral(String message);
    
    /**
     * Reply with a MessageCreateData
     */
    void reply(MessageCreateData message);
    
    /**
     * Reply with a MessageCreateData and callback
     */
    void reply(MessageCreateData message, Consumer<net.dv8tion.jda.api.entities.Message> callback);

    /**
     * Reply with a MessageCreateData, invoking {@code onSuccess} once the message is sent or
     * {@code onFailure} if the send fails — so callers can unwind side effects (e.g. a debited
     * wager) when the panel never goes live.
     */
    void reply(MessageCreateData message, Consumer<net.dv8tion.jda.api.entities.Message> onSuccess,
               Consumer<Throwable> onFailure);

    /**
     * Whether this is a slash command context
     */
    boolean isSlashCommand();
}
