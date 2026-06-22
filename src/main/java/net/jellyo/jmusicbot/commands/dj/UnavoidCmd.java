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
package com.jagrosh.jmusicbot.commands.dj;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AvoidStore;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;

/**
 * Removes songs from the per-guild avoid list by name (matches the avoided
 * label or the song's identity).
 */
public class UnavoidCmd extends DJCommand implements UnifiedCommand
{
    public UnavoidCmd(Bot bot)
    {
        super(bot);
        this.name = "unavoid";
        this.help = "removes a song from the server's avoid list";
        this.arguments = "<song>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = true;
    }

    @Override
    protected void execute(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext ctx)
    {
        AvoidStore store = bot.getAvoidStore();
        if(store == null || ctx.getGuild() == null)
        {
            ctx.replyError("The avoid list is unavailable right now.");
            return;
        }
        String args = ctx.getArgs() == null ? "" : ctx.getArgs().trim();
        if(args.isEmpty())
        {
            ctx.replyError("Tell me which song to unavoid, e.g. `unavoid never gonna give you up`.");
            return;
        }
        int removed = store.unavoid(ctx.getGuild().getIdLong(), args);
        if(removed == 0)
            ctx.replyWarning("No avoided songs matched `" + args + "`.");
        else
            ctx.replySuccess("Removed " + removed + " avoided song" + (removed == 1 ? "" : "s")
                    + " matching `" + args + "`.");
    }

    @Override
    public void doCommand(CommandEvent event) { /* Intentionally empty — handled via CommandContext. */ }
}
