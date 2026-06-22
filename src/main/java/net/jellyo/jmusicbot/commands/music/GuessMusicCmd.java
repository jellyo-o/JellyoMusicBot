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
import com.jagrosh.jmusicbot.guessmusic.GuessMusicService.Options;

public class GuessMusicCmd extends MusicCommand
{
    public GuessMusicCmd(Bot bot)
    {
        super(bot);
        this.name = "guess";
        this.arguments = "[start|status|stop|reveal|join|leave|hints|highlight] [options]";
        this.help = "starts or controls a guess the music game";
        this.aliases = new String[]{"guessmusic", "guessthesong"};
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
                Options options = bot.getGuessMusicService().optionsFromPrefix("start".equals(action) ? rest : args);
                bot.getGuessMusicService().start(context, options);
                break;
            case "status":
                bot.getGuessMusicService().status(context);
                break;
            case "stop":
                bot.getGuessMusicService().stop(context);
                break;
            case "reveal":
                bot.getGuessMusicService().reveal(context);
                break;
            case "join":
                bot.getGuessMusicService().join(context);
                break;
            case "leave":
                bot.getGuessMusicService().leave(context);
                break;
            case "hints":
                handleHints(context, rest);
                break;
            case "highlight":
            case "chorus":
                bot.getGuessMusicService().setHighlight(context, rest);
                break;
            default:
                event.replyWarning("Unknown guess action. Try `" + event.getClient().getPrefix()
                        + "guess start`, `status`, `join`, `leave`, `reveal`, `hints`, `highlight`, or `stop`.");
                break;
        }
    }

    private void handleHints(MessageCommandContext context, String args)
    {
        String first = firstWord(args).toLowerCase();
        Boolean enabled = null;
        if("on".equals(first) || "true".equals(first) || "yes".equals(first) || "enabled".equals(first))
            enabled = true;
        else if("off".equals(first) || "false".equals(first) || "no".equals(first) || "disabled".equals(first))
            enabled = false;

        Integer interval = null;
        Integer seconds = null;
        Integer replays = null;
        String[] tokens = args == null || args.isBlank() ? new String[0] : args.trim().split("\\s+");
        for(String token : tokens)
        {
            String[] parts = token.split("=", 2);
            if(parts.length != 2)
                continue;
            String key = parts[0].toLowerCase();
            if("interval".equals(key) || "hint_interval".equals(key))
                interval = parseInt(parts[1]);
            else if("seconds".equals(key) || "hint_seconds".equals(key) || "step".equals(key))
                seconds = parseInt(parts[1]);
            else if("replays".equals(key) || "hint_replays".equals(key))
                replays = parseInt(parts[1]);
        }

        bot.getGuessMusicService().setHints(context, enabled, interval, seconds, replays);
    }

    private static Integer parseInt(String value)
    {
        try
        {
            return Integer.valueOf(value);
        }
        catch(NumberFormatException ex)
        {
            return null;
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
