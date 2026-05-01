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
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.CommandParsers;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.settings.AutoplayMode;
import com.jagrosh.jmusicbot.settings.Settings;

public class AutoplayCmd extends DJCommand implements UnifiedCommand
{
    public AutoplayCmd(Bot bot)
    {
        super(bot);
        this.name = "autoplay";
        this.help = "sets automatic radio playback when the queue ends";
        this.arguments = "[off|smart|related|artist|playlist|server]";
        this.aliases = java.util.stream.Stream.concat(
                java.util.Arrays.stream(bot.getConfig().getAliases(this.name)),
                java.util.stream.Stream.of("radio"))
                .distinct()
                .toArray(String[]::new);
        this.guildOnly = true;
    }

    @Override
    protected void execute(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext event)
    {
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        AutoplayMode value;
        try
        {
            value = CommandParsers.parseAutoplayMode(event.getArgs(), settings.getAutoplayMode());
        }
        catch(IllegalArgumentException ex)
        {
            event.replyError("Valid options are `" + String.join("`, `", AutoplayMode.getNames()) + "` (or leave empty to toggle smart mode)");
            return;
        }

        settings.setAutoplayMode(value);
        event.replySuccess("Autoplay mode is now `" + value.getUserFriendlyName() + "`");
    }

    @Override
    public void doCommand(CommandEvent event) { /* Intentionally Empty */ }
}
