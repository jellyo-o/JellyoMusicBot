/*
 * Copyright 2016 John Grosh (jagrosh).
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
package com.jagrosh.jmusicbot;

import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.command.CommandClientBuilder;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.jagrosh.jdautilities.examples.command.*;
import com.jagrosh.jmusicbot.commands.admin.*;
import com.jagrosh.jmusicbot.commands.dj.*;
import com.jagrosh.jmusicbot.commands.economy.*;
import com.jagrosh.jmusicbot.commands.general.*;
import com.jagrosh.jmusicbot.commands.music.*;
import com.jagrosh.jmusicbot.commands.owner.*;
import com.jagrosh.jmusicbot.entities.Prompt;
import com.jagrosh.jmusicbot.gui.GUI;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.security.auth.login.LoginException;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

/**
 *
 * @author John Grosh (jagrosh)
 */
public class JMusicBot 
{
    public final static Logger LOG = LoggerFactory.getLogger(JMusicBot.class);
    private final static int HELP_MESSAGE_LIMIT = 1900;
    public final static Permission[] RECOMMENDED_PERMS = {Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MESSAGE_ADD_REACTION,
                                Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_MANAGE, Permission.MESSAGE_EXT_EMOJI,
                                Permission.VOICE_CONNECT, Permission.VOICE_SPEAK, Permission.NICKNAME_CHANGE};
    public final static GatewayIntent[] INTENTS = {GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.GUILD_VOICE_STATES};
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args)
    {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                LOG.error("FATAL uncaught exception in thread {}", thread.getName(), throwable));
        if(args.length > 0)
            switch(args[0].toLowerCase())
            {
                case "generate-config":
                    BotConfig.writeDefaultConfig();
                    return;
                default:
            }
        startBot();
    }
    
    private static void startBot()
    {
        // create prompt to handle startup
        Prompt prompt = new Prompt("JMusicBot");
        
        // startup checks
        // OtherUtil.checkVersion(prompt);
        OtherUtil.checkJavaVersion(prompt);
        
        // load config
        BotConfig config = new BotConfig(prompt);
        config.load();
        if(!config.isValid())
            return;

        // set log level from config
        Level configuredLevel = parseLogLevel(config.getLogLevel());
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(configuredLevel);
        LOG.info("Loaded config from {}", config.getConfigLocation());
        LOG.info("Root log level set to {}", configuredLevel);
        
        // set up the listener
        EventWaiter waiter = new EventWaiter();
        SettingsManager settings = new SettingsManager();
        Bot bot = new Bot(waiter, config, settings);
        CommandClient client = createCommandClient(config, settings, bot);
        
        
        if(!prompt.isNoGUI())
        {
            try 
            {
                GUI gui = new GUI(bot);
                bot.setGUI(gui);
                gui.init();
            }
            catch(Exception e)
            {
                LOG.error("Could not start GUI. If you are "
                        + "running on a server or in a location where you cannot display a "
                        + "window, please run in nogui mode using the -Dnogui=true flag.");
            }
        }
        
        // attempt to log in and start
        try
        {
            LOG.info("Starting JDA with {} gateway intents", INTENTS.length);
            moe.kyokobot.libdave.DaveFactory daveFactory = new moe.kyokobot.libdave.NativeDaveFactory();
            net.dv8tion.jda.api.audio.dave.DaveSessionFactory daveSessionFactory = new moe.kyokobot.libdave.jda.LDJDADaveSessionFactory(daveFactory);

            JDA jda = JDABuilder.create(config.getToken(), Arrays.asList(INTENTS))
                    .enableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE)
                    .disableCache(CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.EMOJI, CacheFlag.ONLINE_STATUS, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                    .setActivity(config.isGameNone() ? null : Activity.playing("loading..."))
                    .setStatus(config.getStatus()==OnlineStatus.INVISIBLE || config.getStatus()==OnlineStatus.OFFLINE 
                            ? OnlineStatus.INVISIBLE : OnlineStatus.DO_NOT_DISTURB)
                    .addEventListeners(client, waiter, new Listener(bot), new SlashCommandListener(bot))
                    .setBulkDeleteSplittingEnabled(true)
                    .setAudioModuleConfig(new net.dv8tion.jda.api.audio.AudioModuleConfig().withDaveSessionFactory(daveSessionFactory))
                    .build();
            jda.setRequiredScopes("bot", "applications.commands");
            bot.setJDA(jda);
            LOG.info("JDA build completed; current status is {}", jda.getStatus());

            // Slash commands are registered in SlashCommandListener.onReady()

            // check if something about the current startup is not supported
            String unsupportedReason = OtherUtil.getUnsupportedBotReason(jda);
            if (unsupportedReason != null)
            {
                prompt.alert(Prompt.Level.ERROR, "JMusicBot", "JMusicBot cannot be run on this Discord bot: " + unsupportedReason);
                try{ Thread.sleep(5000);}catch(InterruptedException ignored){} // this is awful but until we have a better way...
                jda.shutdown();
                System.exit(1);
            }
            
            // other check that will just be a warning now but may be required in the future
            // check if the user has changed the prefix and provide info about the 
            // message content intent
            if(!"@mention".equals(config.getPrefix()))
            {
                LOG.info("You currently have a custom prefix set. "
                        + "If your prefix is not working, make sure that the 'MESSAGE CONTENT INTENT' is Enabled "
                        + "on https://discord.com/developers/applications/" + jda.getSelfUser().getId() + "/bot");
            }
        }
//        catch (LoginException ex)
//        {
//            prompt.alert(Prompt.Level.ERROR, "JMusicBot", ex + "\nPlease make sure you are "
//                    + "editing the correct config.txt file, and that you have used the "
//                    + "correct token (not the 'secret'!)\nConfig Location: " + config.getConfigLocation());
//            System.exit(1);
//        }
        catch(IllegalArgumentException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "JMusicBot", "Some aspect of the configuration is "
                    + "invalid: " + ex + "\nConfig Location: " + config.getConfigLocation());
            System.exit(1);
        }
        catch(ErrorResponseException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "JMusicBot", ex + "\nInvalid reponse returned when "
                    + "attempting to connect, please make sure you're connected to the internet");
            System.exit(1);
        }
        catch(Exception ex)
        {
            LOG.error("FATAL startup failure while connecting to Discord", ex);
            prompt.alert(Prompt.Level.ERROR, "JMusicBot", ex + "\nFatal startup failure while connecting to Discord");
            System.exit(1);
        }
    }

    private static Level parseLogLevel(String configuredLevel)
    {
        if(configuredLevel != null && configuredLevel.equalsIgnoreCase("fatal"))
            return Level.ERROR;

        Level level = Level.toLevel(configuredLevel, null);
        if(level == null)
        {
            LOG.warn("Unknown loglevel '{}'; falling back to INFO. Valid values: off, fatal, error, warn, info, debug, trace, all", configuredLevel);
            return Level.INFO;
        }
        return level;
    }
    
    private static CommandClient createCommandClient(BotConfig config, SettingsManager settings, Bot bot)
    {
        // instantiate about command
        AboutCommand aboutCommand = new AboutCommand(Color.BLUE.brighter(),
                                "a music bot that is [easy to host yourself!](https://github.com/jagrosh/MusicBot) (v" + OtherUtil.getCurrentVersion() + ")",
                                new String[]{"High-quality music playback", "FairQueue™ Technology", "Easy to host yourself"},
                                RECOMMENDED_PERMS);
        aboutCommand.setIsAuthor(false);
        aboutCommand.setReplacementCharacter("\uD83C\uDFB6"); // 🎶
        
        // set up the command client
        CommandClientBuilder cb = new CommandClientBuilder()
                .setPrefix(config.getPrefix())
                .setAlternativePrefix(config.getAltPrefix())
                .setOwnerId(Long.toString(config.getOwnerId()))
                .setEmojis(config.getSuccess(), config.getWarning(), config.getError())
                .setHelpWord(config.getHelp())
                .setHelpConsumer(event -> sendPrefixHelp(event, config, settings))
                .setLinkedCacheSize(200)
                .setGuildSettingsManager(settings)
                .addCommands(aboutCommand,
                        new PingCommand(),
                        new SettingsCmd(bot),
                        
                        new LyricsCmd(bot),
                        new CorrectLyricsCmd(bot),
                        new GuessMusicCmd(bot),
                        new HostGameCmd(bot),
                        new NowplayingCmd(bot),
                        new PlayCmd(bot),
                        new PlaytopCmd(bot),
                        new PlaylistsCmd(bot),
                        new QueueCmd(bot),
                        new HistoryCmd(bot),
                        new RemoveCmd(bot),
                        new SearchCmd(bot),
                        new SCSearchCmd(bot),
                        new SeekCmd(bot),
                        new ShuffleCmd(bot),
                        new SkipCmd(bot),
                        new AvoidedCmd(bot),
                        new RestoreCmd(bot),

                        new StatsCmd(bot),
                        new BalanceCmd(bot),
                        new DailyCmd(bot),
                        new GambleCmd(bot),
                        new LeaderboardCmd(bot),
                        new AchievementsCmd(bot),

                        new ForceRemoveCmd(bot),
                        new ForceskipCmd(bot),
                        new FilterCmd(bot),
                        new MoveTrackCmd(bot),
                        new PauseCmd(bot),
                        new PlaynextCmd(bot),
                        new AutoplayCmd(bot),
                        new AvoidCmd(bot),
                        new UnavoidCmd(bot),
                        new SleepCmd(bot),
                        new RepeatCmd(bot),
                        new SkiptoCmd(bot),
                        new StopCmd(bot),
                        new VolumeCmd(bot),
                        
                        new PrefixCmd(bot),
                        new QueueTypeCmd(bot),
                        new PreloadLyricsCmd(bot),
                        new AutoLyricsCmd(bot),
                        new SetdjCmd(bot),
                        new SkipratioCmd(bot),
                        new SettcCmd(bot),
                        new SetvcCmd(bot),

                        new DebugCmd(bot),
                        new SetavatarCmd(bot),
                        new SetgameCmd(bot),
                        new SetnameCmd(bot),
                        new SetstatusCmd(bot),
                        new ShutdownCmd(bot)
                );
        
        // enable eval if applicable
        if(config.useEval())
            cb.addCommand(new EvalCmd(bot));
        
        // set status if set in config
        if(config.getStatus() != OnlineStatus.UNKNOWN)
            cb.setStatus(config.getStatus());
        
        // set game
        if(config.getGame() == null)
            cb.useDefaultGame();
        else if(config.isGameNone())
            cb.setActivity(null);
        else
            cb.setActivity(config.getGame());
        
        return cb.build();
    }

    private static void sendPrefixHelp(CommandEvent event, BotConfig config, SettingsManager settings)
    {
        String prefix = getPrefixForHelp(event, config, settings);
        List<String> pages = new ArrayList<>();
        StringBuilder builder = new StringBuilder("**")
                .append(event.getSelfUser().getName())
                .append("** commands:\n")
                .append("Use these prefix commands");
        if(event.getClient().getAltPrefix() != null)
            builder.append(" or the alternate prefix `").append(event.getClient().getAltPrefix()).append("`");
        builder.append(". Slash-only commands are listed separately.\n");

        appendPrefixHelp(pages, builder, "General", prefix, new String[][]{
                {"help", "Show this command list"},
                {"about", "Show bot information"},
                {"ping", "Check bot latency"},
                {"settings", "Show bot settings"}
        });
        appendPrefixHelp(pages, builder, "Music", prefix, new String[][]{
                {"play <title|URL>", "Play a song or URL"},
                {"play playlist <name>", "Play one of your playlists"},
                {"playtop <title|URL>", "Add a song to the top of the queue"},
                {"playlists", "List your playlists, including followed and liked lists"},
                {"nowplaying", "Open or reuse the persistent music panel"},
                {"queue", "Show the queue"},
                {"history <session|guild>", "Show played song history"},
                {"search <query>", "Search YouTube and choose a result"},
                {"scsearch <query>", "Search SoundCloud and choose a result"},
                {"skip", "Vote to skip"},
                {"remove <position|all>", "Remove queued songs"},
                {"shuffle", "Shuffle your queued songs; DJs shuffle the full queue"},
                {"seek <time>", "Seek the current song"},
                {"lyrics [song]", "Fetch lyrics"},
                {"correctlyrics <genius-url> | <song>", "Correct cached lyrics for a song"},
                {"guess [start|status|join|reveal|stop|hints|highlight]", "Play a guess the music game"},
                {"hostgame [start|add|status|join|reveal|stop]", "Host a guess the music game where you pick the songs"}
        });
        appendPlainHelp(pages, builder, "Slash-Only", new String[][]{
                {"/g", "Fast private guess for the active guess the music round"},
                {"/guess <start|settings|join|status|reveal|stop|hints|highlight>", "Control a guess the music game"},
                {"/playlist <list|create|rename|delete|view|play|add|addcurrent|addqueue|remove|move|clear|share|addshared|unshare|unfollow|copy>", "Manage editable, shared, and followed playlists"},
                {"/like source:<current|queue|query>", "Add music to Liked Songs"},
                {"/unlike target:<current|index|query>", "Remove music from Liked Songs"},
                {"/liked action:<view|play>", "View or play Liked Songs"},
                {"/resume", "Resume playback if paused"},
                {"/skipratio [ratio]", "Show or set skip ratio from 0 to 1"}
        });
        appendPrefixHelp(pages, builder, "DJ", prefix, new String[][]{
                {"forceskip", "Force skip"},
                {"pause", "Pause playback; use play to resume"},
                {"stop", "Stop playback and clear the queue"},
                {"volume [0-150]", "Show or set volume"},
                {"filter [off|bassboost|nightcore|8d|vaporwave|tremolo|karaoke|list]", "Show or set audio filter"},
                {"repeat [off|all|single]", "Set repeat mode"},
                {"loop [off|all|single]", "Alias for repeat"},
                {"autoplay [off|smart|related|artist|playlist|server]", "Set autoplay radio mode"},
                {"radio [off|smart|related|artist|playlist|server]", "Alias for autoplay"},
                {"skipto <position>", "Skip to a queue position"},
                {"movetrack <from> <to>", "Move a queued track"},
                {"playnext <title|URL>", "Play a song next"},
                {"forceremove <user>", "Remove a user's queued songs"}
        });
        appendPrefixHelp(pages, builder, "Admin", prefix, new String[][]{
                {"prefix <prefix|none>", "Set server prefix"},
                {"setdj <role|none>", "Set DJ role"},
                {"settc <channel|none>", "Restrict music text channel"},
                {"setvc <channel|none>", "Restrict music voice channel"},
                {"setskip <0-100>", "Set skip percentage"},
                {"queuetype [fair|linear]", "Show or set queue type"},
                {"preloadlyrics [on|off]", "Preload lyrics for upcoming songs"},
                {"autolyrics [on|off]", "Auto-post lyrics when a song starts"}
        });
        if(event.isOwner())
        {
            appendPrefixHelp(pages, builder, "Owner", prefix, new String[][]{
                    {"debug", "Show debug info"},
                    {"setavatar <url>", "Set the bot avatar"},
                    {"setgame <game>", "Set the bot activity"},
                    {"setname <name>", "Set the bot name"},
                    {"setstatus <status>", "Set the bot status"},
                    {"shutdown", "Safely shut down"}
            });
        }
        pages.add(builder.toString());

        event.replyInDm(pages.get(0),
                unused -> {
                    sendRemainingHelpPages(event, pages, 1);
                    if(event.isFromType(net.dv8tion.jda.api.entities.channel.ChannelType.TEXT))
                        event.reactSuccess();
                },
                unused -> event.replyWarning("Help cannot be sent because you are blocking Direct Messages."));
    }

    private static String getPrefixForHelp(CommandEvent event, BotConfig config, SettingsManager settings)
    {
        String prefix = config.getPrefix();
        if(event.getGuild() != null)
        {
            Settings guildSettings = settings.getSettings(event.getGuild());
            if(guildSettings.getPrefix() != null)
                prefix = guildSettings.getPrefix();
        }
        if("@mention".equalsIgnoreCase(prefix))
            return "@" + event.getSelfUser().getName() + " ";
        return prefix;
    }

    private static void appendPrefixHelp(List<String> pages, StringBuilder builder, String category, String prefix, String[][] commands)
    {
        StringBuilder section = new StringBuilder("\n\n__").append(category).append("__:");
        for(String[] command : commands)
            section.append("\n`").append(prefix).append(command[0]).append("` - ").append(command[1]);

        if(builder.length() > 0 && builder.length() + section.length() > HELP_MESSAGE_LIMIT)
        {
            pages.add(builder.toString());
            builder.setLength(0);
        }
        builder.append(section);
    }

    private static void appendPlainHelp(List<String> pages, StringBuilder builder, String category, String[][] commands)
    {
        StringBuilder section = new StringBuilder("\n\n__").append(category).append("__:");
        for(String[] command : commands)
            section.append("\n`").append(command[0]).append("` - ").append(command[1]);

        if(builder.length() > 0 && builder.length() + section.length() > HELP_MESSAGE_LIMIT)
        {
            pages.add(builder.toString());
            builder.setLength(0);
        }
        builder.append(section);
    }

    private static void sendRemainingHelpPages(CommandEvent event, List<String> pages, int index)
    {
        if(index >= pages.size())
            return;

        event.getAuthor().openPrivateChannel().queue(channel ->
                channel.sendMessage(pages.get(index)).queue(
                        unused -> sendRemainingHelpPages(event, pages, index + 1),
                        error -> LOG.warn("Failed to send prefix help page {}", index + 1, error)),
                error -> LOG.warn("Failed to open private channel for prefix help page {}", index + 1, error));
    }
}
