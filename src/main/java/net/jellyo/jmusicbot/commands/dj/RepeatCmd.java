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
package com.jagrosh.jmusicbot.commands.dj;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.CommandParsers;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class RepeatCmd extends DJCommand implements UnifiedCommand
{
    public RepeatCmd(Bot bot)
    {
        super(bot);
        this.name = "repeat";
        this.help = "re-adds music to the queue when finished";
        this.arguments = "[off|all|single]";
        this.aliases = java.util.stream.Stream.concat(
                java.util.Arrays.stream(bot.getConfig().getAliases(this.name)),
                java.util.stream.Stream.of("loop"))
                .distinct()
                .toArray(String[]::new);
        this.guildOnly = true;
    }
    
    // override musiccommand's execute because we don't actually care where this is used
    @Override
    protected void execute(CommandEvent event) 
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext event)
    {
        String args = event.getArgs();
        RepeatMode value;
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        try
        {
            value = CommandParsers.parseRepeatMode(args, settings.getRepeatMode());
        }
        catch(IllegalArgumentException ex)
        {
            event.replyError("Valid options are `off`, `all` or `single` (or leave empty to toggle between `off` and `all`)");
            return;
        }
        settings.setRepeatMode(value);
        event.replySuccess("Repeat mode is now `"+value.getUserFriendlyName()+"`");
    }

    @Override
    public void doCommand(CommandEvent event) { /* Intentionally Empty */ }
}
