/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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
import com.jagrosh.jmusicbot.commands.CommandParsers;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class VolumeCmd extends DJCommand implements UnifiedCommand
{
    public VolumeCmd(Bot bot)
    {
        super(bot);
        this.name = "volume";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.help = "sets or shows volume";
        this.arguments = "[0-150]";
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
        if(handler == null)
        {
            event.replyWarning("No audio handler active.");
            return;
        }
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        int volume = handler.getPlayer().getVolume();
        if(event.getArgs().isEmpty())
        {
            event.reply(FormatUtil.volumeIcon(volume)+" Current volume is `"+volume+"`");
        }
        else
        {
            int nvolume;
            try
            {
                nvolume = CommandParsers.parseVolume(event.getArgs());
            }
            catch(RuntimeException e)
            {
                event.reply(event.getError()+" Volume must be a valid integer between 0 and 150!");
                return;
            }
            handler.getPlayer().setVolume(nvolume);
            settings.setVolume(nvolume);
            handler.updateMusicPanels();
            event.reply(FormatUtil.volumeIcon(nvolume)+" Volume changed from `"+volume+"` to `"+nvolume+"`");
        }
    }
    
}
