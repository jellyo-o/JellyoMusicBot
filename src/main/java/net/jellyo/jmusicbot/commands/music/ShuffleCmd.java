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

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.commands.CommandChecks;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class ShuffleCmd extends MusicCommand implements UnifiedCommand
{
    public ShuffleCmd(Bot bot)
    {
        super(bot);
        this.name = "shuffle";
        this.help = "shuffles your queued songs, or the full queue for DJs";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = true;
    }

    @Override
    public void doCommand(CommandEvent event) 
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext event)
    {
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        boolean fullQueue = CommandChecks.checkDJPermission(event, bot);
        int s = fullQueue ? handler.getQueue().shuffleAll() : handler.getQueue().shuffle(event.getAuthor().getIdLong());
        switch (s) 
        {
            case 0:
                event.replyError(fullQueue ? "There is no music in the queue to shuffle!" : "You don't have any music in the queue to shuffle!");
                break;
            case 1:
                event.replyWarning(fullQueue ? "There is only one song in the queue!" : "You only have one song in the queue!");
                break;
            default:
                handler.updateMusicPanels();
                event.replySuccess(fullQueue ? "You successfully shuffled the full queue (`"+s+"` entries)."
                        : "You successfully shuffled your "+s+" entries.");
                break;
        }
    }
    
}
