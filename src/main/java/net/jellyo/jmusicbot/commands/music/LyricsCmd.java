/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.lyrics.LyricsService;
import com.jagrosh.jmusicbot.lyrics.LyricsCache;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class LyricsCmd extends MusicCommand {
    private static volatile LyricsService service;
    public LyricsCmd(Bot bot) {
        super(bot);
        this.name = "lyrics";
        this.arguments = "[song name]";
        this.help = "shows the lyrics of a song (uses Genius web scraping with caching)";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.botPermissions = new Permission[]{Permission.MESSAGE_EMBED_LINKS};
    }

    private void initService() {
        if (service == null) {
            synchronized (LyricsCmd.class) {
                if (service == null) {
                    try {
                        service = new LyricsService(Path.of("lyrics-cache.db"));
                    } catch (Exception e) {
                        // swallow: service remains null
                    }
                }
            }
        }
    }

    @Override
    public void doCommand(CommandEvent event) {
        if (service == null) {
            initService();
            if (service == null) {
                event.replyError("Lyrics service failed to initialize.");
                return;
            }
        }
        String query;
        if (event.getArgs().isEmpty()) {
            AudioHandler sendingHandler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            if (sendingHandler.isMusicPlaying(event.getJDA())) {
                query = sendingHandler.getPlayer().getPlayingTrack().getInfo().title;
            } else {
                event.replyError("There must be music playing to use that!");
                return;
            }
        } else {
            query = event.getArgs();
        }
        final String usedQuery = query;
        event.getChannel().sendTyping().queue();
        // Run network / IO off JDA thread
        CompletableFuture
                .supplyAsync(() -> serviceFetch(usedQuery))
                .thenAccept(opt -> {
                    if (opt.isEmpty()) {
                        event.replyError("Lyrics for `" + usedQuery + "` could not be found." + (event.getArgs().isEmpty()?" Try specifying the song name (`!lyrics <song name>`).":""));
                        return;
                    }
                    LyricsCache.CachedLyrics lyrics = opt.get();
                    String titleLine = (lyrics.artist()==null||lyrics.artist().isBlank()?"":lyrics.artist()+" - ") + lyrics.title();
                    String content = lyrics.lyrics();
                    EmbedBuilder eb = new EmbedBuilder()
                            .setColor(event.getSelfMember().getColor())
                            .setTitle(titleLine, lyrics.sourceUrl());
                    if (content.length() > 15000) {
                        event.replyWarning("Lyrics for `" + usedQuery + "` found but seem unusually long: " + lyrics.sourceUrl());
                        return;
                    }
                    if (content.length() > 2000) {
                        String remaining = content.trim();
                        boolean first = true;
                        while (remaining.length() > 2000) {
                            int index = remaining.lastIndexOf("\n\n", 2000);
                            if (index == -1) index = remaining.lastIndexOf("\n", 2000);
                            if (index == -1) index = remaining.lastIndexOf(" ", 2000);
                            if (index == -1) index = Math.min(2000, remaining.length());
                            String part = remaining.substring(0, index).trim();
                            event.reply((first?eb:eb.setTitle(null,null)).setDescription(part).build());
                            remaining = remaining.substring(index).trim();
                            first = false;
                        }
                        if (!remaining.isEmpty()) {
                            event.reply(eb.setTitle(null,null).setDescription(remaining).build());
                        }
                    } else {
                        event.reply(eb.setDescription(content).build());
                    }
                });
    }

    private Optional<LyricsCache.CachedLyrics> serviceFetch(String query) {
        try {
            return service.fetchAndCache(query, true);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
