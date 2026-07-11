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
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

/**
 * Starts a live karaoke view for the current song: a single message whose lyrics advance
 * line-by-line as the track plays. Only works for songs with time-synced (LRC) lyrics;
 * otherwise it posts the full plain lyrics and says so.
 */
public class KaraokeCmd extends MusicCommand
{
    public KaraokeCmd(Bot bot)
    {
        super(bot);
        this.name = "karaoke";
        this.help = "shows time-synced karaoke lyrics for the current song";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.bePlaying = true;
        this.blockDuringGuessMusic = true;
        this.botPermissions = new Permission[]{Permission.MESSAGE_EMBED_LINKS};
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        AudioTrack track = handler.getPlayer().getPlayingTrack();
        TextChannel channel = event.getTextChannel();
        event.getChannel().sendTyping().queue(null, t -> {});
        bot.getNowplayingHandler().getKaraoke().startManual(event.getGuild(), track, channel,
                msg -> event.getChannel().sendMessage(msg).queue(null, t -> {}));
    }
}
