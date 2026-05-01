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
package com.jagrosh.jmusicbot.commands.music;

import java.util.List;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistSummary;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class PlaylistsCmd extends MusicCommand 
{
    public PlaylistsCmd(Bot bot)
    {
        super(bot);
        this.name = "playlists";
        this.help = "shows the available playlists";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = true;
        this.beListening = false;
        this.beListening = false;
    }
    
    @Override
    public void doCommand(CommandEvent event) 
    {
        List<PlaylistSummary> list = bot.getUserPlaylistService().listPlaylists(event.getAuthor().getIdLong());
        if(list.isEmpty())
            event.replyInDm(event.getClient().getWarning()+" You do not have any playlists yet.");
        else
        {
            StringBuilder builder = new StringBuilder(event.getClient().getSuccess()+" Your playlists:\n");
            list.forEach(playlist -> builder.append("`").append(playlist.getName()).append("` - ")
                    .append(playlist.getItemCount()).append(" songs")
                    .append(playlist.isFollowed() ? " (followed, read-only)" : "")
                    .append(playlist.isLiked() ? " (liked)" : "")
                    .append("\n"));
            builder.append("\nType `").append(event.getClient().getTextualPrefix()).append("play playlist <name>` to play a playlist");
            event.replyInDm(builder.toString());
        }
    }
}
