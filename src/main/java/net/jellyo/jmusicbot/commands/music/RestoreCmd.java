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
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.recovery.CrashRecoveryService;
import com.jagrosh.jmusicbot.recovery.CrashRecoveryService.RestoreOffer;
import java.util.Collections;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restores the queue saved before the bot crashed, restarted or everyone left
 * the voice channel. The saved tracks are added back into the live queue (they
 * play immediately if nothing is currently playing). Anyone in the guild can run
 * this, or press the Restore button posted when a song is requested.
 */
public class RestoreCmd extends MusicCommand implements UnifiedCommand
{
    private static final Logger LOG = LoggerFactory.getLogger(RestoreCmd.class);

    /** Custom-id prefixes for the offer buttons. {@code <prefix><guildId>}. */
    private static final String RESTORE_PREFIX = "jmb-restore:";
    private static final String DISMISS_PREFIX = "jmb-restore-dismiss:";

    public RestoreCmd(Bot bot)
    {
        super(bot);
        this.name = "restore";
        this.help = "restores the queue saved before a crash, restart or everyone leaving";
        this.arguments = "";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
        this.blockDuringGuessMusic = true;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext ctx)
    {
        if(bot.getCrashRecoveryService() == null || !bot.getCrashRecoveryService().isEnabled())
        {
            ctx.replyError("Crash recovery is not available right now.");
            return;
        }
        int count = bot.getCrashRecoveryService().restore(ctx.getGuild());
        if(count < 0)
            ctx.replyWarning("Music is already playing — stop or clear the queue first, then run `restore` again.");
        else if(count == 0)
            ctx.replyWarning("There's nothing to restore — no saved queue was found.");
        else
            ctx.replySuccess("♻️ Restoring **" + count + "** track" + (count == 1 ? "" : "s")
                    + " from your saved queue…");
    }

    /**
     * If a saved queue is pending for this guild, posts a non-ephemeral offer with
     * Restore/Dismiss buttons to the channel. Capturing the offer moves the saved
     * queue into a durable pending slot, so the song being requested can play
     * without losing it. No-op when there is nothing to offer.
     */
    public static void sendOfferIfPending(Bot bot, Guild guild, MessageChannel channel)
    {
        CrashRecoveryService service = bot.getCrashRecoveryService();
        if(service == null || guild == null || channel == null)
            return;
        RestoreOffer offer = service.captureRestoreOffer(guild);
        if(offer == null)
            return;
        channel.sendMessage(buildOfferMessage(bot, guild.getIdLong(), offer)).queue(
                m -> {},
                err -> LOG.debug("Failed to post restore offer in guild {}", guild.getId(), err));
    }

    private static MessageCreateData buildOfferMessage(Bot bot, long guildId, RestoreOffer offer)
    {
        return new MessageCreateBuilder()
                .setContent(offer.describe() + "\nPress **Restore** (or run `/restore`) to add it back to the queue."
                        + " This offer expires in " + (CrashRecoveryService.PENDING_TTL_SECONDS / 60) + " minutes.")
                .setComponents(ActionRow.of(
                        Button.primary(RESTORE_PREFIX + guildId,
                                "♻️ Restore " + offer.getCount() + " track" + (offer.getCount() == 1 ? "" : "s")),
                        Button.secondary(DISMISS_PREFIX + guildId, "Dismiss")))
                .build();
    }

    /**
     * Handles the Restore / Dismiss buttons on a restore offer. Usable by anyone
     * in the guild the offer belongs to.
     *
     * @return true if this event was a restore-offer button (handled), false to
     *         let other handlers try.
     */
    public static boolean handleButtonInteraction(Bot bot, ButtonInteractionEvent event)
    {
        String id = event.getComponentId();
        if(id == null)
            return false;
        boolean dismiss = id.startsWith(DISMISS_PREFIX);
        boolean restore = !dismiss && id.startsWith(RESTORE_PREFIX);
        if(!dismiss && !restore)
            return false;

        long guildId = parseGuildId(id, dismiss ? DISMISS_PREFIX : RESTORE_PREFIX);
        if(event.getGuild() == null || event.getGuild().getIdLong() != guildId)
        {
            event.reply(bot.getConfig().getError() + " This restore control cannot be used here.")
                    .setEphemeral(true).queue();
            return true;
        }

        CrashRecoveryService service = bot.getCrashRecoveryService();
        if(service == null || !service.isEnabled())
        {
            event.reply(bot.getConfig().getError() + " Crash recovery is not available right now.")
                    .setEphemeral(true).queue();
            return true;
        }

        if(dismiss)
        {
            service.discardPending(event.getGuild());
            event.editMessage(bot.getConfig().getWarning() + " Saved queue dismissed.")
                    .setComponents(Collections.emptyList()).queue();
            return true;
        }

        // Don't make anyone join voice for an offer that has already expired or been used.
        CrashRecoveryService.PendingState state = service.pendingRestoreState(event.getGuild());
        if(state != CrashRecoveryService.PendingState.FRESH)
        {
            String msg = state == CrashRecoveryService.PendingState.EXPIRED
                    ? bot.getConfig().getWarning() + " This restore offer has expired."
                    : bot.getConfig().getWarning()
                            + " There's nothing left to restore — it may already have been brought back.";
            event.editMessage(msg).setComponents(Collections.emptyList()).queue();
            return true;
        }

        if(!ensureConnected(bot, event))
            return true;

        // Acknowledge the interaction immediately, then do the (fast, non-blocking) restore work
        // so a slow database round-trip can never blow the 3-second interaction window.
        event.deferEdit().queue();
        int count = service.restorePending(event.getGuild());
        String result = count > 0
                ? bot.getConfig().getSuccess() + " ♻️ Restoring **" + count + "** track"
                        + (count == 1 ? "" : "s") + " from the saved queue…"
                : bot.getConfig().getWarning()
                        + " There's nothing left to restore — it may already have been brought back.";
        if(count > 0)
            LOG.info("Restored {} tracks via button in guild {} ({})",
                    count, event.getGuild().getName(), event.getGuild().getId());
        event.getHook().editOriginal(new MessageEditBuilder()
                .setContent(result)
                .setComponents(Collections.emptyList())
                .build()).queue();
        return true;
    }

    /** Ensures the bot is in voice so restored tracks can play, joining the clicker's channel if needed. */
    private static boolean ensureConnected(Bot bot, ButtonInteractionEvent event)
    {
        Guild guild = event.getGuild();
        GuildVoiceState selfState = guild.getSelfMember().getVoiceState();
        if(selfState != null && selfState.inAudioChannel())
            return true;

        Member member = event.getMember();
        GuildVoiceState userState = member == null ? null : member.getVoiceState();
        if(userState == null || !userState.inAudioChannel())
        {
            event.reply(bot.getConfig().getError() + " Join a voice channel first, then press Restore.")
                    .setEphemeral(true).queue();
            return false;
        }

        AudioChannel target = userState.getChannel();
        try
        {
            guild.getAudioManager().openAudioConnection(target);
            return true;
        }
        catch(PermissionException ex)
        {
            LOG.warn("Failed to connect for restore button in guild {} ({}) to channel {}",
                    guild.getName(), guild.getId(), target.getId(), ex);
            event.reply(bot.getConfig().getError() + " I am unable to connect to " + target.getAsMention() + "!")
                    .setEphemeral(true).queue();
            return false;
        }
    }

    private static long parseGuildId(String id, String prefix)
    {
        try
        {
            return Long.parseLong(id.substring(prefix.length()));
        }
        catch(NumberFormatException ex)
        {
            return -1;
        }
    }
}
