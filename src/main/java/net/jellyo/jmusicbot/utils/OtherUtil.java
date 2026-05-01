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
package com.jagrosh.jmusicbot.utils;

import com.jagrosh.jmusicbot.JMusicBot;
import com.jagrosh.jmusicbot.entities.Prompt;
import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.User;
import okhttp3.*;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class OtherUtil
{
    private final static String RELEASE_REPO_OWNER = "jellyo-o";
    private final static String RELEASE_REPO_NAME = "JellyoMusicBot";
    public final static String NEW_VERSION_AVAILABLE = "There is a new version of JMusicBot available!\n"
                    + "Current version: %s\n"
                    + "New Version: %s\n\n"
                    + "Please visit https://github.com/" + RELEASE_REPO_OWNER + "/" + RELEASE_REPO_NAME + "/releases/latest to get the latest release.";
    private final static String WINDOWS_INVALID_PATH = "c:\\windows\\system32\\";
    
    /**
     * gets a Path from a String
     * also fixes the windows tendency to try to start in system32
     * any time the bot tries to access this path, it will instead start in the location of the jar file
     * 
     * @param path the string path
     * @return the Path object
     */
    public static Path getPath(String path)
    {
        Path result = Paths.get(path);
        // special logic to prevent trying to access system32
        if(result.toAbsolutePath().toString().toLowerCase().startsWith(WINDOWS_INVALID_PATH))
        {
            try
            {
                result = Paths.get(new File(JMusicBot.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile().getPath() + File.separator + path);
            }
            catch(URISyntaxException ignored) {}
        }
        return result;
    }
    
    /**
     * Loads a resource from the jar as a string
     * 
     * @param clazz class base object
     * @param name name of resource
     * @return string containing the contents of the resource
     */
    public static String loadResource(Object clazz, String name)
    {
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(clazz.getClass().getResourceAsStream(name))))
        {
            StringBuilder sb = new StringBuilder();
            reader.lines().forEach(line -> sb.append("\r\n").append(line));
            return sb.toString().trim();
        }
        catch(IOException ignored)
        {
            return null;
        }
    }
    
    /**
     * Loads image data from a URL
     * 
     * @param url url of image
     * @return inputstream of url
     */
    public static InputStream imageFromUrl(String url)
    {
        if(url==null)
            return null;
        try 
        {
            URL u = new URL(url);
            URLConnection urlConnection = u.openConnection();
            urlConnection.setRequestProperty("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/49.0.2623.112 Safari/537.36");
            return urlConnection.getInputStream();
        }
        catch(IOException | IllegalArgumentException ignore) {}
        return null;
    }
    
    /**
     * Parses an activity from a string
     * 
     * @param game the game, including the action such as 'playing' or 'watching'
     * @return the parsed activity
     */
    public static Activity parseGame(String game)
    {
        if(game==null || game.trim().isEmpty() || game.trim().equalsIgnoreCase("default"))
            return null;
        String lower = game.toLowerCase();
        if(lower.startsWith("playing"))
            return Activity.playing(makeNonEmpty(game.substring(7).trim()));
        if(lower.startsWith("listening to"))
            return Activity.listening(makeNonEmpty(game.substring(12).trim()));
        if(lower.startsWith("listening"))
            return Activity.listening(makeNonEmpty(game.substring(9).trim()));
        if(lower.startsWith("watching"))
            return Activity.watching(makeNonEmpty(game.substring(8).trim()));
        if(lower.startsWith("streaming"))
        {
            String[] parts = game.substring(9).trim().split("\\s+", 2);
            if(parts.length == 2)
            {
                return Activity.streaming(makeNonEmpty(parts[1]), "https://twitch.tv/"+parts[0]);
            }
        }
        return Activity.playing(game);
    }
   
    public static String makeNonEmpty(String str)
    {
        return str == null || str.isEmpty() ? "\u200B" : str;
    }
    
    public static OnlineStatus parseStatus(String status)
    {
        if(status==null || status.trim().isEmpty())
            return OnlineStatus.ONLINE;
        OnlineStatus st = OnlineStatus.fromKey(status);
        return st == null ? OnlineStatus.ONLINE : st;
    }
    
    public static void checkJavaVersion(Prompt prompt)
    {
        if(!System.getProperty("java.vm.name").contains("64"))
            prompt.alert(Prompt.Level.WARNING, "Java Version", 
                    "It appears that you may not be using a supported Java version. Please use 64-bit java.");
    }
    
    public static void checkVersion(Prompt prompt)
    {
        String version = getCurrentVersion();
        String latest = getLatestVersion();

        if(latest != null && compareVersions(latest, version) > 0)
        {
            prompt.alert(Prompt.Level.WARNING, "JMusicBot Version",
                    String.format(NEW_VERSION_AVAILABLE, version, latest));
        }
    }
    
    public static String getCurrentVersion()
    {
        if(JMusicBot.class.getPackage()!=null && JMusicBot.class.getPackage().getImplementationVersion()!=null)
            return JMusicBot.class.getPackage().getImplementationVersion();
        else
            return "UNKNOWN";
    }
    
    public static String getLatestVersion()
    {
        return getLatestVersion(RELEASE_REPO_OWNER, RELEASE_REPO_NAME);
    }

    public static String getLatestVersion(String repoOwner)
    {
        return getLatestVersion(repoOwner, "MusicBot");
    }

    public static String getLatestVersion(String repoOwner, String repoName) {
        try {
            Response response = new OkHttpClient.Builder().build()
                    .newCall(new Request.Builder().get()
                    .url("https://api.github.com/repos/" + repoOwner + "/" + repoName + "/releases/latest").build())
                    .execute();
            ResponseBody body = response.body();
            if (body != null) {
                try (Reader reader = body.charStream()) {
                    JSONObject obj = new JSONObject(new JSONTokener(reader));
                    return obj.getString("tag_name");
                } finally {
                    response.close();
                }
            }
        } catch (IOException | JSONException | NullPointerException ex) {
            return null;
        }
        return null;
    }

    public static int compareVersions(String v1, String v2)
    {
        VersionParts first = VersionParts.parse(v1);
        VersionParts second = VersionParts.parse(v2);

        int length = Math.max(first.numbers.length, second.numbers.length);
        for (int i = 0; i < length; i++)
        {
            int num1 = i < first.numbers.length ? first.numbers[i] : 0;
            int num2 = i < second.numbers.length ? second.numbers[i] : 0;

            if (num1 > num2) return 1;
            if (num1 < num2) return -1;
        }
        if(first.qualifier.isEmpty() && !second.qualifier.isEmpty()) return 1;
        if(!first.qualifier.isEmpty() && second.qualifier.isEmpty()) return -1;
        QualifierParts firstQualifier = QualifierParts.parse(first.qualifier);
        QualifierParts secondQualifier = QualifierParts.parse(second.qualifier);
        int qualifier = firstQualifier.rank - secondQualifier.rank;
        if(qualifier != 0) return qualifier;
        if(firstQualifier.number != secondQualifier.number) return Integer.compare(firstQualifier.number, secondQualifier.number);
        return first.qualifier.compareTo(second.qualifier);
    }

    private static int qualifierRank(String qualifier)
    {
        if(qualifier == null || qualifier.isEmpty()) return 4;
        String q = qualifier.toLowerCase();
        if(q.startsWith("snapshot")) return 0;
        if(q.startsWith("a") || q.startsWith("alpha")) return 1;
        if(q.startsWith("b") || q.startsWith("beta")) return 2;
        if(q.startsWith("rc")) return 3;
        return 0;
    }

    private static class QualifierParts
    {
        private final int rank;
        private final int number;

        private QualifierParts(int rank, int number)
        {
            this.rank = rank;
            this.number = number;
        }

        private static QualifierParts parse(String qualifier)
        {
            if(qualifier == null)
                return new QualifierParts(qualifierRank(""), 0);
            int number = 0;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(qualifier);
            if(matcher.find())
            {
                try
                {
                    number = Integer.parseInt(matcher.group(1));
                }
                catch(NumberFormatException ignored)
                {
                    number = 0;
                }
            }
            return new QualifierParts(qualifierRank(qualifier), number);
        }
    }

    private static class VersionParts
    {
        private final int[] numbers;
        private final String qualifier;

        private VersionParts(int[] numbers, String qualifier)
        {
            this.numbers = numbers;
            this.qualifier = qualifier;
        }

        private static VersionParts parse(String version)
        {
            String clean = version == null ? "" : version.trim();
            if(clean.startsWith("v") || clean.startsWith("V"))
                clean = clean.substring(1);
            String[] qualified = clean.split("-", 2);
            String[] numeric = qualified[0].split("\\.");
            int[] numbers = new int[numeric.length];
            for(int i = 0; i < numeric.length; i++)
            {
                try
                {
                    numbers[i] = Integer.parseInt(numeric[i].replaceAll("[^0-9].*$", ""));
                }
                catch(NumberFormatException ex)
                {
                    numbers[i] = 0;
                }
            }
            return new VersionParts(numbers, qualified.length > 1 ? qualified[1] : "");
        }
    }

    /**
     * Checks if the bot JMusicBot is being run on is supported & returns the reason if it is not.
     * @return A string with the reason, or null if it is supported.
     */
    public static String getUnsupportedBotReason(JDA jda) 
    {
        if (jda.getSelfUser().getFlags().contains(User.UserFlag.VERIFIED_BOT))
            return "The bot is verified. Using JMusicBot in a verified bot is not supported.";

        ApplicationInfo info = jda.retrieveApplicationInfo().complete();
        if (info.isBotPublic())
            return "\"Public Bot\" is enabled. Using JMusicBot as a public bot is not supported. Please disable it in the "
                    + "Developer Dashboard at https://discord.com/developers/applications/" + jda.getSelfUser().getId() + "/bot ."
                    + "You may also need to disable all Installation Contexts at https://discord.com/developers/applications/" 
                    + jda.getSelfUser().getId() + "/installation .";

        return null;
    }
}
