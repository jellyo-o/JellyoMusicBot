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
package com.jagrosh.jmusicbot.commands.economy;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.LotteryService;
import com.jagrosh.jmusicbot.economy.LotteryService.BuyResult;
import com.jagrosh.jmusicbot.economy.LotteryService.LastDraw;
import com.jagrosh.jmusicbot.economy.LotteryStore.DrawInfo;
import java.time.Instant;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * The single, bot-wide lottery: buy tickets into one global pot, or check it. A
 * bot-owner-scheduled draw picks one ticket-weighted winner who takes the pot.
 */
public class LotteryCmd extends EconomyCommand
{
    public LotteryCmd(Bot bot)
    {
        super(bot, "lottery", "buy tickets or check the global lottery", "[buy <count> | info]");
        this.aliases = bot.getConfig().getAliases("lottery").length == 0
                ? new String[]{"raffle"}
                : bot.getConfig().getAliases("lottery");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        LotteryService lottery = bot.getLotteryService();
        if(lottery == null || !lottery.isEnabled())
        {
            ctx.replyError("The lottery is turned off.");
            return;
        }
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        String action = tokens.length > 0 && !tokens[0].isEmpty() ? tokens[0].toLowerCase() : "info";

        if(action.equals("buy"))
        {
            int count = 1;
            if(tokens.length > 1 && tokens[1].matches("\\d{1,3}"))
                count = Integer.parseInt(tokens[1]);
            BuyResult result = lottery.buy(ctx.getAuthor(), count);
            switch(result.getStatus())
            {
                case DISABLED:
                    ctx.replyError("The lottery is turned off.");
                    return;
                case CAP_REACHED:
                    ctx.replyError("You can hold at most **" + result.getCap() + "** tickets per draw (you have "
                            + result.getHeld() + "). This keeps any one player from buying the odds.");
                    return;
                case INSUFFICIENT:
                    ctx.replyError("You need " + EconomyService.coins(result.getCost()) + " for that many tickets.");
                    return;
                case BOUGHT:
                default:
                    ctx.reply(new MessageCreateBuilder().setEmbeds(
                            infoEmbed("🎟️ Tickets bought",
                                    "You bought **" + result.getTickets() + "** ticket(s) for "
                                            + EconomyService.coins(result.getCost()) + ".", result.getInfo())).build());
            }
            return;
        }

        DrawInfo info = lottery.info(ctx.getAuthor().getIdLong());
        if(info == null)
        {
            ctx.reply(new MessageCreateBuilder().setEmbeds(dormantEmbed(lottery)).build());
            return;
        }
        ctx.reply(new MessageCreateBuilder().setEmbeds(infoEmbed("🎟️ Global Lottery", null, info)).build());
    }

    private net.dv8tion.jda.api.entities.MessageEmbed infoEmbed(String title, String header, DrawInfo info)
    {
        long secondsLeft = Math.max(0, info.getDrawEpoch() - Instant.now().getEpochSecond());
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(ECONOMY_COLOR);
        eb.setTitle(title);
        if(header != null)
            eb.setDescription(header);
        eb.addField("💰 Pot", EconomyService.coins(info.getPot()), true);
        eb.addField("🎫 Your tickets", info.getUserTickets() + " / " + info.getTotalTickets(), true);
        eb.addField("⏳ Draw in", formatDuration(secondsLeft), true);
        eb.setFooter(footer(), null);
        return eb.build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed dormantEmbed(LotteryService lottery)
    {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(ECONOMY_COLOR);
        eb.setTitle("🎟️ Global Lottery");
        eb.setDescription("No draw is running yet — buy a ticket with `lottery buy` to start the pot!");
        LastDraw last = lottery.lastDraw();
        if(last != null)
            eb.addField("Last draw", "1 winner took **" + EconomyService.coins(last.getPot()) + "** from "
                    + last.getParticipants() + " player(s) and " + last.getTotalTickets() + " tickets.", false);
        eb.setFooter(footer(), null);
        return eb.build();
    }

    private String footer()
    {
        LotteryService lottery = bot.getLotteryService();
        return "Tickets cost " + EconomyService.coins(lottery.ticketPrice()) + " each • max "
                + LotteryService.MAX_TICKETS_PER_USER + " per draw • one winner takes the pot";
    }
}
