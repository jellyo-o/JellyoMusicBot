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
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.MusicCommand;

/**
 * Hosts a guess the music game in which the host privately picks every song (one at a time as the game
 * runs, or a pre-set list before it starts). The guessing is identical to {@code /guess}; the only
 * differences are that the host controls the songs and that correct guesses grant no coins/XP — players
 * keep only their passive listening XP. Like {@link GuessMusicCmd} this command must run while a game is
 * active (to control it), so it does not set {@code blockDuringGuessMusic}.
 */
public class HostGameCmd extends MusicCommand
{
    public HostGameCmd(Bot bot)
    {
        super(bot);
        this.name = "hostgame";
        this.arguments = "[start|add|status|join|leave|reveal|stop] [options]";
        this.help = "hosts a guess the music game where you pick the songs";
        this.aliases = new String[]{"hostguess", "guesshost"};
        this.beListening = true;
        this.bePlaying = false;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        MessageCommandContext context = new MessageCommandContext(event);
        String args = event.getArgs() == null ? "" : event.getArgs().trim();
        String action = firstWord(args).toLowerCase();
        String rest = rest(args);

        switch(action)
        {
            case "":
            case "start":
                bot.getGuessMusicService().start(context,
                        bot.getGuessMusicService().hostOptionsFromPrefix("start".equals(action) ? rest : args));
                break;
            case "add":
            case "addsong":
            case "song":
                bot.getGuessMusicService().promptHostAdd(context);
                break;
            case "status":
                bot.getGuessMusicService().status(context);
                break;
            case "join":
                bot.getGuessMusicService().join(context);
                break;
            case "leave":
                bot.getGuessMusicService().leave(context);
                break;
            case "reveal":
                bot.getGuessMusicService().reveal(context);
                break;
            case "stop":
                bot.getGuessMusicService().stop(context);
                break;
            default:
                event.replyWarning("Unknown host action. Try `" + event.getClient().getPrefix()
                        + "hostgame start`, `add`, `status`, `join`, `leave`, `reveal`, or `stop`.");
                break;
        }
    }

    private static String firstWord(String value)
    {
        if(value == null || value.isBlank())
            return "";
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    private static String rest(String value)
    {
        if(value == null)
            return "";
        int space = value.indexOf(' ');
        return space < 0 ? "" : value.substring(space + 1).trim();
    }
}
