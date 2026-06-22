package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.playlist.PlaylistTrack;
import com.jagrosh.jmusicbot.playlist.PlaylistTrackLoader;
import com.jagrosh.jmusicbot.playlist.SpotifyPlaylistFallback;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.entities.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command to play a track or playlist at the top of the queue.
 *
 * @author John Grosh (modified by ChatGPT)
 */
public class PlaytopCmd extends MusicCommand
{
    private final static Logger LOG = LoggerFactory.getLogger(PlaytopCmd.class);

    private final String loadingEmoji;

    public PlaytopCmd(Bot bot)
    {
        super(bot);
        this.loadingEmoji = bot.getConfig().getLoading();
        this.name = "playtop";
        this.arguments = "<title|URL>";
        this.help = "adds the provided song or playlist to the top of the queue";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
        this.blockDuringGuessMusic = true;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        if(event.getArgs().isEmpty() && event.getMessage().getAttachments().isEmpty())
        {
            event.replyWarning("Please include a song title or URL!");
            return;
        }
        if(bot.getCrashRecoveryService() != null)
        {
            String restorePrompt = bot.getCrashRecoveryService().promptIfRestorePending(event.getGuild());
            if(restorePrompt != null)
            {
                event.replyWarning(restorePrompt);
                return;
            }
        }
        String args = event.getArgs().startsWith("<") && event.getArgs().endsWith(">")
                ? event.getArgs().substring(1,event.getArgs().length()-1)
                : event.getArgs().isEmpty() ? event.getMessage().getAttachments().get(0).getUrl() : event.getArgs();
        LOG.info("Loading prefix playtop request in guild {} ({}); query='{}'",
                event.getGuild().getName(), event.getGuild().getId(), args);
        event.reply(loadingEmoji+" Loading... `["+args+"]`", m -> bot.getPlayerManager().loadItemOrdered(event.getGuild(), args, new ResultHandler(m,event,args,false)));
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
        private final String args;
        private final boolean ytsearch;

        private ResultHandler(Message m, CommandEvent event, String args, boolean ytsearch)
        {
            this.m = m;
            this.event = event;
            this.args = args;
            this.ytsearch = ytsearch;
        }

        private void loadSingle(AudioTrack track)
        {
            if(bot.getGuessMusicService().isActive(event.getGuild()))
            {
                m.editMessage(bot.getGuessMusicService().activeGameBlockMessage()).queue();
                return;
            }
            if(bot.getConfig().isTooLong(track))
            {
                LOG.warn("Rejected prefix playtop track in guild {} ({}): track too long; query='{}'; track={}",
                        event.getGuild().getName(), event.getGuild().getId(), event.getArgs(), describeTrack(track));
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" This track (**"+track.getInfo().title+"**) is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration())+"` > `"+ TimeUtil.formatTime(bot.getConfig().getMaxSeconds()*1000)+"`")).queue();
                return;
            }
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrackToFront(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)))+1;
            LOG.info("Prefix playtop track loaded in guild {} ({}); query='{}'; position={}; track={}",
                    event.getGuild().getName(), event.getGuild().getId(), event.getArgs(), pos, describeTrack(track));
            String addMsg = FormatUtil.filter(event.getClient().getSuccess()+" Added **"+track.getInfo().title
                    +"** (`"+ TimeUtil.formatTime(track.getDuration())+"`) "+(pos==0?"to begin playing":" to the top of the queue"));
            m.editMessage(addMsg).queue();
        }

        private int loadPlaylist(AudioPlaylist playlist)
        {
            if(bot.getGuessMusicService().isActive(event.getGuild()))
            {
                m.editMessage(bot.getGuessMusicService().activeGameBlockMessage()).queue();
                return 0;
            }
            int count = 0;
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            boolean first = handler.getPlayer().getPlayingTrack()==null;
            int index = 0;
            for(AudioTrack track : playlist.getTracks())
            {
                if(bot.getConfig().isTooLong(track))
                {
                    LOG.debug("Skipping too-long prefix playtop playlist track in guild {} ({}); query='{}'; track={}",
                            event.getGuild().getName(), event.getGuild().getId(), event.getArgs(), describeTrack(track));
                    continue;
                }
                if(first)
                {
                    LOG.info("Starting first prefix playtop playlist track immediately in guild {} ({}); query='{}'; track={}",
                            event.getGuild().getName(), event.getGuild().getId(), event.getArgs(), describeTrack(track));
                    handler.getPlayer().playTrack(track);
                    first = false;
                }
                else
                {
                    handler.getQueue().addAt(index, new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                    index++;
                }
                count++;
            }
            LOG.info("Prefix playtop playlist loaded in guild {} ({}); query='{}'; playlist='{}'; acceptedTracks={}; sourceTracks={}",
                    event.getGuild().getName(), event.getGuild().getId(), args,
                    playlist.getName(), count, playlist.getTracks().size());
            return count;
        }

        private boolean trySpotifyPlaylistFallback()
        {
            if(ytsearch || !SpotifyPlaylistFallback.isSpotifyPlaylistUrl(args))
                return false;

            m.editMessage(FormatUtil.filter(SpotifyPlaylistFallback.fallbackNotice(event.getClient().getWarning()))).queue();
            bot.getBlockingThreadpool().submit(() ->
            {
                SpotifyPlaylistFallback.PublicPlaylist playlist;
                try
                {
                    playlist = SpotifyPlaylistFallback.fetch(args);
                }
                catch(Exception ex)
                {
                    LOG.warn("Failed to fetch Spotify public playlist fallback for playtop in guild {} ({}); query='{}'",
                            event.getGuild().getName(), event.getGuild().getId(), args, ex);
                    m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                            + " Spotify did not expose this playlist through the API, and I could not read the public page fallback.")).queue();
                    return;
                }

                List<PlaylistTrack> items = playlist.toPlaylistTracks();
                if(items.isEmpty())
                {
                    LOG.warn("Spotify public playlist fallback found no tracks for playtop in guild {} ({}); query='{}'",
                            event.getGuild().getName(), event.getGuild().getId(), args);
                    m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                            + " Spotify did not expose this playlist through the API, and the public page had no readable tracks.")).queue();
                    return;
                }

                m.editMessage(FormatUtil.filter(loadingEmoji + " Found `" + items.size() + "` tracks from **"
                        + playlist.getName() + "**. Loading them now...")).queue();
                PlaylistTrackLoader.load(bot.getPlayerManager(), bot.getThreadpool(), playlist.getName(), items,
                        bot.getConfig()::isTooLong, result ->
                        {
                            if(bot.getGuessMusicService().isActive(event.getGuild()))
                            {
                                m.editMessage(bot.getGuessMusicService().activeGameBlockMessage()).queue();
                                return;
                            }
                            List<QueuedTrack> queuedTracks = new ArrayList<>();
                            for(List<AudioTrack> itemTracks : result.getTracksByItem())
                                for(AudioTrack track : itemTracks)
                                    queuedTracks.add(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                            if(queuedTracks.isEmpty())
                            {
                                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                                        + " I found `" + items.size() + "` public Spotify tracks, but none could be loaded.")).queue();
                                return;
                            }
                            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
                            handler.addTracksToFront(queuedTracks);
                            LOG.info("Spotify public playlist fallback loaded for playtop in guild {} ({}); query='{}'; playlist='{}'; loadedTracks={}; failed={}; retries={}; elapsedMs={}",
                                    event.getGuild().getName(), event.getGuild().getId(), args, playlist.getName(),
                                    queuedTracks.size(), result.getFailed(), result.getRetries(), result.getElapsedMillis());
                            String message = event.getClient().getSuccess() + " Added `" + queuedTracks.size()
                                    + "` tracks from **" + playlist.getName() + "** to the top of the queue."
                                    + (result.getFailed() == 0 ? "" : "\n" + event.getClient().getWarning()
                                    + " `" + result.getFailed() + "` entries failed.")
                                    + "\n\n" + SpotifyPlaylistFallback.fallbackFootnote(event.getClient().getWarning());
                            m.editMessage(FormatUtil.filter(message)).queue();
                        });
            });
            return true;
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            loadSingle(track);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            if(playlist.getTracks().size()==1 || playlist.isSearchResult())
            {
                AudioTrack single = playlist.getSelectedTrack()==null ? playlist.getTracks().get(0) : playlist.getSelectedTrack();
                loadSingle(single);
            }
            else
            {
                int count = loadPlaylist(playlist);
                if(count==0)
                {
                    LOG.warn("Prefix playtop playlist had no acceptable tracks in guild {} ({}); query='{}'; sourceTracks={}",
                            event.getGuild().getName(), event.getGuild().getId(), event.getArgs(), playlist.getTracks().size());
                    m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" All entries in this playlist "+(playlist.getName()==null ? "" : "(**"+playlist.getName()+"**) ")+"were longer than the allowed maximum (`"+bot.getConfig().getMaxTime()+"`)")).queue();
                }
                else
                {
                    m.editMessage(FormatUtil.filter(event.getClient().getSuccess()+" Added `"+count+"` tracks from "+(playlist.getName()==null?"the playlist":"playlist **"+playlist.getName()+"**")+" to the top of the queue!"+(count<playlist.getTracks().size()?"\n"+event.getClient().getWarning()+" Tracks longer than the allowed maximum (`"+bot.getConfig().getMaxTime()+"`) have been omitted.":""))).queue();
                }
            }
        }

        @Override
        public void noMatches()
        {
            if(ytsearch)
            {
                LOG.info("Prefix playtop found no matches after YouTube fallback in guild {} ({}); query='{}'",
                        event.getGuild().getName(), event.getGuild().getId(), event.getArgs());
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" No results found for `"+event.getArgs()+"`.")).queue();
            }
            else
            {
                if(trySpotifyPlaylistFallback())
                    return;
                LOG.info("Prefix playtop found no direct matches in guild {} ({}); retrying as YouTube search; query='{}'",
                        event.getGuild().getName(), event.getGuild().getId(), args);
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:"+args, new ResultHandler(m,event,args,true));
            }
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            LOG.warn("Prefix playtop load failed in guild {} ({}); query='{}'; severity={}; message={}",
                    event.getGuild().getName(), event.getGuild().getId(), args, throwable.severity, throwable.getMessage(), throwable);
            if(trySpotifyPlaylistFallback())
                return;
            if(throwable.severity==FriendlyException.Severity.COMMON)
                m.editMessage(event.getClient().getError()+" Error loading: "+throwable.getMessage()).queue();
            else
                m.editMessage(event.getClient().getError()+" Error loading track.").queue();
        }
    }
}
