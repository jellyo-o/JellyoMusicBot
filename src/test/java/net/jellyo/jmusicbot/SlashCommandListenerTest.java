/*
 * Copyright 2024 Jellyo.
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
package com.jagrosh.jmusicbot;

import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SlashCommandListenerTest
{
    @Test
    public void slashCommandNamesAreUnique()
    {
        List<SlashCommandData> commands = SlashCommandListener.buildSlashCommands();
        Set<String> names = new HashSet<>();

        for (SlashCommandData command : commands)
            assertTrue("Duplicate slash command: " + command.getName(), names.add(command.getName()));

        assertEquals(commands.size(), names.size());
    }

    @Test
    public void songQueryCommandsUseAutocomplete()
    {
        assertQueryAutocompleteEnabled("play");
        assertQueryAutocompleteEnabled("playtop");
        assertQueryAutocompleteEnabled("playnext");
    }

    @Test
    public void userFacingPrefixCommandsHaveSlashEquivalents()
    {
        Set<String> names = new HashSet<>();
        for (SlashCommandData command : SlashCommandListener.buildSlashCommands())
            names.add(command.getName());

        String[] expected = {
                "about", "help", "ping", "settings",
                "play", "playtop", "playplaylist", "playlist", "like", "liked", "nowplaying", "queue", "skip", "remove", "shuffle", "seek",
                "lyrics", "correctlyrics", "playlists", "search", "scsearch",
                "forceskip", "pause", "resume", "stop", "volume", "filter", "repeat", "loop", "skipto", "move", "playnext", "forceremove",
                "prefix", "setdj", "settc", "setvc", "setskip", "skipratio", "queuetype"
        };

        for (String command : expected)
            assertTrue("Missing slash command: " + command, names.contains(command));
    }

    @Test
    public void slashCommandsAreGuildOnly()
    {
        for (SlashCommandData command : SlashCommandListener.buildSlashCommands())
        {
            assertEquals("Unexpected slash command context for /" + command.getName(),
                    Collections.singleton(InteractionContextType.GUILD),
                    command.getContexts());
        }
    }

    @Test
    public void correctLyricsRequiresUrlAndQuery()
    {
        SlashCommandData command = SlashCommandListener.buildSlashCommands().stream()
                .filter(cmd -> "correctlyrics".equals(cmd.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(command);
        OptionData url = command.getOptions().stream()
                .filter(option -> "url".equals(option.getName()))
                .findFirst()
                .orElse(null);
        OptionData query = command.getOptions().stream()
                .filter(option -> "query".equals(option.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(url);
        assertNotNull(query);
        assertTrue(url.isRequired());
        assertTrue(query.isRequired());
    }

    @Test
    public void playlistCommandHasExpectedSubcommands()
    {
        SlashCommandData command = SlashCommandListener.buildSlashCommands().stream()
                .filter(cmd -> "playlist".equals(cmd.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(command);
        Set<String> subcommands = command.getSubcommands().stream()
                .map(subcommand -> subcommand.getName())
                .collect(Collectors.toSet());

        String[] expected = {
                "list", "create", "rename", "delete", "view", "play", "add", "addcurrent", "addqueue",
                "remove", "move", "clear", "share", "addshared", "unshare", "unfollow", "copy"
        };
        for(String subcommand : expected)
            assertTrue("Missing playlist subcommand: " + subcommand, subcommands.contains(subcommand));
    }

    @Test
    public void filterCommandHasExpectedPresetChoices()
    {
        SlashCommandData command = SlashCommandListener.buildSlashCommands().stream()
                .filter(cmd -> "filter".equals(cmd.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(command);
        OptionData preset = command.getOptions().stream()
                .filter(option -> "preset".equals(option.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(preset);
        Set<String> choices = preset.getChoices().stream()
                .map(choice -> choice.getAsString())
                .collect(Collectors.toSet());

        String[] expected = {"off", "bassboost", "nightcore", "8d", "vaporwave", "tremolo", "karaoke"};
        for(String choice : expected)
            assertTrue("Missing filter preset choice: " + choice, choices.contains(choice));
    }

    @Test
    public void slashCommandUpdateCheckSkipsIdenticalDefinitions()
    {
        List<SlashCommandData> desired = SlashCommandListener.buildSlashCommands();
        List<CommandData> existing = desired.stream()
                .map(command -> CommandData.fromData(command.toData()))
                .collect(Collectors.toList());

        assertFalse(SlashCommandListener.commandDataNeedUpdate(desired, existing));
    }

    @Test
    public void slashCommandUpdateCheckDetectsChangedDefinitions()
    {
        List<SlashCommandData> desired = SlashCommandListener.buildSlashCommands();
        List<CommandData> existing = new ArrayList<>(desired.stream()
                .map(command -> CommandData.fromData(command.toData()))
                .collect(Collectors.toList()));
        existing.removeIf(command -> "ping".equals(command.getName()));
        existing.add(Commands.slash("ping", "Old ping description"));

        assertTrue(SlashCommandListener.commandDataNeedUpdate(desired, existing));
    }

    private void assertQueryAutocompleteEnabled(String commandName)
    {
        SlashCommandData command = SlashCommandListener.buildSlashCommands().stream()
                .filter(cmd -> commandName.equals(cmd.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(command);
        OptionData query = command.getOptions().stream()
                .filter(option -> "query".equals(option.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(query);
        assertTrue(query.isAutoComplete());
    }
}
