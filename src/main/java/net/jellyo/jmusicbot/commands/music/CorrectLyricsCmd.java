package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.lyrics.InputValidator;
import com.jagrosh.jmusicbot.lyrics.LyricsCache;
import com.jagrosh.jmusicbot.lyrics.LyricsService;
import net.dv8tion.jda.api.Permission;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class CorrectLyricsCmd extends MusicCommand {
    private static volatile LyricsService service;
    public CorrectLyricsCmd(Bot bot) {
        super(bot);
        this.name = "correctlyrics";
        this.arguments = "<genius-url>";
        this.help = "corrects the last fetched lyrics entry by supplying a valid genius.com lyrics URL";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.botPermissions = new Permission[]{Permission.MESSAGE_EMBED_LINKS};
    }

    private void initService() {
        if (service==null) {
            synchronized (CorrectLyricsCmd.class) {
                if (service==null) {
                    try { service = new LyricsService(Path.of("lyrics-cache.db")); } catch (Exception ignored) {}
                }
            }
        }
    }

    @Override
    public void doCommand(CommandEvent event) {
        if (service==null) initService();
        if (service==null) { event.replyError("Lyrics service unavailable."); return; }
        String url = event.getArgs().trim();
        if (url.isEmpty()) { event.replyError("You must provide a genius.com lyrics URL."); return; }
        if (!url.contains("genius.com")) { event.replyError("Provide a valid genius.com lyrics URL"); return; }
        if (!url.startsWith("http")) url = "https://"+url; // normalize
        final String finalUrl = url;
        if (!InputValidator.isValidGeniusUrl(finalUrl)) { event.replyError("That doesn't look like a valid Genius lyrics URL."); return; }
        event.getChannel().sendTyping().queue();
        CompletableFuture
                .supplyAsync(() -> doReplace(finalUrl))
                .thenAccept(opt -> {
                    if (opt.isEmpty()) {
                        event.replyError("Could not correct using that URL (fetch failed or invalid)." );
                        return;
                    }
                    LyricsCache.CachedLyrics cl = opt.get();
                    event.replySuccess("Updated lyrics cache to use URL: " + cl.sourceUrl());
                });
    }

    private Optional<LyricsCache.CachedLyrics> doReplace(String url) {
        try { return service.replaceLastWithFetched(url); } catch (Exception e) { return Optional.empty(); }
    }
}
