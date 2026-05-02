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
import com.jagrosh.jmusicbot.playlist.PlaylistTrack;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.AddResult;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistException;
import com.jagrosh.jmusicbot.settings.AutoplayMode;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
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
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class NowplayingHandler
{
    private final static Logger LOG = LoggerFactory.getLogger(NowplayingHandler.class);
    private final static int QUEUE_PREVIEW_LIMIT = 10;

    public static final String BUTTON_RESTART = "jmb-np:restart";
    public static final String BUTTON_PLAY_PAUSE = "jmb-np:play-pause";
    public static final String BUTTON_SKIP = "jmb-np:skip";
    public static final String BUTTON_QUEUE = "jmb-np:queue";
    public static final String BUTTON_LIKE = "jmb-np:like";
    public static final String BUTTON_LOOP = "jmb-np:loop";
    public static final String BUTTON_SHUFFLE = "jmb-np:shuffle";
    public static final String BUTTON_LYRICS = "jmb-np:lyrics";
    public static final String BUTTON_STOP = "jmb-np:stop";
    private static final String BUTTON_STOP_CONFIRM_PREFIX = "jmb-np:stop-confirm:";
    private static final String BUTTON_STOP_CANCEL_PREFIX = "jmb-np:stop-cancel:";
    private static final long PANEL_MIN_EDIT_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(Math.max(5L,
            Long.getLong("jmusicbot.nowplaying.minEditSeconds", 15L)));
    private static final long PANEL_INTERACTIVE_EDIT_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(Math.max(2L,
            Long.getLong("jmusicbot.nowplaying.interactiveEditSeconds", 2L)));
    private static final long PANEL_BUTTON_UPDATE_DEBOUNCE_MILLIS = Math.max(100L,
            Long.getLong("jmusicbot.nowplaying.buttonDebounceMillis", 250L));
    private static final int PANEL_MOVE_MESSAGE_DISTANCE = Math.max(1,
            Integer.getInteger("jmusicbot.nowplaying.moveAfterMessages", 5));

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
        // Panel updates are event driven. Requests are queued per panel and throttled in requestPanelUpdate.
    }
    
    public void setLastNPMessage(Message m)
    {
        rememberPanel(m.getGuild().getIdLong(), m.getChannelIdLong(), m.getIdLong());
        markPanelUpdated(m.getGuild().getIdLong(), m.getChannelIdLong());
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

    public void collapsePanels(Guild guild, String reason)
    {
        if(guild == null)
            return;

        Map<Long, Long> channelPanels = panels.remove(guild.getIdLong());
        panelUpdateStates.keySet().removeIf(key -> key.guildId == guild.getIdLong());
        if(channelPanels == null || channelPanels.isEmpty())
            return;

        MessageEditData closedPanel = new MessageEditBuilder()
                .setContent(bot.getConfig().getWarning() + " " + reason)
                .setEmbeds(Collections.emptyList())
                .setComponents(Collections.emptyList())
                .build();
        for(Map.Entry<Long, Long> entry : channelPanels.entrySet())
        {
            TextChannel channel = guild.getTextChannelById(entry.getKey());
            if(channel == null)
                continue;
            channel.editMessageById(entry.getValue(), closedPanel).queue(m -> {}, t -> {});
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
        if(!isPanelButton(componentId) && !isStopConfirmationButton(componentId))
            return;
        if(event.getUser().isBot())
        {
            event.deferEdit().queue();
            return;
        }
        if(isStopConfirmationButton(componentId))
        {
            handleStopConfirmation(event);
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
            case BUTTON_RESTART:
                handleRestart(event);
                break;
            case BUTTON_PLAY_PAUSE:
                handlePlayPause(event);
                break;
            case BUTTON_SKIP:
                handleSkip(event);
                break;
            case BUTTON_QUEUE:
                handleQueue(event);
                break;
            case BUTTON_LIKE:
                handleLike(event);
                break;
            case BUTTON_LOOP:
                handleLoop(event);
                break;
            case BUTTON_SHUFFLE:
                handleShuffle(event);
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
                        .queue(m ->
                                {
                                    markPanelUpdated(guildId, channelId);
                                    success.accept(PanelResult.updated(m));
                                },
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
            markPanelUpdated(guild.getIdLong(), channel.getIdLong());
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

    private void markPanelUpdated(long guildId, long channelId)
    {
        PanelKey key = new PanelKey(guildId, channelId);
        PanelUpdateState state = panelUpdateStates.computeIfAbsent(key, ignored -> new PanelUpdateState());
        synchronized(state)
        {
            state.lastEditAt = System.currentTimeMillis();
        }
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

    private void requestPanelUpdate(long guildId, long channelId, boolean bypassThrottle)
    {
        requestPanelUpdate(guildId, channelId,
                bypassThrottle ? PANEL_INTERACTIVE_EDIT_INTERVAL_MILLIS : PANEL_MIN_EDIT_INTERVAL_MILLIS, 0L);
    }

    private void requestPanelUpdate(long guildId, long channelId, long minEditIntervalMillis, long debounceMillis)
    {
        PanelKey key = new PanelKey(guildId, channelId);
        PanelUpdateState state = panelUpdateStates.computeIfAbsent(key, ignored -> new PanelUpdateState());
        synchronized(state)
        {
            state.markPending(minEditIntervalMillis);
            if(state.editInFlight)
                return;

            long now = System.currentTimeMillis();
            long delayMillis = computePanelUpdateDelayMillis(now, state.lastEditAt,
                    minEditIntervalMillis, debounceMillis);
            long scheduledAt = now + delayMillis;
            if(hasScheduledUpdate(state) && state.scheduledUpdateAt <= scheduledAt)
                return;
            if(hasScheduledUpdate(state))
                state.scheduledUpdate.cancel(false);

            schedulePanelUpdate(key, state, delayMillis);
        }
    }

    private void requestButtonPanelUpdate(ButtonInteractionEvent event)
    {
        if(event.getGuild() == null)
            return;

        long guildId = event.getGuild().getIdLong();
        long channelId = event.getChannel().getIdLong();
        requestPanelUpdate(guildId, channelId,
                PANEL_INTERACTIVE_EDIT_INTERVAL_MILLIS, PANEL_BUTTON_UPDATE_DEBOUNCE_MILLIS);
    }

    private boolean hasScheduledUpdate(PanelUpdateState state)
    {
        return state.scheduledUpdate != null
                && !state.scheduledUpdate.isDone()
                && !state.scheduledUpdate.isCancelled();
    }

    private void schedulePanelUpdate(PanelKey key, PanelUpdateState state, long delayMillis)
    {
        state.scheduledUpdateAt = System.currentTimeMillis() + Math.max(0L, delayMillis);
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

            long delayMillis = state.lastEditAt + state.pendingMinEditIntervalMillis - System.currentTimeMillis();
            if(delayMillis > 0L)
            {
                schedulePanelUpdate(key, state, delayMillis);
                return;
            }

            state.pending = false;
            state.pendingMinEditIntervalMillis = PANEL_MIN_EDIT_INTERVAL_MILLIS;
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

        MessageCreateData msg;
        try
        {
            msg = bot.getPlayerManager().setUpHandler(guild).getMusicPanel(bot.getJDA());
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Failed to build music panel for guild {}", guild.getId(), ex);
            finishPanelUpdate(key, false);
            return;
        }

        if(shouldInspectPanelDistance(channel.getLatestMessageIdLong(), messageId))
        {
            inspectPanelDistanceThenUpdate(key, guild, channelPanels, channel, messageId, msg);
            return;
        }

        editPanelMessage(key, guild, channelPanels, channel, messageId, msg);
    }

    private void inspectPanelDistanceThenUpdate(PanelKey key, Guild guild, Map<Long, Long> channelPanels,
                                                TextChannel channel, long messageId, MessageCreateData msg)
    {
        try
        {
            channel.getHistoryAfter(messageId, PANEL_MOVE_MESSAGE_DISTANCE)
                    .queue(messages ->
                    {
                        if(shouldMovePanel(messages.size(), PANEL_MOVE_MESSAGE_DISTANCE))
                            movePanelToLatest(key, guild, channelPanels, channel, messageId, msg);
                        else
                            editPanelMessage(key, guild, channelPanels, channel, messageId, msg);
                    }, t ->
                    {
                        LOG.debug("Could not inspect music panel distance for message {} in channel {} for guild {}; editing in place",
                                messageId, key.channelId, guild.getId(), t);
                        editPanelMessage(key, guild, channelPanels, channel, messageId, msg);
                    });
        }
        catch(RuntimeException ex)
        {
            LOG.debug("Failed to queue music panel distance check for message {} in channel {} for guild {}; editing in place",
                    messageId, key.channelId, guild.getId(), ex);
            editPanelMessage(key, guild, channelPanels, channel, messageId, msg);
        }
    }

    private void movePanelToLatest(PanelKey key, Guild guild, Map<Long, Long> channelPanels,
                                   TextChannel channel, long oldMessageId, MessageCreateData msg)
    {
        try
        {
            channel.sendMessage(msg).queue(newPanel ->
            {
                rememberPanel(key.guildId, key.channelId, newPanel.getIdLong());
                markPanelUpdated(key.guildId, key.channelId);
                markPanelMoved(channel, oldMessageId, newPanel);
                finishPanelUpdate(key, false);
            }, t ->
            {
                LOG.warn("Failed to move music panel {} to latest message in channel {} for guild {}; editing in place",
                        oldMessageId, key.channelId, guild.getId(), t);
                editPanelMessage(key, guild, channelPanels, channel, oldMessageId, msg);
            });
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Failed to queue music panel move for message {} in channel {} for guild {}; editing in place",
                    oldMessageId, key.channelId, guild.getId(), ex);
            editPanelMessage(key, guild, channelPanels, channel, oldMessageId, msg);
        }
    }

    private void editPanelMessage(PanelKey key, Guild guild, Map<Long, Long> channelPanels,
                                  TextChannel channel, long messageId, MessageCreateData msg)
    {
        try
        {
            channel.editMessageById(messageId, MessageEditData.fromCreateData(msg))
                    .queue(m -> finishPanelUpdate(key, false), t ->
                    {
                        LOG.debug("Removing stale music panel {} in channel {} for guild {}",
                                messageId, key.channelId, guild.getId());
                        channelPanels.remove(key.channelId, messageId);
                        finishPanelUpdate(key, true);
                    });
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Failed to queue music panel update for message {} in channel {} for guild {}",
                    messageId, key.channelId, guild.getId(), ex);
            channelPanels.remove(key.channelId, messageId);
            finishPanelUpdate(key, true);
        }
    }

    private void finishPanelUpdate(PanelKey key, boolean removeState)
    {
        PanelUpdateState state = panelUpdateStates.get(key);
        if(state == null)
            return;

        synchronized(state)
        {
            state.editInFlight = false;
            state.lastEditAt = System.currentTimeMillis();
            if(removeState)
            {
                state.pending = false;
                panelUpdateStates.remove(key, state);
                return;
            }
            if(state.pending && !hasScheduledUpdate(state))
            {
                long delayMillis = Math.max(0L,
                        state.lastEditAt + state.pendingMinEditIntervalMillis - System.currentTimeMillis());
                schedulePanelUpdate(key, state, delayMillis);
            }
        }
    }

    static long computePanelUpdateDelayMillis(long nowMillis, long lastEditAtMillis,
                                              long minEditIntervalMillis, long debounceMillis)
    {
        long earliestEditAt = Math.max(nowMillis + Math.max(0L, debounceMillis),
                lastEditAtMillis + Math.max(0L, minEditIntervalMillis));
        return Math.max(0L, earliestEditAt - nowMillis);
    }

    static boolean shouldInspectPanelDistance(long latestMessageId, long panelMessageId)
    {
        return latestMessageId == 0L || Long.compareUnsigned(latestMessageId, panelMessageId) > 0;
    }

    static boolean shouldMovePanel(int messagesAfterPanel, int threshold)
    {
        return threshold > 0 && messagesAfterPanel >= threshold;
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
        return BUTTON_RESTART.equals(componentId)
                || BUTTON_PLAY_PAUSE.equals(componentId)
                || BUTTON_SKIP.equals(componentId)
                || BUTTON_QUEUE.equals(componentId)
                || BUTTON_LIKE.equals(componentId)
                || BUTTON_LOOP.equals(componentId)
                || BUTTON_SHUFFLE.equals(componentId)
                || BUTTON_LYRICS.equals(componentId)
                || BUTTON_STOP.equals(componentId);
    }

    private boolean isStopConfirmationButton(String componentId)
    {
        return componentId != null
                && (componentId.startsWith(BUTTON_STOP_CONFIRM_PREFIX)
                || componentId.startsWith(BUTTON_STOP_CANCEL_PREFIX));
    }

    private void handleRestart(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null || !requireListening(event) || !requireDJ(event))
            return;

        AudioTrack track = handler.getPlayer().getPlayingTrack();
        if(track == null || !track.isSeekable())
        {
            event.reply(bot.getConfig().getError() + " This track cannot be restarted.").setEphemeral(true).queue();
            return;
        }

        track.setPosition(0L);
        handler.getPlayer().setPaused(false);
        event.deferEdit().queue();
        requestButtonPanelUpdate(event);
    }

    private void handlePlayPause(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null || !requireListening(event) || !requireDJ(event))
            return;

        handler.getPlayer().setPaused(!handler.getPlayer().isPaused());
        event.deferEdit().queue();
        requestButtonPanelUpdate(event);
    }

    private void handleSkip(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null)
            return;

        AudioTrack playing = handler.getPlayer().getPlayingTrack();
        String title = trackTitle(playing);
        if(checkDJPermission(event.getMember(), event.getGuild()))
        {
            handler.getPlayer().stopTrack();
            event.reply(bot.getConfig().getSuccess() + " Skipped **" + title + "**.").setEphemeral(true).queue();
            return;
        }

        if(!requireListening(event))
            return;

        RequestMetadata rm = handler.getRequestMetadata();
        double skipRatio = bot.getSettingsManager().getSettings(event.getGuild()).getSkipRatio();
        if(skipRatio == -1)
            skipRatio = bot.getConfig().getSkipRatio();

        if(event.getUser().getIdLong() == rm.getOwner() || skipRatio == 0)
        {
            handler.getPlayer().stopTrack();
            event.reply(bot.getConfig().getSuccess() + " Skipped **" + title + "**.").setEphemeral(true).queue();
            return;
        }

        boolean addedVote = handler.getVotes().add(event.getUser().getId());
        AudioChannel channel = event.getGuild().getSelfMember().getVoiceState().getChannel();
        int listeners = (int)channel.getMembers().stream()
                .filter(m -> !m.getUser().isBot() && !m.getVoiceState().isDeafened()).count();
        int required = Math.max(1, (int)Math.ceil(listeners * skipRatio));
        int skippers = (int)channel.getMembers().stream()
                .filter(m -> handler.getVotes().contains(m.getUser().getId())).count();

        if(skippers >= required)
        {
            handler.getPlayer().stopTrack();
            event.reply(bot.getConfig().getSuccess() + " Vote passed: skipped **" + title + "** (`"
                    + skippers + "/" + required + "`).").setEphemeral(true).queue();
        }
        else
        {
            requestButtonPanelUpdate(event);
            String prefix = addedVote ? bot.getConfig().getSuccess() + " Vote counted: "
                    : bot.getConfig().getWarning() + " You already voted: ";
            event.reply(prefix + "`" + skippers + "/" + required + "` needed from `"
                    + listeners + "` listener" + (listeners == 1 ? "" : "s") + ".")
                    .setEphemeral(true).queue();
        }
    }

    private void handleQueue(ButtonInteractionEvent event)
    {
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        if(handler == null)
        {
            event.reply(bot.getConfig().getWarning() + " Nothing is currently playing.").setEphemeral(true).queue();
            return;
        }

        List<QueuedTrack> list = handler.getQueue().getList();
        if(list.isEmpty())
        {
            AutoplayMode autoplayMode = bot.getSettingsManager().getSettings(event.getGuild()).getAutoplayMode();
            String message = bot.getConfig().getWarning() + " There is no music in the queue.";
            if(autoplayMode != AutoplayMode.OFF)
                message += " Autoplay `" + autoplayMode.getUserFriendlyName() + "` will choose the next track.";
            event.reply(message).setEphemeral(true).queue();
            return;
        }

        StringBuilder description = new StringBuilder();
        AudioTrack current = handler.getPlayer().getPlayingTrack();
        if(current != null)
            description.append(handler.getStatusEmoji()).append(" Now: **")
                    .append(trackTitle(current)).append("**\n\n");

        int limit = Math.min(list.size(), QUEUE_PREVIEW_LIMIT);
        for(int i = 0; i < limit; i++)
            description.append("`").append(i + 1).append(".` ")
                    .append(AudioHandler.formatQueuedTrackLine(list.get(i))).append('\n');
        if(list.size() > limit)
            description.append("\n...and `").append(list.size() - limit).append("` more.");

        event.replyEmbeds(new EmbedBuilder()
                .setColor(event.getGuild().getSelfMember().getColor())
                .setTitle("Queue | " + list.size() + " queued | " + queueDurationText(list))
                .setDescription(description.toString())
                .build()).setEphemeral(true).queue();
    }

    private void handleLike(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null)
            return;

        AudioTrack track = handler.getPlayer().getPlayingTrack();
        try
        {
            bot.getUserPlaylistService().getOrCreateLikedPlaylist(event.getUser().getIdLong());
            PlaylistTrack playlistTrack = PlaylistTrack.fromAudioTrack(track, track.getInfo().uri);
            AddResult result = bot.getUserPlaylistService().addTrack(event.getUser().getIdLong(),
                    UserPlaylistService.LIKED_SONGS, playlistTrack);
            if(result.getAdded() == 0 && result.getSkippedDuplicates() > 0)
            {
                Optional<PlaylistTrack> removed = bot.getUserPlaylistService().removeTrack(event.getUser().getIdLong(),
                        UserPlaylistService.LIKED_SONGS, playlistTrack);
                event.reply(formatUnlikeResult(track, removed.isPresent())).setEphemeral(true).queue();
                return;
            }
            event.reply(formatLikeResult(track)).setEphemeral(true).queue();
        }
        catch(PlaylistException ex)
        {
            event.reply(bot.getConfig().getError() + " " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private String formatLikeResult(AudioTrack track)
    {
        return bot.getConfig().getSuccess() + " Added **" + trackTitle(track) + "** to your Liked Songs.";
    }

    private String formatUnlikeResult(AudioTrack track, boolean removed)
    {
        String title = trackTitle(track);
        if(removed)
            return bot.getConfig().getSuccess() + " Removed **" + title + "** from your Liked Songs.";
        return bot.getConfig().getWarning() + " **" + title + "** was already removed from your Liked Songs.";
    }

    private static String queueDurationText(List<QueuedTrack> list)
    {
        long total = 0L;
        for(QueuedTrack queuedTrack : list)
        {
            AudioTrack track = queuedTrack.getTrack();
            if(track == null || track.getDuration() == Long.MAX_VALUE
                    || (track.getInfo() != null && track.getInfo().isStream))
                return "LIVE";

            long duration = Math.max(0L, track.getDuration());
            if(Long.MAX_VALUE - total < duration)
                return "LIVE";
            total += duration;
        }
        return TimeUtil.formatTime(total);
    }

    private static String trackTitle(AudioTrack track)
    {
        if(track == null || track.getInfo() == null || track.getInfo().title == null || track.getInfo().title.isBlank())
            return "the current track";
        return FormatUtil.filter(track.getInfo().title);
    }

    private void handleLoop(ButtonInteractionEvent event)
    {
        if(requirePlaying(event) == null || !requireListening(event) || !requireDJ(event))
            return;

        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        settings.setRepeatMode(nextRepeatMode(settings.getRepeatMode()));
        event.deferEdit().queue();
        requestButtonPanelUpdate(event);
    }

    private void handleShuffle(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null || !requireListening(event))
            return;

        boolean fullQueue = checkDJPermission(event.getMember(), event.getGuild());
        int shuffled = fullQueue ? handler.getQueue().shuffleAll() : handler.getQueue().shuffle(event.getUser().getIdLong());
        switch(shuffled)
        {
            case 0:
                event.reply(bot.getConfig().getError() + " "
                        + (fullQueue ? "There is no music in the queue to shuffle." : "You don't have any music in the queue to shuffle."))
                        .setEphemeral(true).queue();
                break;
            case 1:
                event.reply(bot.getConfig().getWarning() + " "
                        + (fullQueue ? "There is only one song in the queue." : "You only have one song in the queue."))
                        .setEphemeral(true).queue();
                break;
            default:
                requestButtonPanelUpdate(event);
                event.reply(bot.getConfig().getSuccess() + " "
                        + (fullQueue ? "Shuffled the full queue (`" + shuffled + "` entries)."
                        : "Shuffled your `" + shuffled + "` queued entries."))
                        .setEphemeral(true).queue();
                break;
        }
    }

    private void handleStop(ButtonInteractionEvent event)
    {
        AudioHandler handler = requirePlaying(event);
        if(handler == null || !requireDJ(event))
            return;

        String confirmId = BUTTON_STOP_CONFIRM_PREFIX + event.getGuild().getId() + ":" + event.getUser().getId();
        String cancelId = BUTTON_STOP_CANCEL_PREFIX + event.getGuild().getId() + ":" + event.getUser().getId();
        event.reply(bot.getConfig().getWarning() + " Stop playback and clear the queue?")
                .setEphemeral(true)
                .setComponents(ActionRow.of(
                        Button.danger(confirmId, "Stop"),
                        Button.secondary(cancelId, "Cancel")))
                .queue();
    }

    private void handleStopConfirmation(ButtonInteractionEvent event)
    {
        if(!event.getComponentId().endsWith(":" + event.getUser().getId()))
        {
            event.reply(bot.getConfig().getError() + " This stop confirmation is not for you.")
                    .setEphemeral(true).queue();
            return;
        }

        if(event.getComponentId().startsWith(BUTTON_STOP_CANCEL_PREFIX))
        {
            event.editMessage(bot.getConfig().getWarning() + " Stop cancelled.")
                    .setComponents(Collections.emptyList())
                    .queue();
            return;
        }

        if(event.getGuild() == null)
        {
            event.editMessage(bot.getConfig().getError() + " This confirmation is no longer valid.")
                    .setComponents(Collections.emptyList())
                    .queue();
            return;
        }

        if(!checkDJPermission(event.getMember(), event.getGuild()))
        {
            event.editMessage(bot.getConfig().getError() + " You need DJ permissions to stop playback.")
                    .setComponents(Collections.emptyList())
                    .queue();
            return;
        }

        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        if(handler == null || !handler.isMusicPlaying(event.getJDA()))
        {
            event.editMessage(bot.getConfig().getWarning() + " Nothing is currently playing.")
                    .setComponents(Collections.emptyList())
                    .queue();
            updatePanels(event.getGuild());
            return;
        }

        handler.stopAndClear();
        bot.closeAudioConnection(event.getGuild().getIdLong());
        event.editMessage(bot.getConfig().getSuccess() + " Playback stopped and the queue was cleared.")
                .setComponents(Collections.emptyList())
                .queue();
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
        private long lastEditAt;
        private long pendingMinEditIntervalMillis = PANEL_MIN_EDIT_INTERVAL_MILLIS;
        private long scheduledUpdateAt;
        private ScheduledFuture<?> scheduledUpdate;

        private void markPending(long minEditIntervalMillis)
        {
            pendingMinEditIntervalMillis = pending
                    ? Math.min(pendingMinEditIntervalMillis, minEditIntervalMillis)
                    : minEditIntervalMillis;
            pending = true;
        }
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
