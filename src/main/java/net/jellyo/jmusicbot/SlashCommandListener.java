package com.jagrosh.jmusicbot;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.command.CommandEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Registers slash commands and forwards interactions to existing command implementations.
 */
public class SlashCommandListener extends ListenerAdapter
{
    private final CommandClient commandClient;

    public SlashCommandListener(CommandClient commandClient)
    {
        this.commandClient = commandClient;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event)
    {
        CommandListUpdateAction updateAction = event.getJDA().updateCommands();
        commandClient.getCommands().forEach(command -> updateAction.addCommands(buildSlashData(command)));
        updateAction.queue();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event)
    {
        Command target = commandClient.getCommands().stream()
                .filter(cmd -> cmd.getName().equalsIgnoreCase(event.getName()) || Arrays.asList(cmd.getAliases() == null ? new String[0] : cmd.getAliases()).contains(event.getName()))
                .findFirst()
                .orElse(null);
        if(target == null)
            return;

        String args = event.getOption("input", "", option -> option.getAsString());
        CommandEvent adapter = new SlashCommandAdapter(event, args, commandClient);
        target.run(adapter);
    }

    private SlashCommandData buildSlashData(Command command)
    {
        SlashCommandData data = Commands.slash(command.getName(), command.getHelp() == null ? "No description" : command.getHelp());
        data.addOption(OptionType.STRING, "input", command.getArguments() == null ? "Command arguments" : command.getArguments(), false);
        return data;
    }
}
