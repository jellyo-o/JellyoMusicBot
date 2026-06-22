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
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schedules the bot to stop playing and leave after a duration, after the
 * current song, or after a number of songs. Use {@code status} to check it and
 * {@code off} to cancel it. The timer is also cancelled when a new song is
 * queued or when everyone leaves the voice channel.
 */
public class SleepCmd extends DJCommand implements UnifiedCommand
{
    private static final long MIN_MILLIS = 10_000L;
    private static final long MAX_MILLIS = 24L * 60 * 60 * 1000L;
    private static final int MAX_TRACKS = 100;
    private static final Pattern TRACKS = Pattern.compile("(\\d{1,3})\\s*(?:tracks?|songs?)");

    public SleepCmd(Bot bot)
    {
        super(bot);
        this.name = "sleep";
        this.help = "stops playback after a time or a number of songs";
        this.arguments = "<30m | 1h30m | track | 3 tracks | status | off>";
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
        if(ctx.getGuild() == null)
        {
            ctx.replyError("This command can only be used in a server.");
            return;
        }
        AudioHandler handler = (AudioHandler) ctx.getGuild().getAudioManager().getSendingHandler();
        String raw = ctx.getArgs() == null ? "" : ctx.getArgs().trim();
        String lower = raw.toLowerCase(Locale.ROOT);

        if(lower.isEmpty() || lower.equals("status"))
        {
            showStatus(ctx, handler);
            return;
        }
        if(isOff(lower))
        {
            if(handler != null && handler.cancelSleepTimer())
                ctx.replySuccess("Sleep timer cancelled.");
            else
                ctx.replyWarning("There is no sleep timer set.");
            return;
        }

        if(handler == null || handler.getPlayer().getPlayingTrack() == null)
        {
            ctx.replyError("I need to be playing something to set a sleep timer.");
            return;
        }
        long channelId = ctx.getChannel() == null ? 0L : ctx.getChannel().getIdLong();

        if(lower.equals("track") || lower.equals("song") || lower.equals("end") || lower.equals("endoftrack") || lower.equals("eot"))
        {
            handler.scheduleSleepTracks(1, channelId);
            ctx.replySuccess("💤 I'll stop after the current song finishes.");
            return;
        }
        Matcher tracks = TRACKS.matcher(lower);
        if(tracks.matches())
        {
            int n = Math.max(1, Math.min(MAX_TRACKS, Integer.parseInt(tracks.group(1))));
            handler.scheduleSleepTracks(n, channelId);
            ctx.replySuccess("💤 I'll stop after **" + n + "** more song" + (n == 1 ? "" : "s") + " finish.");
            return;
        }

        long millis = parseSleepMillis(lower);
        if(millis < 0)
        {
            ctx.replyError("I couldn't understand `" + raw + "`. Try `30m`, `1h30m`, `track`, `3 tracks`, `status`, or `off`.");
            return;
        }
        if(millis < MIN_MILLIS)
        {
            ctx.replyError("The minimum sleep time is 10 seconds.");
            return;
        }
        if(millis > MAX_MILLIS)
        {
            ctx.replyError("The maximum sleep time is 24 hours.");
            return;
        }
        handler.scheduleSleepDuration(millis, channelId);
        ctx.replySuccess("💤 I'll stop playing in **" + TimeUtil.formatTime(millis) + "**.");
    }

    private void showStatus(CommandContext ctx, AudioHandler handler)
    {
        if(handler == null || !handler.isSleepActive())
        {
            ctx.reply("💤 No sleep timer is set.");
            return;
        }
        long ms = handler.sleepRemainingMillis();
        if(ms >= 0)
        {
            ctx.reply("💤 Sleeping in **" + TimeUtil.formatTime(ms) + "**.");
        }
        else
        {
            int t = handler.getSleepTracksRemaining();
            ctx.reply("💤 Stopping after **" + t + "** more song" + (t == 1 ? "" : "s") + ".");
        }
    }

    private static boolean isOff(String s)
    {
        return s.equals("off") || s.equals("cancel") || s.equals("stop") || s.equals("none") || s.equals("disable");
    }

    private static long parseSleepMillis(String s)
    {
        if(s.matches("\\d{1,7}")) // a bare number means minutes
        {
            try
            {
                return Math.multiplyExact(Long.parseLong(s), 60_000L);
            }
            catch(ArithmeticException ex)
            {
                return -1; // absurdly large number — fall through to the "couldn't understand" reply
            }
        }
        long unit = TimeUtil.parseUnitTime(s);
        return unit > 0 ? unit : -1;
    }

    @Override
    public void doCommand(CommandEvent event) { /* Intentionally empty — handled via CommandContext. */ }
}
