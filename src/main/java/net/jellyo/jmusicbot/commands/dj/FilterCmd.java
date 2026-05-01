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
import com.jagrosh.jmusicbot.audio.filter.AudioFilterPreset;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Applies fun PCM audio filter presets to a guild player.
 */
public class FilterCmd extends DJCommand implements UnifiedCommand
{
    public FilterCmd(Bot bot)
    {
        super(bot);
        this.name = "filter";
        this.aliases = Stream.concat(
                Arrays.stream(bot.getConfig().getAliases(this.name)),
                Stream.of("filters", "effect", "effects"))
                .distinct()
                .toArray(String[]::new);
        this.help = "sets or shows the audio filter";
        this.arguments = "[off|bassboost|nightcore|8d|vaporwave|tremolo|karaoke|list]";
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext event)
    {
        AudioHandler handler = bot.getPlayerManager().setUpHandler(event.getGuild());
        String args = event.getArgs().trim();

        if(args.isEmpty() || "status".equalsIgnoreCase(args) || "current".equalsIgnoreCase(args))
        {
            event.reply("Current audio filter is `" + handler.getFilterPreset().getDisplayName()
                    + "`. Available filters: " + AudioFilterPreset.availablePresetList());
            return;
        }

        if("list".equalsIgnoreCase(args) || "options".equalsIgnoreCase(args))
        {
            event.reply("Available audio filters: " + AudioFilterPreset.availablePresetList());
            return;
        }

        Optional<AudioFilterPreset> preset = AudioFilterPreset.fromInput(args);
        if(!preset.isPresent())
        {
            event.replyError("Unknown audio filter. Valid filters are: " + AudioFilterPreset.availablePresetList());
            return;
        }

        handler.setFilterPreset(preset.get());
        if(preset.get().isEnabled())
            event.replySuccess("Audio filter set to `" + preset.get().getDisplayName() + "`");
        else
            event.replySuccess("Audio filters cleared");
    }
}
