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
package com.jagrosh.jmusicbot.commands.admin;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.AdminCommand;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.CommandParsers;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.settings.Settings;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class SkipratioCmd extends AdminCommand implements UnifiedCommand
{
    private final Bot bot;

    public SkipratioCmd(Bot bot)
    {
        this.bot = bot;
        this.name = "setskip";
        this.help = "sets a server-specific skip percentage";
        this.arguments = "<0 - 100>";
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
        try
        {
            int val = CommandParsers.parseSkipPercentage(event.getArgs());
            Settings s = bot.getSettingsManager().getSettings(event.getGuild());
            s.setSkipRatio(val / 100.0);
            event.replySuccess("Skip percentage has been set to `" + val + "%` of listeners on *" + event.getGuild().getName() + "*");
        }
        catch(RuntimeException ex)
        {
            event.replyError("Please include an integer between 0 and 100 (default is 55). This number is the percentage of listening users that must vote to skip a song.");
        }
    }
}
