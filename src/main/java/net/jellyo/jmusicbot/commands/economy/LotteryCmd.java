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
import com.jagrosh.jmusicbot.economy.LotteryStore.DrawInfo;
import java.time.Instant;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/** Buy tickets for the per-server lottery, or check the current pot. */
public class LotteryCmd extends EconomyCommand
{
    public LotteryCmd(Bot bot)
    {
        super(bot, "lottery", "buy tickets or check the server lottery", "[buy <count> | info]");
        this.aliases = bot.getConfig().getAliases("lottery").length == 0
                ? new String[]{"raffle"}
                : bot.getConfig().getAliases("lottery");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        if(ctx.getGuild() == null)
        {
            ctx.replyError("The lottery is per-server — use it in a server.");
            return;
        }
        LotteryService lottery = bot.getLotteryService();
        if(lottery == null || !lottery.isEnabled())
        {
            ctx.replyError("The lottery is unavailable right now.");
            return;
        }
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        String action = tokens.length > 0 && !tokens[0].isEmpty() ? tokens[0].toLowerCase() : "info";
        long guildId = ctx.getGuild().getIdLong();

        if(action.equals("buy"))
        {
            int count = 1;
            if(tokens.length > 1 && tokens[1].matches("\\d{1,3}"))
                count = Integer.parseInt(tokens[1]);
            BuyResult result = lottery.buy(guildId, ctx.getChannel().getIdLong(), ctx.getAuthor(), count);
            switch(result.getStatus())
            {
                case DISABLED:
                    ctx.replyError("The lottery is unavailable right now.");
                    return;
                case TOO_MANY:
                    ctx.replyError("You can buy at most " + LotteryService.MAX_TICKETS_PER_BUY + " tickets at once.");
                    return;
                case INSUFFICIENT:
                    ctx.replyError("You need " + EconomyService.coins(result.getCost()) + " for that many tickets.");
                    return;
                case BOUGHT:
                default:
                    ctx.reply(new MessageCreateBuilder().setEmbeds(
                            infoEmbed("🎟️ Tickets bought", "You bought **" + result.getTickets() + "** ticket(s) for "
                                    + EconomyService.coins(result.getCost()) + ".", result.getInfo())).build());
            }
            return;
        }

        DrawInfo info = lottery.info(guildId, ctx.getAuthor().getIdLong());
        if(info == null)
        {
            ctx.replyWarning("No lottery is running here yet. Start one with `lottery buy`! "
                    + "Tickets cost " + EconomyService.coins(LotteryService.TICKET_PRICE) + " each.");
            return;
        }
        ctx.reply(new MessageCreateBuilder().setEmbeds(
                infoEmbed("🎟️ Server Lottery", null, info)).build());
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
        eb.setFooter("Tickets cost " + EconomyService.coins(LotteryService.TICKET_PRICE) + " each", null);
        return eb.build();
    }
}
