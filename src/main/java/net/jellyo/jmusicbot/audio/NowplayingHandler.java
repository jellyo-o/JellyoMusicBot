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
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.lyrics.LyricsCache;
import com.jagrosh.jmusicbot.lyrics.LyricsService;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class NowplayingHandler
{
    public static final String BUTTON_PLAY_PAUSE = "jmb-np:play-pause";
    public static final String BUTTON_SKIP = "jmb-np:skip";
    public static final String BUTTON_LOOP = "jmb-np:loop";
    public static final String BUTTON_LYRICS = "jmb-np:lyrics";
    public static final String BUTTON_STOP = "jmb-np:stop";
    private static final long PANEL_REFRESH_SECONDS = Math.max(10L,
            Long.getLong("jmusicbot.nowplaying.refreshSeconds", 15L));
    private static final long PANEL_MIN_EDIT_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(Math.max(5L,
            Long.getLong("jmusicbot.nowplaying.minEditSeconds", 10L)));

    private final Bot bot;
    private final Map<Long, Map<Long, Long>> panels; // guild -> channel -> message
    private final Map<PanelKey, PanelUpdateState> panelUpdateStates;
    private volatile LyricsService lyricsService;
    
    public NowplayingHandler(Bot bot)
    {
        this.bot = bot;
        this.panels = new ConcurrentHashMap<>();
        this.panelUpdateStates = new ConcurrentHashMap<>();
    }
    
    public void init()
    {
        bot.getThreadpool().scheduleWithFixedDelay(() -> updateAll(),
                PANEL_REFRESH_SECONDS, PANEL_REFRESH_SECONDS, TimeUnit.SECONDS);
    }
    
    public void setLastNPMessage(Message m)
    {
        rememberPanel(m.getGuild().getIdLong(), m.getChannelIdLong(), m.getIdLong());
    }
    
    public void clearLastNPMessage(Guild guild)
    {
        panels.remove(guild.getIdLong());
        panelUpdateStates.keySet().removeIf(key -> key.guildId == guild.getIdLong());
    }

    public void showPanel(Guild guild, MessageChannel channel, boolean replaceExisting)
    {
        showPanel(guild, channel, replaceExisting, m -> {}, t -> {});
    }

    public void showPanel(Guild guild, MessageChannel channel, boolean replaceExisting,
                          Consumer<Message> success, Consumer<Throwable> failure)
    {
        showOrUpdatePanel(guild, channel, replaceExisting, success, failure);
    }

    public void showPanel(Guild guild, MessageChannel channel, Member viewer, boolean replaceExisting,
                          Consumer<PanelResult> success, Consumer<Throwable> failure)
    {
        showOrUpdatePanel(guild, channel, viewer, replaceExisting, success, failure);
    }
    
    private void updateAll()
    {
        for(long guildId: panels.keySet())
        {
            Guild guild = bot.getJDA() == null ? null : bot.getJDA().getGuildById(guildId);
            if(guild != null && isMusicPlaying(guild))
                updatePanels(guild, false);
        }
    }

    public void updatePanels(long guildId)
    {
        updatePanels(guildId, false);
    }

    private void updatePanels(long guildId, boolean bypassThrottle)
    {
        Guild guild = bot.getJDA() == null ? null : bot.getJDA().getGuildById(guildId);
        if(guild != null)
            updatePanels(guild, bypassThrottle);
    }

    public void updatePanels(Guild guild)
    {
        updatePanels(guild, false);
    }

    private void updatePanels(Guild guild, boolean bypassThrottle)
    {
        Map<Long, Long> channelPanels = panels.get(guild.getIdLong());
        if(channelPanels == null || channelPanels.isEmpty())
            return;

        for(Map.Entry<Long, Long> entry : channelPanels.entrySet())
        {
            TextChannel channel = guild.getTextChannelById(entry.getKey());
            if(channel == null)
            {
                channelPanels.remove(entry.getKey());
                panelUpdateStates.remove(new PanelKey(guild.getIdLong(), entry.getKey()));
                continue;
            }

            requestPanelUpdate(guild.getIdLong(), entry.getKey(), bypassThrottle);
        }
    }

    // "event"-based methods
    public void onTrackUpdate(long guildId, AudioTrack track)
    {
        // update bot status if applicable
        if(bot.getConfig().getSongInStatus())
        {
            if(track!=null && bot.getJDA().getGuilds().stream().filter(g -> g.getSelfMember().getVoiceState().inAudioChannel()).count()<=1)
                bot.getJDA().getPresence().setActivity(Activity.listening(track.getInfo().title));
            else
                bot.resetGame();
        }

        Guild guild = bot.getJDA() == null ? null : bot.getJDA().getGuildById(guildId);
        if(guild == null)
            return;

        updatePanels(guild, true);
        if(track != null)
        {
            TextChannel channel = getDefaultPanelChannel(guild, track);
            if(channel != null && getPanelMessageId(guildId, channel.getIdLong()) == null)
                showPanel(guild, channel, false);
        }
    }
    
    public void onMessageDelete(Guild guild, long messageId)
    {
        Map<Long, Long> channelPanels = panels.get(guild.getIdLong());
        if(channelPanels == null)
            return;
        channelPanels.entrySet().removeIf(entry ->
        {
            boolean remove = entry.getValue() == messageId;
            if(remove)
                panelUpdateStates.remove(new PanelKey(guild.getIdLong(), entry.getKey()));
            return remove;
        });
    }

    public void onButtonInteraction(ButtonInteractionEvent event)
    {
        String componentId = event.getComponentId();
        if(!isPanelButton(componentId))
            return;
        if(event.getUser().isBot())
        {
            event.deferEdit().queue();
            return;
        }
        if(event.getGuild() == null || !isActivePanel(event.getGuild().getIdLong(), event.getChannel().getIdLong(), event.getMessageIdLong()))
        {
            event.reply(bot.getConfig().getWarning() + " This music panel is no longer active. Run `/nowplaying` to open a fresh panel.")
                    .setEphemeral(true).queue();
            return;
        }

        switch(componentId)
        {
            case BUTTON_PLAY_PAUSE:
                handlePlayPause(event);
                break;
            case BUTTON_SKIP:
                handleSkip(event);
                break;
            case BUTTON_LOOP:
                handleLoop(event);
                break;
            case BUTTON_LYRICS:
                handleLyrics(event);
                break;
            case BUTTON_STOP:
                handleStop(event);
                break;
            default:
                event.deferEdit().queue();
                break;
        }
    }

    private void showOrUpdatePanel(Guild guild, MessageChannel channel, boolean replaceExisting,
                                   Consumer<Message> success, Consumer<Throwable> failure)
    {
        showOrUpdatePanel(guild, channel, null, replaceExisting,
                result ->
                {
                    if(result.getMessage() != null)
                        success.accept(result.getMessage());
                }, failure);
    }

    private void showOrUpdatePanel(Guild guild, MessageChannel channel, Member viewer, boolean replaceExisting,
                                   Consumer<PanelResult> success, Consumer<Throwable> failure)
    {
        if(guild == null || channel == null)
            return;

        long guildId = guild.getIdLong();
        long channelId = channel.getIdLong();
        if(replaceExisting && viewer != null)
        {
            Optional<PanelReference> visiblePanel = findVisiblePanel(guild, viewer, channelId);
            if(visiblePanel.isPresent())
            {
                PanelReference panel = visiblePanel.get();
                requestPanelUpdate(guildId, panel.channelId, false);
                success.accept(PanelResult.reused(guildId, panel.channelId, panel.messageId));
                return;
            }
        }

        Long existingMessageId = getPanelMessageId(guildId, channelId);
        if(existingMessageId != null)
        {
            if(replaceExisting)
            {
                sendPanel(guild, channel, m ->
                {
                    markPanelMoved(channel, existingMessageId, m);
                    success.accept(PanelResult.posted(m));
                }, failure);
                return;
            }
            else
            {
                MessageCreateData msg = bot.getPlayerManager().setUpHandler(guild).getMusicPanel(bot.getJDA());
                channel.editMessageById(existingMessageId, MessageEditData.fromCreateData(msg))
                        .queue(m -> success.accept(PanelResult.updated(m)),
                                t -> sendPanel(guild, channel, m -> success.accept(PanelResult.posted(m)), failure));
                return;
            }
        }

        sendPanel(guild, channel, m -> success.accept(PanelResult.posted(m)), failure);
    }

    private void sendPanel(Guild guild, MessageChannel channel, Consumer<Message> success, Consumer<Throwable> failure)
    {
        MessageCreateData msg = bot.getPlayerManager().setUpHandler(guild).getMusicPanel(bot.getJDA());
        channel.sendMessage(msg).queue(m ->
        {
            rememberPanel(guild.getIdLong(), channel.getIdLong(), m.getIdLong());
            success.accept(m);
        }, failure);
    }

    private void markPanelMoved(MessageChannel channel, long oldMessageId, Message newPanel)
    {
        String content = bot.getConfig().getWarning() + " This music panel moved to " + newPanel.getJumpUrl();
        MessageEditData moved = new MessageEditBuilder()
                .setContent(content)
                .setEmbeds(Collections.emptyList())
                .setComponents(Collections.emptyList())
                .build();
        channel.editMessageById(oldMessageId, moved).queue(m -> {}, t -> {});
    }

    private void rememberPanel(long guildId, long channelId, long messageId)
    {
        panels.computeIfAbsent(guildId, id -> new ConcurrentHashMap<>()).put(channelId, messageId);
    }

    private Optional<PanelReference> findVisiblePanel(Guild guild, Member viewer, long preferredChannelId)
    {
        Map<Long, Long> channelPanels = panels.get(guild.getIdLong());
        if(channelPanels == null || channelPanels.isEmpty())
            return Optional.empty();

        Long preferredMessageId = channelPanels.get(preferredChannelId);
        if(preferredMessageId != null)
        {
            TextChannel preferredChannel = guild.getTextChannelById(preferredChannelId);
            if(preferredChannel == null)
            {
                channelPanels.remove(preferredChannelId);
                panelUpdateStates.remove(new PanelKey(guild.getIdLong(), preferredChannelId));
            }
            else if(canViewPanel(viewer, preferredChannel))
            {
                return Optional.of(new PanelReference(preferredChannelId, preferredMessageId));
            }
        }

        for(Map.Entry<Long, Long> entry : channelPanels.entrySet())
        {
            if(entry.getKey() == preferredChannelId)
                continue;
            TextChannel panelChannel = guild.getTextChannelById(entry.getKey());
            if(panelChannel == null)
            {
                channelPanels.remove(entry.getKey());
                panelUpdateStates.remove(new PanelKey(guild.getIdLong(), entry.getKey()));
                continue;
            }
            if(canViewPanel(viewer, panelChannel))
                return Optional.of(new PanelReference(entry.getKey(), entry.getValue()));
        }
        return Optional.empty();
    }

    private boolean canViewPanel(Member viewer, TextChannel channel)
    {
        return viewer != null && viewer.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY);
    }

    private boolean isMusicPlaying(Guild guild)
    {
        if(bot.getJDA() == null)
            return false;
        if(!(guild.getAudioManager().getSendingHandler() instanceof AudioHandler))
            return false;
        AudioHandler handler = (AudioHandler)guild.getAudioManager().getSendingHandler();
        return handler.isMusicPlaying(bot.getJDA());
    }

    private void requestPanelUpdate(long guildId, long channelId, boolean bypassThrottle)
    {
        PanelKey key = new PanelKey(guildId, channelId);
        PanelUpdateState state = panelUpdateStates.computeIfAbsent(key, ignored -> new PanelUpdateState());
        synchronized(state)
        {
            state.pending = true;
            if(bypassThrottle)
            {
                state.immediatePending = true;
                if(hasScheduledUpdate(state) && !state.editInFlight)
                {
                    state.scheduledUpdate.cancel(false);
                    state.scheduledUpdate = null;
                }
            }
            if(state.editInFlight)
                return;
            if(hasScheduledUpdate(state))
                return;

            long now = System.currentTimeMillis();
            long delayMillis = state.immediatePending ? 0L : Math.max(0L, state.nextAllowedEditAt - now);
            schedulePanelUpdate(key, state, delayMillis);
        }
    }

    private boolean hasScheduledUpdate(PanelUpdateState state)
    {
        return state.scheduledUpdate != null
                && !state.scheduledUpdate.isDone()
                && !state.scheduledUpdate.isCancelled();
    }

    private void schedulePanelUpdate(PanelKey key, PanelUpdateState state, long delayMillis)
    {
        state.scheduledUpdate = bot.getThreadpool().schedule(() -> runPanelUpdate(key),
                Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private void runPanelUpdate(PanelKey key)
    {
        PanelUpdateState state = panelUpdateStates.computeIfAbsent(key, ignored -> new PanelUpdateState());
        synchronized(state)
        {
            state.scheduledUpdate = null;
            if(state.editInFlight || !state.pending)
                return;

            long delayMillis = state.immediatePending ? 0L : state.nextAllowedEditAt - System.currentTimeMillis();
            if(delayMillis > 0L)
            {
                schedulePanelUpdate(key, state, delayMillis);
                return;
            }

            state.pending = false;
            state.immediatePending = false;
            state.editInFlight = true;
        }

        editPanel(key);
    }

    private void editPanel(PanelKey key)
    {
        Guild guild = bot.getJDA() == null ? null : bot.getJDA().getGuildById(key.guildId);
        Map<Long, Long> channelPanels = panels.get(key.guildId);
        Long messageId = channelPanels == null ? null : channelPanels.get(key.channelId);
        TextChannel channel = guild == null ? null : guild.getTextChannelById(key.channelId);
        if(guild == null || channelPanels == null || messageId == null || channel == null)
        {
            if(channelPanels != null)
                channelPanels.remove(key.channelId);
            finishPanelUpdate(key, true);
            return;
        }

        MessageCreateData msg = bot.getPlayerManager().setUpHandler(guild).getMusicPanel(bot.getJDA());
        channel.editMessageById(messageId, MessageEditData.fromCreateData(msg))
                .queue(m -> finishPanelUpdate(key, false), t ->
                {
                    channelPanels.remove(key.channelId, messageId);
                    finishPanelUpdate(key, true);
                });
    }

    private void finishPanelUpdate(PanelKey key, boolean removeState)
    {
        PanelUpdateState state = panelUpdateStates.get(key);
        if(state == null)
            return;

        synchronized(state)
        {
            state.editInFlight = false;
            state.nextAllowedEditAt = System.currentTimeMillis() + PANEL_MIN_EDIT_INTERVAL_MILLIS;
            if(removeState)
            {
                state.pending = false;
                panelUpdateStates.remove(key, state);
                return;
            }
            if(state.pending && !hasScheduledUpdate(state))
                schedulePanelUpdate(key, state, state.immediatePending ? 0L : PANEL_MIN_EDIT_INTERVAL_MILLIS);
        }
    }

    private Long getPanelMessageId(long guildId, long channelId)
    {
        Map<Long, Long> channelPanels = panels.get(guildId);
        return channelPanels == null ? null : channelPanels.get(channelId);
    }

    private boolean isActivePanel(long guildId, long channelId, long messageId)
    {
        Long activeMessageId = getPanelMessageId(guildId, channelId);
        return activeMessageId != null && activeMessageId == messageId;
    }

    private TextChannel getDefaultPanelChannel(Guild guild, AudioTrack track)
    {
        Settings settings = bot.getSettingsManager().getSettings(guild);
        TextChannel configured = settings.getTextChannel(guild);
        if(configured != null)
            return configured;

        RequestMetadata rm = track.getUserData(RequestMetadata.class);
        if(rm != null && rm.getTextChannelId() != 0L)
            return guild.getTextChannelById(rm.getTextChannelId());
        return null;
    }

    private boolean isPanelButton(String componentId)
    {
        return BUTTON_PLAY_PAUSE.equals(componentId)
                || BUTTON_SKIP.equals(componentId)
                || BUTTON_LOOP.equals(componentId)
                || BUTTON_LYRICS.equals(componentId)
                || BUTTON_STOP.equals(componentId);
    }

    private void handlePlayPause(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null || !requireListening(event) || !requireDJ(event))
            return;

        handler.getPlayer().setPaused(!handler.getPlayer().isPaused());
        event.deferEdit().queue();
        handler.updateMusicPanels();
    }

    private void handleSkip(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null || !requireListening(event))
            return;

        RequestMetadata rm = handler.getRequestMetadata();
        double skipRatio = bot.getSettingsManager().getSettings(event.getGuild()).getSkipRatio();
        if(skipRatio == -1)
            skipRatio = bot.getConfig().getSkipRatio();

        if(event.getUser().getIdLong() == rm.getOwner() || skipRatio == 0)
        {
            handler.getPlayer().stopTrack();
            event.deferEdit().queue();
            return;
        }

        handler.getVotes().add(event.getUser().getId());
        AudioChannel channel = event.getGuild().getSelfMember().getVoiceState().getChannel();
        int listeners = (int)channel.getMembers().stream()
                .filter(m -> !m.getUser().isBot() && !m.getVoiceState().isDeafened()).count();
        int required = Math.max(1, (int)Math.ceil(listeners * skipRatio));
        int skippers = (int)channel.getMembers().stream()
                .filter(m -> handler.getVotes().contains(m.getUser().getId())).count();

        if(skippers >= required)
            handler.getPlayer().stopTrack();
        else
            handler.updateMusicPanels();
        event.deferEdit().queue();
    }

    private void handleLoop(ButtonInteractionEvent event)
    {
        if(requirePlaying(event) == null || !requireListening(event) || !requireDJ(event))
            return;

        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        settings.setRepeatMode(nextRepeatMode(settings.getRepeatMode()));
        event.deferEdit().queue();
        updatePanels(event.getGuild());
    }

    private void handleStop(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null || !requireListening(event) || !requireDJ(event))
            return;

        handler.stopAndClear();
        event.getGuild().getAudioManager().closeAudioConnection();
        event.deferEdit().queue();
    }

    private void handleLyrics(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null)
            return;

        String query = handler.getPlayer().getPlayingTrack().getInfo().title;
        event.deferReply(true).queue(hook -> CompletableFuture
                .supplyAsync(() -> fetchLyrics(query))
                .thenAccept(opt ->
                {
                    if(opt.isEmpty())
                    {
                        hook.editOriginal(bot.getConfig().getWarning() + " Lyrics for `" + query + "` could not be found.").queue();
                        return;
                    }

                    LyricsCache.CachedLyrics lyrics = opt.get();
                    String title = (lyrics.artist() == null || lyrics.artist().isBlank() ? "" : lyrics.artist() + " - ") + lyrics.title();
                    String content = lyrics.lyrics();
                    if(content.length() > 3900)
                    {
                        hook.editOriginal(bot.getConfig().getWarning() + " Lyrics found but are too long for a panel reply: " + lyrics.sourceUrl()).queue();
                        return;
                    }

                    hook.editOriginalEmbeds(new EmbedBuilder()
                            .setColor(event.getGuild().getSelfMember().getColor())
                            .setTitle(title, lyrics.sourceUrl())
                            .setDescription(content)
                            .build()).queue();
                })
                .exceptionally(ex ->
                {
                    hook.editOriginal(bot.getConfig().getError() + " Failed to fetch lyrics for `" + query + "`.").queue();
                    return null;
                }));
    }

    private Optional<LyricsCache.CachedLyrics> fetchLyrics(String query)
    {
        try
        {
            LyricsService service = getLyricsService();
            return service == null ? Optional.empty() : service.fetchAndCache(query, true);
        }
        catch(Exception ex)
        {
            return Optional.empty();
        }
    }

    private LyricsService getLyricsService()
    {
        if(lyricsService == null)
        {
            synchronized(this)
            {
                if(lyricsService == null)
                {
                    try
                    {
                        lyricsService = new LyricsService(Path.of("lyrics-cache.db"));
                    }
                    catch(Exception ignored)
                    {
                        return null;
                    }
                }
            }
        }
        return lyricsService;
    }

    private AudioHandler requirePlaying(ButtonInteractionEvent event)
    {
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        if(handler == null || !handler.isMusicPlaying(event.getJDA()))
        {
            event.reply(bot.getConfig().getError() + " There must be music playing to use that.").setEphemeral(true).queue();
            updatePanels(event.getGuild());
            return null;
        }
        return handler;
    }

    private boolean requireListening(ButtonInteractionEvent event)
    {
        AudioChannel current = event.getGuild().getSelfMember().getVoiceState().getChannel();
        Member member = event.getMember();
        if(member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()
                || member.getVoiceState().isDeafened()
                || (current != null && !current.equals(member.getVoiceState().getChannel())))
        {
            event.reply(bot.getConfig().getError() + " You must be listening in "
                    + (current == null ? "a voice channel" : current.getAsMention()) + " to use that.")
                    .setEphemeral(true).queue();
            return false;
        }
        return true;
    }

    private boolean requireDJ(ButtonInteractionEvent event)
    {
        if(checkDJPermission(event.getMember(), event.getGuild()))
            return true;

        event.reply(bot.getConfig().getError() + " You need DJ permissions to use that.").setEphemeral(true).queue();
        return false;
    }

    private boolean checkDJPermission(Member member, Guild guild)
    {
        if(member == null || guild == null)
            return false;
        if(member.getIdLong() == bot.getConfig().getOwnerId())
            return true;
        if(member.hasPermission(Permission.MANAGE_SERVER))
            return true;
        Settings settings = bot.getSettingsManager().getSettings(guild);
        Role dj = settings.getRole(guild);
        return dj != null && (member.getRoles().contains(dj) || dj.getIdLong() == guild.getIdLong());
    }

    private RepeatMode nextRepeatMode(RepeatMode current)
    {
        switch(current)
        {
            case OFF:
                return RepeatMode.ALL;
            case ALL:
                return RepeatMode.SINGLE;
            case SINGLE:
            default:
                return RepeatMode.OFF;
        }
    }

    private static final class PanelKey
    {
        private final long guildId;
        private final long channelId;

        private PanelKey(long guildId, long channelId)
        {
            this.guildId = guildId;
            this.channelId = channelId;
        }

        @Override
        public boolean equals(Object obj)
        {
            if(this == obj)
                return true;
            if(!(obj instanceof PanelKey))
                return false;
            PanelKey other = (PanelKey)obj;
            return guildId == other.guildId && channelId == other.channelId;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(guildId, channelId);
        }
    }

    private static final class PanelUpdateState
    {
        private boolean editInFlight;
        private boolean pending;
        private boolean immediatePending;
        private long nextAllowedEditAt;
        private ScheduledFuture<?> scheduledUpdate;
    }

    private static final class PanelReference
    {
        private final long channelId;
        private final long messageId;

        private PanelReference(long channelId, long messageId)
        {
            this.channelId = channelId;
            this.messageId = messageId;
        }
    }

    public static final class PanelResult
    {
        private final Message message;
        private final long guildId;
        private final long channelId;
        private final long messageId;
        private final boolean posted;

        private PanelResult(Message message, long guildId, long channelId, long messageId, boolean posted)
        {
            this.message = message;
            this.guildId = guildId;
            this.channelId = channelId;
            this.messageId = messageId;
            this.posted = posted;
        }

        private static PanelResult posted(Message message)
        {
            return new PanelResult(message, message.getGuild().getIdLong(),
                    message.getChannelIdLong(), message.getIdLong(), true);
        }

        private static PanelResult updated(Message message)
        {
            return new PanelResult(message, message.getGuild().getIdLong(),
                    message.getChannelIdLong(), message.getIdLong(), false);
        }

        private static PanelResult reused(long guildId, long channelId, long messageId)
        {
            return new PanelResult(null, guildId, channelId, messageId, false);
        }

        public Message getMessage()
        {
            return message;
        }

        public boolean isPosted()
        {
            return posted;
        }

        public String getJumpUrl()
        {
            if(message != null)
                return message.getJumpUrl();
            return "https://discord.com/channels/" + guildId + "/" + channelId + "/" + messageId;
        }
    }
}
