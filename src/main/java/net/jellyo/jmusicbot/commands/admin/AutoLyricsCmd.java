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
package com.jagrosh.jmusicbot.commands.admin;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.AdminCommand;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.CommandParsers;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.settings.Settings;

public class AutoLyricsCmd extends AdminCommand implements UnifiedCommand
{
    private final Bot bot;

    public AutoLyricsCmd(Bot bot)
    {
        super();
        this.bot = bot;
        this.name = "autolyrics";
        this.help = "toggles automatically posting lyrics when a song starts";
        this.arguments = "[on|off]";
        this.aliases = bot.getConfig().getAliases(this.name);
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
        boolean value;
        try
        {
            value = CommandParsers.parseToggle(event.getArgs(), settings.isAutoShowLyrics());
        }
        catch(IllegalArgumentException ex)
        {
            event.replyError("Valid options are `on` or `off` (or leave empty to toggle).");
            return;
        }
        settings.setAutoShowLyrics(value);
        event.replySuccess("Auto-posting lyrics on song start is now `" + (value ? "on" : "off") + "`.");
    }
}
