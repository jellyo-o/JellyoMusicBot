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
package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;

/**
 * Restores the queue saved before the bot crashed, restarted or everyone left
 * the voice channel. Requires the user to be in a voice channel (the bot joins
 * to resume playback).
 */
public class RestoreCmd extends MusicCommand implements UnifiedCommand
{
    public RestoreCmd(Bot bot)
    {
        super(bot);
        this.name = "restore";
        this.help = "restores the queue saved before a crash, restart or everyone leaving";
        this.arguments = "";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
        this.blockDuringGuessMusic = true;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext ctx)
    {
        if(bot.getCrashRecoveryService() == null || !bot.getCrashRecoveryService().isEnabled())
        {
            ctx.replyError("Crash recovery is not available right now.");
            return;
        }
        int count = bot.getCrashRecoveryService().restore(ctx.getGuild());
        if(count < 0)
            ctx.replyWarning("Music is already playing — stop or clear the queue first, then run `restore` again.");
        else if(count == 0)
            ctx.replyWarning("There's nothing to restore — no saved queue was found.");
        else
            ctx.replySuccess("♻️ Restoring **" + count + "** track" + (count == 1 ? "" : "s")
                    + " from your saved queue…");
    }
}
