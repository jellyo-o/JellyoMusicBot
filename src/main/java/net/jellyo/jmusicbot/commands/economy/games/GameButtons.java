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
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single dispatch entry point for every casino game button, plugged into the
 * chain of responsibility in {@code Listener.onButtonInteraction}. Buttons carry
 * a {@code jmb-game:<action>} custom id; the owning session is found by the
 * clicked message's id (so the id needn't embed it), and only the game's owner
 * may press.
 */
public final class GameButtons
{
    private static final Logger LOG = LoggerFactory.getLogger(GameButtons.class);
    public static final String PREFIX = "jmb-game:";

    private GameButtons() {}

    /** Builds a custom id for a game button action. */
    public static String id(String action)
    {
        return PREFIX + action;
    }

    /** @return true if this was a game button (handled), false to let the next handler try. */
    public static boolean handle(Bot bot, ButtonInteractionEvent event)
    {
        String componentId = event.getComponentId();
        if(componentId == null || !componentId.startsWith(PREFIX))
            return false;
        String action = componentId.substring(PREFIX.length());
        GameSession session = bot.getGameSessions().get(event.getMessageIdLong());
        if(session == null)
        {
            event.reply(bot.getConfig().getWarning() + " This game has already ended.")
                    .setEphemeral(true).queue(x -> {}, t -> {});
            return true;
        }
        if(!session.canPress(event.getUser().getIdLong(), action))
        {
            event.reply(bot.getConfig().getError() + " This button isn't for you.")
                    .setEphemeral(true).queue(x -> {}, t -> {});
            return true;
        }
        try
        {
            session.onButton(action, event);
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Game button handler failed for action {}", action, ex);
            if(!event.isAcknowledged())
                event.deferEdit().queue(x -> {}, t -> {});
        }
        return true;
    }
}
