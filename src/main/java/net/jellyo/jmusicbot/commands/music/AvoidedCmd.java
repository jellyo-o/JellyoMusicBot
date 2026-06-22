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

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AvoidStore;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import java.awt.Color;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * Lists the songs currently on this server's avoid list.
 */
public class AvoidedCmd extends Command implements UnifiedCommand
{
    private static final int MAX_LISTED = 40;
    private final Bot bot;

    public AvoidedCmd(Bot bot)
    {
        this.bot = bot;
        this.name = "avoided";
        this.help = "lists the songs avoided on this server";
        this.guildOnly = true;
        this.category = new Category("Music");
        this.aliases = bot.getConfig().getAliases(this.name);
    }

    @Override
    protected void execute(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext ctx)
    {
        AvoidStore store = bot.getAvoidStore();
        if(store == null || ctx.getGuild() == null)
        {
            ctx.replyError("The avoid list is unavailable right now.");
            return;
        }
        List<String> avoided = store.list(ctx.getGuild().getIdLong());
        if(avoided.isEmpty())
        {
            ctx.reply("🚫 No songs are avoided on this server. Use `avoid` while a song is playing to block it.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        int shown = Math.min(avoided.size(), MAX_LISTED);
        for(int i = 0; i < shown; i++)
            sb.append('`').append(i + 1).append(".` ").append(avoided.get(i)).append('\n');
        if(avoided.size() > shown)
            sb.append("…and ").append(avoided.size() - shown).append(" more");

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(new Color(0xE74C3C));
        eb.setTitle("🚫 Avoided Songs (" + avoided.size() + ")");
        eb.setDescription(sb.toString());
        eb.setFooter("Autoplay will never pick these • use unavoid <song> to remove", null);
        ctx.reply(new MessageCreateBuilder().setEmbeds(eb.build()).build());
    }
}
