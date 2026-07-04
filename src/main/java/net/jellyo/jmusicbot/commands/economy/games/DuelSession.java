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
package com.jagrosh.jmusicbot.commands.economy.games;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.economy.EconomyService;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/**
 * A player-vs-player coinflip duel. The challenger's ante is escrowed when the duel
 * is created; the opponent may accept (which escrows theirs and flips) or decline,
 * and the challenger may cancel. It is zero-sum — the winner takes both antes, no
 * house edge — so it settles via direct transfers, not the house settlement.
 */
public class DuelSession extends GameSession
{
    private final long opponentId;
    private final String opponentName;
    private final long ante;

    public DuelSession(Bot bot, long challengerId, String challengerName, long guildId, long channelId,
                       long ante, String escrowId, long opponentId, String opponentName)
    {
        super(bot, challengerId, challengerName, guildId, channelId, ante, escrowId);
        this.opponentId = opponentId;
        this.opponentName = opponentName;
        this.ante = ante;
    }

    @Override
    public boolean canPress(long userId, String action)
    {
        if(action.equals("duel:cancel"))
            return userId == ownerId;
        return userId == opponentId; // accept / decline
    }

    @Override
    public void onButton(String action, ButtonInteractionEvent event)
    {
        switch(action)
        {
            case "duel:cancel": abort(event, ownerName + " cancelled the duel."); break;
            case "duel:decline": abort(event, opponentName + " declined the duel."); break;
            case "duel:accept": accept(event); break;
            default: ackIfNeeded(event);
        }
    }

    /** Refunds the challenger's ante and ends the duel (cancel / decline / timeout). */
    private void abort(ButtonInteractionEvent event, String reason)
    {
        if(!claimResolution())
        {
            ackIfNeeded(event);
            return;
        }
        bot.getEconomyService().resolveEscrow(escrowId, ante); // refund the challenger's escrowed ante + clear its row
        closeClaimed();
        editResult(event, endEmbed(reason + " Ante refunded."));
    }

    private void accept(ButtonInteractionEvent event)
    {
        // Friendly pre-check (non-atomic) so a broke opponent doesn't consume the duel.
        if(bot.getEconomyService().getBalance(opponentId) < ante)
        {
            event.reply(bot.getConfig().getError() + " You can't afford this duel's "
                            + EconomyService.coins(ante) + " ante.")
                    .setEphemeral(true).queue(x -> {}, t -> {});
            return;
        }
        if(!claimResolution())
        {
            ackIfNeeded(event);
            return;
        }
        // Escrow the opponent's ante (debit + crash-recovery record). Only the resolution winner does this,
        // so a double-click can't double-charge.
        String oppEscrow = bot.getEconomyService().escrow(opponentId, ante, "duel");
        if(oppEscrow == null)
        {
            bot.getEconomyService().resolveEscrow(escrowId, ante); // refund the challenger's escrowed ante
            closeClaimed();
            editResult(event, endEmbed(opponentName + " couldn't cover the ante — duel off. Ante refunded."));
            return;
        }
        boolean challengerWins = ThreadLocalRandom.current().nextBoolean();
        long winnerId = challengerWins ? ownerId : opponentId;
        String winnerName = challengerWins ? ownerName : opponentName;
        long pot = ante * 2;
        // Clear both antes' escrows and pay the winner the pot in one transaction — crash-safe even if the
        // bot dies between the opponent's debit and the winner's credit.
        bot.getEconomyService().settleDuel(escrowId, oppEscrow, winnerId, pot);
        bot.getEconomyService().recordGamePlayed(ownerId, channel());
        bot.getEconomyService().recordGamePlayed(opponentId, channel());
        closeClaimed();

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(GameEmbeds.WIN);
        eb.setTitle("⚔️ Duel");
        eb.setDescription("🪙 The coin favours **" + winnerName + "**!\n<@" + winnerId + "> wins the pot of **"
                + EconomyService.coins(pot) + "** (net +" + EconomyService.coins(ante) + ").");
        editResult(event, eb.build());
    }

    @Override
    protected void onTimeout()
    {
        if(!claimResolution())
            return;
        bot.getEconomyService().resolveEscrow(escrowId, ante); // refund the challenger's escrowed ante + clear its row
        closeClaimed();
        editResultById(endEmbed("The duel expired — " + opponentName + " didn't respond. Ante refunded."));
    }

    private MessageEmbed endEmbed(String reason)
    {
        return new EmbedBuilder().setColor(GameEmbeds.NEUTRAL).setTitle("⚔️ Duel").setDescription(reason).build();
    }

    public MessageEmbed panel()
    {
        return GameEmbeds.live("⚔️ Duel Challenge",
                "<@" + ownerId + "> challenges <@" + opponentId + "> to a coinflip duel for **"
                        + EconomyService.coins(ante) + "**!\nWinner takes the **" + EconomyService.coins(ante * 2)
                        + "** pot. " + opponentName + ", do you accept?");
    }

    public List<ActionRow> buttons()
    {
        return List.of(ActionRow.of(
                Button.success(GameButtons.id("duel:accept"), "Accept"),
                Button.danger(GameButtons.id("duel:decline"), "Decline"),
                Button.secondary(GameButtons.id("duel:cancel"), "Cancel")));
    }
}
