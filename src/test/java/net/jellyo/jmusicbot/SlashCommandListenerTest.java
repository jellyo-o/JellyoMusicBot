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
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
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
    public void commandOptionsUseAutocomplete()
    {
        assertQueryAutocompleteEnabled("play");
        assertQueryAutocompleteEnabled("playtop");
        assertQueryAutocompleteEnabled("playnext");
        assertOptionAutocompleteEnabled("unlike", "target");
        assertSubcommandOptionAutocompleteEnabled("guess", "start", "playlist");
        assertSubcommandOptionAutocompleteEnabled("hostgame", "start", "playlist");
    }

    @Test
    public void userFacingPrefixCommandsHaveSlashEquivalents()
    {
        Set<String> names = new HashSet<>();
        for (SlashCommandData command : SlashCommandListener.buildSlashCommands())
            names.add(command.getName());

        String[] expected = {
                "about", "help", "ping", "settings",
                "play", "playtop", "playplaylist", "playlist", "like", "unlike", "liked", "nowplaying", "queue", "history", "skip", "remove", "shuffle", "seek",
                "lyrics", "correctlyrics", "guess", "hostgame", "g", "idk", "playlists", "search", "scsearch",
                "forceskip", "pause", "resume", "stop", "volume", "filter", "repeat", "loop", "autoplay", "radio", "skipto", "move", "playnext", "forceremove",
                "prefix", "setdj", "settc", "setvc", "setskip", "skipratio", "queuetype"
        };

        for (String command : expected)
            assertTrue("Missing slash command: " + command, names.contains(command));
    }

    @Test
    public void casinoGamesHaveSlashCommands()
    {
        Set<String> names = new HashSet<>();
        for (SlashCommandData command : SlashCommandListener.buildSlashCommands())
            names.add(command.getName());

        String[] games = {
                "gamble", "predict", "roulette", "wheel", "keno", "scratch",
                "double", "rps", "hilo", "crash", "mines", "blackjack"
        };
        for (String game : games)
            assertTrue("Missing casino slash command: " + game, names.contains(game));
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
    public void historyCommandHasExpectedSubcommands()
    {
        SlashCommandData command = SlashCommandListener.buildSlashCommands().stream()
                .filter(cmd -> "history".equals(cmd.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(command);
        Set<String> subcommands = command.getSubcommands().stream()
                .map(subcommand -> subcommand.getName())
                .collect(Collectors.toSet());

        assertTrue(subcommands.contains("session"));
        assertTrue(subcommands.contains("guild"));
        assertTrue(command.getOptions().isEmpty());
    }

    @Test
    public void guessMusicCommandsHaveExpectedShape()
    {
        SlashCommandData guess = findSlashCommand("guess");
        assertNotNull(guess);
        Set<String> subcommands = guess.getSubcommands().stream()
                .map(subcommand -> subcommand.getName())
                .collect(Collectors.toSet());

        String[] expected = {"start", "submit", "settings", "join", "leave", "status", "reveal", "stop", "hints", "highlight"};
        for(String subcommand : expected)
            assertTrue("Missing guess subcommand: " + subcommand, subcommands.contains(subcommand));

        SubcommandData start = guess.getSubcommands().stream()
                .filter(subcommand -> "start".equals(subcommand.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(start);
        assertTrue(hasOption(start.getOptions(), "known_percent"));
        assertTrue(hasOption(start.getOptions(), "timeout"));
        assertTrue(hasOption(start.getOptions(), "hints"));
        assertTrue(hasOption(start.getOptions(), "hint_interval"));
        assertTrue(hasOption(start.getOptions(), "hint_seconds"));
        assertTrue(hasOption(start.getOptions(), "hint_replays"));
        assertTrue(hasOption(start.getOptions(), "replay_interval"));
        assertTrue(hasOption(start.getOptions(), "buffer"));
        assertTrue(hasOption(start.getOptions(), "playlist"));
        assertTrue(hasOption(start.getOptions(), "artist"));

        SubcommandData submit = guess.getSubcommands().stream()
                .filter(subcommand -> "submit".equals(subcommand.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(submit);
        OptionData fullGuess = submit.getOptions().stream()
                .filter(option -> "answer".equals(option.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(fullGuess);
        assertTrue(fullGuess.isRequired());

        SubcommandData highlight = guess.getSubcommands().stream()
                .filter(subcommand -> "highlight".equals(subcommand.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(highlight);
        OptionData timestamp = highlight.getOptions().stream()
                .filter(option -> "timestamp".equals(option.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(timestamp);
        assertFalse(timestamp.isRequired());

        SlashCommandData shorthand = findSlashCommand("g");
        assertNotNull(shorthand);
        OptionData answer = shorthand.getOptions().stream()
                .filter(option -> "answer".equals(option.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(answer);
        assertTrue(answer.isRequired());

        SlashCommandData idk = findSlashCommand("idk");
        assertNotNull(idk);
        assertTrue(idk.getOptions().isEmpty());
    }

    @Test
    public void hostGameCommandHasExpectedShape()
    {
        SlashCommandData host = findSlashCommand("hostgame");
        assertNotNull(host);
        Set<String> subcommands = host.getSubcommands().stream()
                .map(subcommand -> subcommand.getName())
                .collect(Collectors.toSet());

        String[] expected = {"start", "add", "status", "join", "leave", "reveal", "stop"};
        for(String subcommand : expected)
            assertTrue("Missing hostgame subcommand: " + subcommand, subcommands.contains(subcommand));

        SubcommandData start = host.getSubcommands().stream()
                .filter(subcommand -> "start".equals(subcommand.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(start);
        // The hosted game is controlled by the host: an idle window ends it when songs run out, and a
        // private playlist can pre-load songs.
        assertTrue(hasOption(start.getOptions(), "idle"));
        assertTrue(hasOption(start.getOptions(), "playlist"));
        assertTrue(hasOption(start.getOptions(), "hints"));
        assertTrue(hasOption(start.getOptions(), "timeout"));

        // Privacy guarantee: songs are added through a modal (private), never a visible slash option, so
        // the "add" subcommand must expose no options at all.
        SubcommandData add = host.getSubcommands().stream()
                .filter(subcommand -> "add".equals(subcommand.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(add);
        assertTrue("hostgame add must not expose a public song option", add.getOptions().isEmpty());
    }

    @Test
    public void paginatedCommandsUseButtonsInsteadOfPageOptions()
    {
        SlashCommandData queue = findSlashCommand("queue");
        assertNotNull(queue);
        assertTrue(queue.getOptions().isEmpty());

        SlashCommandData liked = findSlashCommand("liked");
        assertNotNull(liked);
        assertFalse(hasOption(liked.getOptions(), "page"));

        SlashCommandData playlist = findSlashCommand("playlist");
        assertNotNull(playlist);
        SubcommandData view = playlist.getSubcommands().stream()
                .filter(subcommand -> "view".equals(subcommand.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(view);
        assertFalse(hasOption(view.getOptions(), "page"));
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
        assertOptionAutocompleteEnabled(commandName, "query");
    }

    private void assertOptionAutocompleteEnabled(String commandName, String optionName)
    {
        SlashCommandData command = findSlashCommand(commandName);

        assertNotNull(command);
        OptionData option = command.getOptions().stream()
                .filter(candidate -> optionName.equals(candidate.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(option);
        assertTrue(option.isAutoComplete());
    }

    private void assertSubcommandOptionAutocompleteEnabled(String commandName, String subcommandName, String optionName)
    {
        SlashCommandData command = findSlashCommand(commandName);

        assertNotNull(command);
        SubcommandData subcommand = command.getSubcommands().stream()
                .filter(candidate -> subcommandName.equals(candidate.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(subcommand);
        OptionData option = subcommand.getOptions().stream()
                .filter(candidate -> optionName.equals(candidate.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(option);
        assertTrue(option.isAutoComplete());
    }

    private SlashCommandData findSlashCommand(String commandName)
    {
        return SlashCommandListener.buildSlashCommands().stream()
                .filter(cmd -> commandName.equals(cmd.getName()))
                .findFirst()
                .orElse(null);
    }

    private boolean hasOption(List<OptionData> options, String optionName)
    {
        return options.stream().anyMatch(option -> optionName.equals(option.getName()));
    }
}
