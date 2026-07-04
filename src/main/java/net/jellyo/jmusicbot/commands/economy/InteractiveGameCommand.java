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
package com.jagrosh.jmusicbot.commands.economy;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.economy.games.GameSession;
import java.util.List;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * Base for the button-driven casino games: requires a guild, and starts an
 * interactive {@link GameSession} once the panel message has been sent.
 */
public abstract class InteractiveGameCommand extends WagerGameCommand
{
    protected static final long DEFAULT_TIMEOUT_MS = 90_000;

    protected InteractiveGameCommand(Bot bot, String name, String help, String arguments)
    {
        super(bot, name, help, arguments);
    }

    protected boolean requireGuild(CommandContext ctx)
    {
        if(ctx.getGuild() == null)
        {
            ctx.replyError("Button games can only be played in a server.");
            return false;
        }
        return true;
    }

    /** Sends the initial panel and registers the session once its message id is known. */
    protected void start(CommandContext ctx, GameSession session, MessageEmbed panel,
                         List<ActionRow> rows, long timeoutMs)
    {
        ctx.reply(new MessageCreateBuilder().setEmbeds(panel).setComponents(rows).build(),
                msg -> session.begin(msg.getIdLong(), timeoutMs),
                error -> session.cancelBeforeStart());
    }
}
