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
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.AvoidStore;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

/**
 * Blocks a song from being chosen by autoplay. With no argument it avoids the
 * currently-playing song and skips it; with an argument it avoids a song by
 * name. The avoid list is per-guild and persists across restarts.
 */
public class AvoidCmd extends DJCommand implements UnifiedCommand
{
    public AvoidCmd(Bot bot)
    {
        super(bot);
        this.name = "avoid";
        this.help = "blocks a song from autoplay (skips it if it is playing)";
        this.arguments = "[song]";
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
        long guildId = ctx.getGuild().getIdLong();
        AudioHandler handler = (AudioHandler) ctx.getGuild().getAudioManager().getSendingHandler();
        AudioTrack current = handler == null ? null : handler.getPlayer().getPlayingTrack();
        String args = ctx.getArgs() == null ? "" : ctx.getArgs().trim();

        if(args.isEmpty())
        {
            if(current == null)
            {
                ctx.replyError("Nothing is playing — use `avoid <song>` to block a specific song.");
                return;
            }
            AvoidStore.Result result = store.avoidTrack(guildId, current);
            if(!result.isIdentifiable())
            {
                ctx.replyError("I couldn't identify the current song well enough to avoid it.");
                return;
            }
            handler.skipCurrentTrack(ctx.getAuthor(), "avoid");
            ctx.replySuccess("🚫 Avoided **" + result.getLabel() + "** and skipped it. Autoplay won't pick it again.");
            return;
        }

        AvoidStore.Result result = store.avoidQuery(guildId, args);
        if(!result.isIdentifiable())
        {
            ctx.replyError("I couldn't make an avoid entry from `" + args + "`.");
            return;
        }
        boolean skipped = false;
        if(handler != null && current != null && store.isAvoided(guildId, current))
        {
            handler.skipCurrentTrack(ctx.getAuthor(), "avoid");
            skipped = true;
        }
        String suffix = skipped
                ? " and skipped the current song."
                : (result.isAlreadyAvoided() ? " (it was already on the list)." : ".");
        ctx.replySuccess("🚫 Avoided **" + result.getLabel() + "**" + suffix + " Autoplay won't pick it again.");
    }

    @Override
    public void doCommand(CommandEvent event) { /* Intentionally empty — handled via CommandContext. */ }
}
