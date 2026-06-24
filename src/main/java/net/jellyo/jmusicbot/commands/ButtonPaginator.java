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
package com.jagrosh.jmusicbot.commands;

import com.jagrosh.jmusicbot.Bot;
import java.util.Collections;
import java.util.List;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

public final class ButtonPaginator
{
    private static final String BUTTON_PREFIX = "jmb-page:";

    private ButtonPaginator()
    {
    }

    public static List<ActionRow> controls(String namespace, String state, long guildId, long userId, int page, int pages)
    {
        if(pages <= 1)
            return Collections.emptyList();

        int safePage = Math.max(1, Math.min(page, pages));
        return Collections.singletonList(ActionRow.of(
                Button.secondary(buttonId(namespace, state, guildId, userId, Math.max(1, safePage - 1)), "Previous")
                        .withDisabled(safePage <= 1),
                Button.secondary(buttonId(namespace, state, guildId, userId, Math.min(pages, safePage + 1)), "Next")
                        .withDisabled(safePage >= pages)));
    }

    public static Request parse(String componentId)
    {
        if(componentId == null || !componentId.startsWith(BUTTON_PREFIX))
            return null;

        String[] parts = componentId.substring(BUTTON_PREFIX.length()).split(":");
        if(parts.length != 5)
            return null;

        try
        {
            return new Request(parts[0], parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3]),
                    Math.max(1, Integer.parseInt(parts[4])));
        }
        catch(NumberFormatException ex)
        {
            return null;
        }
    }

    public static boolean isNamespace(Request request, String namespace)
    {
        return request != null && namespace.equals(request.getNamespace());
    }

    public static boolean isAuthorized(Bot bot, ButtonInteractionEvent event, Request request, String label)
    {
        if(!isInGuild(bot, event, request, label))
            return false;

        if(event.getUser().getIdLong() != request.getUserId())
        {
            event.reply(bot.getConfig().getError() + " Only the user who opened this " + label + " can change pages.")
                    .setEphemeral(true).queue();
            return false;
        }

        return true;
    }

    /**
     * Guild-scoped authorization: any member of the guild the control was created in
     * may use it (unlike {@link #isAuthorized}, which is restricted to the opener).
     */
    public static boolean isInGuild(Bot bot, ButtonInteractionEvent event, Request request, String label)
    {
        if(event.getGuild() == null || event.getGuild().getIdLong() != request.getGuildId())
        {
            event.reply(bot.getConfig().getError() + " This " + label + " control cannot be used here.")
                    .setEphemeral(true).queue();
            return false;
        }
        return true;
    }

    public static int pageCount(int totalItems, int itemsPerPage)
    {
        if(itemsPerPage <= 0)
            throw new IllegalArgumentException("itemsPerPage must be greater than zero");
        return Math.max(1, (int)Math.ceil((double)Math.max(0, totalItems) / itemsPerPage));
    }

    public static int clampPage(int requestedPage, int totalItems, int itemsPerPage)
    {
        return Math.max(1, Math.min(requestedPage, pageCount(totalItems, itemsPerPage)));
    }

    public static int offset(int page, int itemsPerPage)
    {
        if(itemsPerPage <= 0)
            throw new IllegalArgumentException("itemsPerPage must be greater than zero");
        return (Math.max(1, page) - 1) * itemsPerPage;
    }

    public static <T> List<T> pageItems(List<T> items, int page, int itemsPerPage)
    {
        int safePage = clampPage(page, items.size(), itemsPerPage);
        int start = offset(safePage, itemsPerPage);
        int end = Math.min(start + itemsPerPage, items.size());
        return items.subList(start, end);
    }

    private static String buttonId(String namespace, String state, long guildId, long userId, int page)
    {
        return BUTTON_PREFIX + namespace + ":" + state + ":" + guildId + ":" + userId + ":" + page;
    }

    public static final class Request
    {
        private final String namespace;
        private final String state;
        private final long guildId;
        private final long userId;
        private final int page;

        private Request(String namespace, String state, long guildId, long userId, int page)
        {
            this.namespace = namespace;
            this.state = state;
            this.guildId = guildId;
            this.userId = userId;
            this.page = page;
        }

        public String getNamespace()
        {
            return namespace;
        }

        public String getState()
        {
            return state;
        }

        public long getGuildId()
        {
            return guildId;
        }

        public long getUserId()
        {
            return userId;
        }

        public int getPage()
        {
            return page;
        }
    }
}
