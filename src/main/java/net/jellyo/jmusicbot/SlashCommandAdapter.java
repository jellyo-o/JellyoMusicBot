package com.jagrosh.jmusicbot;

import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.command.CommandEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.ChannelType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

/**
 * A simple adapter to allow existing {@link CommandEvent}-based commands to be reused for slash commands.
 */
public class SlashCommandAdapter extends CommandEvent
{
    private final SlashCommandInteractionEvent slashEvent;

    public SlashCommandAdapter(SlashCommandInteractionEvent event, String args, CommandClient client)
    {
        super((MessageReceivedEvent) null, args, client);
        this.slashEvent = event;
    }

    @Override
    public Guild getGuild()
    {
        return slashEvent.getGuild();
    }

    @Override
    public Member getMember()
    {
        return slashEvent.getMember();
    }

    @Override
    public Member getSelfMember()
    {
        return slashEvent.getGuild() == null ? null : slashEvent.getGuild().getSelfMember();
    }

    @Override
    public User getAuthor()
    {
        return slashEvent.getUser();
    }

    @Override
    public JDA getJDA()
    {
        return slashEvent.getJDA();
    }

    @Override
    public MessageChannel getChannel()
    {
        MessageChannelUnion channel = slashEvent.getChannel();
        return channel == null ? null : channel.asGuildMessageChannel();
    }

    @Override
    public TextChannel getTextChannel()
    {
        MessageChannelUnion channel = slashEvent.getChannel();
        return channel == null ? null : channel.asTextChannel();
    }

    @Override
    public ChannelType getChannelType()
    {
        return slashEvent.getChannelType();
    }

    @Override
    public void reply(String message)
    {
        slashEvent.reply(message).queue();
    }

    @Override
    public void replyWarning(String message)
    {
        slashEvent.reply(getClient().getWarning() + message).queue();
    }

    @Override
    public void replySuccess(String message)
    {
        slashEvent.reply(getClient().getSuccess() + message).queue();
    }

    @Override
    public void replyError(String message)
    {
        slashEvent.reply(getClient().getError() + message).queue();
    }

    @Override
    public void replyInDm(String message)
    {
        slashEvent.getUser().openPrivateChannel().queue(pc -> pc.sendMessage(message).queue());
    }

    @Override
    public void replyInDm(String message, java.util.function.Consumer<Message> success, java.util.function.Consumer<Throwable> failure)
    {
        slashEvent.getUser().openPrivateChannel().queue(pc -> pc.sendMessage(message).queue(success, failure), failure);
    }

    @Override
    public boolean isFromType(ChannelType type)
    {
        return getChannelType() == type;
    }

    @Override
    public Message getMessage()
    {
        return null;
    }

    public OptionMapping getOption(String name)
    {
        return slashEvent.getOption(name);
    }
}
