/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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

import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.menu.ButtonMenu;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.playlist.PlaylistTrack;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistException;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistSummary;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.PermissionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class PlayCmd extends MusicCommand
{
    private final static Logger LOG = LoggerFactory.getLogger(PlayCmd.class);

    private final static String LOAD = "\uD83D\uDCE5"; // 📥
    private final static String CANCEL = "\uD83D\uDEAB"; // 🚫
    
    private final String loadingEmoji;
    
    public PlayCmd(Bot bot)
    {
        super(bot);
        this.loadingEmoji = bot.getConfig().getLoading();
        this.name = "play";
        this.arguments = "<title|URL|subcommand>";
        this.help = "plays the provided song";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
        this.children = new Command[]{new PlaylistCmd(bot)};
    }

    @Override
    public void doCommand(CommandEvent event) 
    {
        if(event.getArgs().isEmpty() && event.getMessage().getAttachments().isEmpty())
        {
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            if(handler.getPlayer().getPlayingTrack()!=null && handler.getPlayer().isPaused())
            {
                if(DJCommand.checkDJPermission(event))
                {
                    handler.getPlayer().setPaused(false);
                    event.replySuccess("Resumed **"+handler.getPlayer().getPlayingTrack().getInfo().title+"**.");
                }
                else
                    event.replyError("Only DJs can unpause the player!");
                return;
            }
            StringBuilder builder = new StringBuilder(event.getClient().getWarning()+" Play Commands:\n");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" <song title>` - plays the first result from Youtube");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" <URL>` - plays the provided song, playlist, or stream");
            for(Command cmd: children)
                builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" ").append(cmd.getName()).append(" ").append(cmd.getArguments()).append("` - ").append(cmd.getHelp());
            event.reply(builder.toString());
            return;
        }
        String args = event.getArgs().startsWith("<") && event.getArgs().endsWith(">") 
                ? event.getArgs().substring(1,event.getArgs().length()-1) 
                : event.getArgs().isEmpty() ? event.getMessage().getAttachments().get(0).getUrl() : event.getArgs();
        LOG.info("Loading prefix play request in guild {} ({}); query='{}'",
                event.getGuild().getName(), event.getGuild().getId(), args);
        event.reply(loadingEmoji+" Loading... `["+args+"]`", m -> bot.getPlayerManager().loadItemOrdered(event.getGuild(), args, new ResultHandler(m,event,false)));
    }

    private String describeTrack(AudioTrack track)
    {
        if(track == null)
            return "none";

        String source = track.getSourceManager() == null ? "unknown" : track.getSourceManager().getSourceName();
        return "'" + track.getInfo().title + "' by '" + track.getInfo().author + "'"
                + " [id=" + track.getIdentifier()
                + ", source=" + source
                + ", duration=" + TimeUtil.formatTime(track.getDuration()) + "]";
    }
    
    private class ResultHandler implements AudioLoadResultHandler
    {
        private final Message m;
        private final CommandEvent event;
        private final boolean ytsearch;
        
        private ResultHandler(Message m, CommandEvent event, boolean ytsearch)
        {
            this.m = m;
            this.event = event;
            this.ytsearch = ytsearch;
        }
        
        private void loadSingle(AudioTrack track, AudioPlaylist playlist)
        {
            if(bot.getConfig().isTooLong(track))
            {
                LOG.warn("Rejected prefix play track in guild {} ({}): track too long; query='{}'; track={}",
                        event.getGuild().getName(), event.getGuild().getId(), event.getArgs(), describeTrack(track));
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" This track (**"+track.getInfo().title+"**) is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration())+"` > `"+ TimeUtil.formatTime(bot.getConfig().getMaxSeconds()*1000)+"`")).queue();
                return;
            }
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)))+1;
            LOG.info("Prefix play track loaded in guild {} ({}); query='{}'; position={}; track={}",
                    event.getGuild().getName(), event.getGuild().getId(), event.getArgs(), pos, describeTrack(track));
            String addMsg = FormatUtil.filter(event.getClient().getSuccess()+" Added **"+track.getInfo().title
                    +"** (`"+ TimeUtil.formatTime(track.getDuration())+"`) "+(pos==0?"to begin playing":" to the queue at position "+pos));
            if(playlist==null || !event.getSelfMember().hasPermission(event.getTextChannel(), Permission.MESSAGE_ADD_REACTION))
                m.editMessage(addMsg).queue();
            else
            {
                new ButtonMenu.Builder()
                        .setText(addMsg+"\n"+event.getClient().getWarning()+" This track has a playlist of **"+playlist.getTracks().size()+"** tracks attached. Select "+LOAD+" to load playlist.")
                        .setChoices(LOAD, CANCEL)
                        .setEventWaiter(bot.getWaiter())
                        .setTimeout(30, TimeUnit.SECONDS)
                        .setAction(re ->
                        {
                            if(re.getName().equals(LOAD))
                                m.editMessage(addMsg+"\n"+event.getClient().getSuccess()+" Loaded **"+loadPlaylist(playlist, track)+"** additional tracks!").queue();
                            else
                                m.editMessage(addMsg).queue();
                        }).setFinalAction(m ->
                        {
                            try{ m.clearReactions().queue(); }catch(PermissionException ignore) {}
                        }).build().display(m);
            }
        }
        
        private int loadPlaylist(AudioPlaylist playlist, AudioTrack exclude)
        {
            int[] count = {0};
            playlist.getTracks().stream().forEach((track) -> {
                if(!bot.getConfig().isTooLong(track) && !track.equals(exclude))
                {
                    AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
                    handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                    count[0]++;
                }
            });
            LOG.info("Prefix play playlist loaded in guild {} ({}); query='{}'; playlist='{}'; acceptedTracks={}; sourceTracks={}",
                    event.getGuild().getName(), event.getGuild().getId(), event.getArgs(),
                    playlist.getName(), count[0], playlist.getTracks().size());
            return count[0];
        }
        
        @Override
        public void trackLoaded(AudioTrack track)
        {
            loadSingle(track, null);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            if(playlist.getTracks().size()==1 || playlist.isSearchResult())
            {
                AudioTrack single = playlist.getSelectedTrack()==null ? playlist.getTracks().get(0) : playlist.getSelectedTrack();
                loadSingle(single, null);
            }
            else if (playlist.getSelectedTrack()!=null)
            {
                AudioTrack single = playlist.getSelectedTrack();
                loadSingle(single, playlist);
            }
            else
            {
                int count = loadPlaylist(playlist, null);
                if(playlist.getTracks().size() == 0)
                {
                    LOG.info("Prefix play playlist result was empty in guild {} ({}); query='{}'",
                            event.getGuild().getName(), event.getGuild().getId(), event.getArgs());
                    m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" The playlist "+(playlist.getName()==null ? "" : "(**"+playlist.getName()
                            +"**) ")+" could not be loaded or contained 0 entries")).queue();
                }
                else if(count==0)
                {
                    LOG.warn("Prefix play playlist had no acceptable tracks in guild {} ({}); query='{}'; sourceTracks={}",
                            event.getGuild().getName(), event.getGuild().getId(), event.getArgs(), playlist.getTracks().size());
                    m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" All entries in this playlist "+(playlist.getName()==null ? "" : "(**"+playlist.getName()
                            +"**) ")+"were longer than the allowed maximum (`"+bot.getConfig().getMaxTime()+"`)")).queue();
                }
                else
                {
                    m.editMessage(FormatUtil.filter(event.getClient().getSuccess()+" Found "
                            +(playlist.getName()==null?"a playlist":"playlist **"+playlist.getName()+"**")+" with `"
                            + playlist.getTracks().size()+"` entries; added to the queue!"
                            + (count<playlist.getTracks().size() ? "\n"+event.getClient().getWarning()+" Tracks longer than the allowed maximum (`"
                            + bot.getConfig().getMaxTime()+"`) have been omitted." : ""))).queue();
                }
            }
        }

        @Override
        public void noMatches()
        {
            if(ytsearch)
            {
                LOG.info("Prefix play found no matches after YouTube fallback in guild {} ({}); query='{}'",
                        event.getGuild().getName(), event.getGuild().getId(), event.getArgs());
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" No results found for `"+event.getArgs()+"`.")).queue();
            }
            else
            {
                LOG.info("Prefix play found no direct matches in guild {} ({}); retrying as YouTube search; query='{}'",
                        event.getGuild().getName(), event.getGuild().getId(), event.getArgs());
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:"+event.getArgs(), new ResultHandler(m,event,true));
            }
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            LOG.warn("Prefix play load failed in guild {} ({}); query='{}'; severity={}; message={}",
                    event.getGuild().getName(), event.getGuild().getId(), event.getArgs(), throwable.severity, throwable.getMessage(), throwable);
            if(throwable.severity==Severity.COMMON)
                m.editMessage(event.getClient().getError()+" Error loading: "+throwable.getMessage()).queue();
            else
                m.editMessage(event.getClient().getError()+" Error loading track.").queue();
        }

        
    }
    
    public class PlaylistCmd extends MusicCommand
    {
        public PlaylistCmd(Bot bot)
        {
            super(bot);
            this.name = "playlist";
            this.aliases = new String[]{"pl"};
            this.arguments = "<name>";
            this.help = "plays the provided playlist";
            this.beListening = true;
            this.bePlaying = false;
        }

        @Override
        public void doCommand(CommandEvent event) 
        {
            if(event.getArgs().isEmpty())
            {
                event.reply(event.getClient().getError()+" Please include a playlist name.");
                return;
            }
            PlaylistSummary playlist;
            List<PlaylistTrack> items;
            try
            {
                playlist = bot.getUserPlaylistService().resolveVisible(event.getAuthor().getIdLong(), event.getArgs())
                        .orElseThrow(() -> new PlaylistException("Playlist `" + event.getArgs() + "` does not exist."));
                items = bot.getUserPlaylistService().listItems(playlist.getId());
                if(playlist.isLegacyShuffle())
                    Collections.shuffle(items);
            }
            catch(PlaylistException ex)
            {
                event.replyError(ex.getMessage());
                return;
            }
            if(items.isEmpty())
            {
                event.reply(event.getClient().getWarning()+" Playlist `"+playlist.getName()+"` is empty.");
                return;
            }
            event.getChannel().sendMessage(loadingEmoji+" Loading playlist **"+playlist.getName()+"**... ("+items.size()+" items)").queue(m ->
            {
                LOG.info("Loading user playlist '{}' in guild {} ({}) with {} configured entries",
                        playlist.getName(), event.getGuild().getName(), event.getGuild().getId(), items.size());
                AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
                AtomicInteger remaining = new AtomicInteger(items.size());
                AtomicInteger loaded = new AtomicInteger();
                AtomicInteger failed = new AtomicInteger();
                for(PlaylistTrack item : items)
                {
                    bot.getPlayerManager().loadItemOrdered(event.getGuild(), item.getLoadQuery(), new AudioLoadResultHandler()
                    {
                        private void done()
                        {
                            if(remaining.decrementAndGet() == 0)
                            {
                                LOG.info("User playlist '{}' loaded in guild {} ({}); loadedTracks={}; errors={}",
                                        playlist.getName(), event.getGuild().getName(), event.getGuild().getId(), loaded.get(), failed.get());
                                String str = event.getClient().getSuccess()+" Loaded **"+loaded.get()+"** tracks!"
                                        +(failed.get() == 0 ? "" : "\n"+event.getClient().getWarning()+" `"+failed.get()+"` entries failed.");
                                m.editMessage(FormatUtil.filter(str)).queue();
                            }
                        }

                        private void addTrack(AudioTrack track)
                        {
                            if(bot.getConfig().isTooLong(track))
                            {
                                failed.incrementAndGet();
                                return;
                            }
                            handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                            loaded.incrementAndGet();
                        }

                        @Override
                        public void trackLoaded(AudioTrack track)
                        {
                            addTrack(track);
                            done();
                        }

                        @Override
                        public void playlistLoaded(AudioPlaylist audioPlaylist)
                        {
                            if(audioPlaylist.isSearchResult())
                            {
                                if(audioPlaylist.getTracks().isEmpty())
                                    failed.incrementAndGet();
                                else
                                    addTrack(audioPlaylist.getTracks().get(0));
                            }
                            else if(audioPlaylist.getSelectedTrack() != null)
                            {
                                addTrack(audioPlaylist.getSelectedTrack());
                            }
                            else
                            {
                                int before = loaded.get();
                                for(AudioTrack track : audioPlaylist.getTracks())
                                    addTrack(track);
                                if(loaded.get() == before)
                                    failed.incrementAndGet();
                            }
                            done();
                        }

                        @Override
                        public void noMatches()
                        {
                            failed.incrementAndGet();
                            done();
                        }

                        @Override
                        public void loadFailed(FriendlyException throwable)
                        {
                            failed.incrementAndGet();
                            LOG.warn("Prefix playlist item load failed in guild {} ({}); playlist='{}'; query='{}'; severity={}; message={}",
                                    event.getGuild().getName(), event.getGuild().getId(), playlist.getName(), item.getLoadQuery(),
                                    throwable.severity, throwable.getMessage(), throwable);
                            done();
                        }
                    });
                }
            });
        }
    }
}
