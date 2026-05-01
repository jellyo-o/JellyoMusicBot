package com.jagrosh.jmusicbot.commands.dj;


import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.CommandParsers;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.queue.AbstractQueue;

/**
 * Command that provides users the ability to move a track in the playlist.
 */
public class MoveTrackCmd extends DJCommand implements UnifiedCommand
{

    public MoveTrackCmd(Bot bot)
    {
        super(bot);
        this.name = "movetrack";
        this.help = "move a track in the current queue to a different position";
        this.arguments = "<from> <to>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.bePlaying = true;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext event)
    {
        int from;
        int to;

        try
        {
            int[] move = CommandParsers.parseMove(event.getArgs());
            from = move[0];
            to = move[1];
        }
        catch (RuntimeException e)
        {
            event.replyError("Please provide two valid indexes.");
            return;
        }

        if (from == to)
        {
            event.replyError("Can't move a track to the same position.");
            return;
        }

        // Validate that from and to are available
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        AbstractQueue<QueuedTrack> queue = handler.getQueue();
        if (isUnavailablePosition(queue, from))
        {
            String reply = String.format("`%d` is not a valid position in the queue!", from);
            event.replyError(reply);
            return;
        }
        if (isUnavailablePosition(queue, to))
        {
            String reply = String.format("`%d` is not a valid position in the queue!", to);
            event.replyError(reply);
            return;
        }

        // Move the track
        QueuedTrack track = queue.moveItem(from - 1, to - 1);
        handler.updateMusicPanels();
        String trackTitle = track.getTrack().getInfo().title;
        String reply = String.format("Moved **%s** from position `%d` to `%d`.", trackTitle, from, to);
        event.replySuccess(reply);
    }

    private static boolean isUnavailablePosition(AbstractQueue<QueuedTrack> queue, int position)
    {
        return (position < 1 || position > queue.size());
    }
}
