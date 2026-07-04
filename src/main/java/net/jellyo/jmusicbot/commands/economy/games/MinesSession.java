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
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import com.jagrosh.jmusicbot.economy.games.MinesGame;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/**
 * "Mines": reveal safe tiles on a 5x4 grid to grow the multiplier; hit one of the
 * hidden mines and lose it all. Cash out (or time out) to bank the current pot.
 */
public class MinesSession extends GameSession
{
    private static final int COLS = 5;
    private static final int ROWS = 4;
    /** Conservative sizing so a single game can't easily mint near the return cap. */
    public static final double SIZING_MULTIPLIER = 50.0;

    private final boolean[] mines;
    private final boolean[] revealed = new boolean[MinesGame.TILES];
    private final int bombs;
    private int revealedCount;

    public MinesSession(Bot bot, long ownerId, String ownerName, long guildId, long channelId,
                        long wager, String escrowId, int bombs, Random rng)
    {
        super(bot, ownerId, ownerName, guildId, channelId, wager, escrowId);
        this.bombs = bombs;
        this.mines = MinesGame.placeBombs(bombs, rng);
    }

    public int getBombs() { return bombs; }

    private long pot()
    {
        return (long) Math.floor(wager * MinesGame.multiplier(bombs, revealedCount));
    }

    @Override
    public void onButton(String action, ButtonInteractionEvent event)
    {
        if(action.equals("mines:cash"))
        {
            long snapshot;
            synchronized(this)
            {
                if(isSettled() || revealedCount < 1) { ackIfNeeded(event); return; }
                snapshot = pot();
            }
            GameOutcome outcome = settleOnce(snapshot);
            if(outcome != null)
                editResult(event, resultEmbed(outcome, "You cashed out with **" + revealedCount + "** safe tiles."));
            else
                ackIfNeeded(event);
            return;
        }
        if(!action.startsWith("mines:reveal:"))
        {
            ackIfNeeded(event);
            return;
        }
        int index;
        try
        {
            index = Integer.parseInt(action.substring("mines:reveal:".length()));
        }
        catch(NumberFormatException ex)
        {
            ackIfNeeded(event);
            return;
        }
        boolean boom;
        boolean cleared;
        long snapshotPot;
        synchronized(this)
        {
            if(isSettled() || index < 0 || index >= MinesGame.TILES || revealed[index])
            {
                ackIfNeeded(event);
                return;
            }
            if(mines[index])
            {
                boom = true;
                cleared = false;
                snapshotPot = 0;
            }
            else
            {
                revealed[index] = true;
                revealedCount++;
                boom = false;
                cleared = revealedCount >= MinesGame.safeTiles(bombs);
                snapshotPot = pot();
            }
        }
        if(boom)
        {
            GameOutcome outcome = settleOnce(0);
            if(outcome != null)
                editPanel(event, resultEmbed(outcome, "💥 You hit a mine!"), grid(true));
            else
                ackIfNeeded(event);
        }
        else if(cleared)
        {
            GameOutcome outcome = settleOnce(snapshotPot);
            if(outcome != null)
                editPanel(event, resultEmbed(outcome, "🏆 You cleared every safe tile!"), grid(true));
            else
                ackIfNeeded(event);
        }
        else
        {
            rearmTimeout(90_000);
            editPanel(event, panel(), grid(false));
        }
    }

    @Override
    protected void onTimeout()
    {
        long snapshot;
        synchronized(this)
        {
            if(isSettled())
                return;
            snapshot = pot();
        }
        GameOutcome outcome = settleOnce(snapshot);
        if(outcome != null)
            editResultById(resultEmbed(outcome, "⏱️ Timed out — cashed out your " + revealedCount + " tiles."));
    }

    public MessageEmbed panel()
    {
        double next = MinesGame.multiplier(bombs, revealedCount);
        return GameEmbeds.live("💣 Mines",
                "Bombs: **" + bombs + "** • revealed: **" + revealedCount + "**\n"
                        + "Cash-out value: **" + EconomyService.coins(pot()) + "** (" + fmt(next) + "x)\n"
                        + "Pick a tile — avoid the mines!");
    }

    private MessageEmbed resultEmbed(GameOutcome outcome, String detail)
    {
        return GameEmbeds.result("💣 Mines", detail, outcome, wager);
    }

    /** The button grid; {@code revealAll} exposes every tile for the final board. */
    public List<ActionRow> grid(boolean revealAll)
    {
        List<ActionRow> rows = new ArrayList<>();
        for(int r = 0; r < ROWS; r++)
        {
            List<Button> row = new ArrayList<>();
            for(int c = 0; c < COLS; c++)
            {
                int i = r * COLS + c;
                row.add(tileButton(i, revealAll));
            }
            rows.add(ActionRow.of(row));
        }
        if(!revealAll)
        {
            rows.add(ActionRow.of(Button.primary(GameButtons.id("mines:cash"),
                    "💰 Cash Out (" + fmt(MinesGame.multiplier(bombs, revealedCount)) + "x)")
                    .withDisabled(revealedCount < 1)));
        }
        return rows;
    }

    private Button tileButton(int i, boolean revealAll)
    {
        String id = GameButtons.id("mines:reveal:" + i);
        if(revealAll)
        {
            if(mines[i])
                return Button.danger(id, Emoji.fromUnicode("💣")).asDisabled();
            return Button.success(id, Emoji.fromUnicode(revealed[i] ? "💎" : "⬜")).asDisabled();
        }
        if(revealed[i])
            return Button.success(id, Emoji.fromUnicode("💎")).asDisabled();
        return Button.secondary(id, Emoji.fromUnicode("🟦"));
    }

    private static String fmt(double m)
    {
        return String.format("%.2f", m);
    }
}
