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
package com.jagrosh.jmusicbot.guessmusic;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GuessMusicTitleMatcher
{
    private static final Pattern BRACKET_CONTENT = Pattern.compile("[\\[({【「『《〈]\\s*([^\\])}】」』》〉]+?)\\s*[\\])}】」』》〉]");

    private static final List<String> VERSION_LABELS = Arrays.asList(
            "official music video",
            "official lyric video",
            "official lyrics video",
            "official audio",
            "official video",
            "lyric video",
            "lyrics video",
            "music video",
            "official lyrics",
            "official lyric",
            "visualizer",
            "audio only",
            "full audio",
            "full version",
            "album version",
            "single version",
            "acoustic version",
            "piano version",
            "live version",
            "clean version",
            "explicit version",
            "radio edit",
            "video edit",
            "extended mix",
            "radio mix",
            "club mix",
            "original mix",
            "bonus track",
            "deluxe edition",
            "remastered",
            "remaster",
            "remixed",
            "remix",
            "lyrics",
            "lyric",
            "official",
            "mix",
            "mv"
    );

    private static final List<String> FEATURE_MARKERS = Arrays.asList(" feat ", " ft ", " featuring ");
    private static final Set<String> OPTIONAL_WORDS = Set.of(
            "a", "an", "the", "of", "and", "or", "are", "is", "am", "be", "to", "in", "on", "for"
    );

    private GuessMusicTitleMatcher()
    {
    }

    public static ParsedTitle parse(String rawTitle, String rawAuthor)
    {
        String title = rawTitle == null ? "" : rawTitle.trim();
        String author = cleanArtist(rawAuthor);

        title = title.replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        title = stripWrapping(title);
        title = stripVersionDecorations(title, author);

        SplitTitle split = splitArtistTitle(title);
        if(split != null)
        {
            if(author.isEmpty())
            {
                author = cleanArtist(split.artist);
                title = split.title;
            }
            else if(artistLooksLikeAuthor(split.artist, author))
            {
                title = split.title;
            }
            else if(artistLooksLikeAuthor(stripFeaturedArtist(split.title), author))
            {
                title = split.artist;
            }
        }

        title = stripVersionDecorations(title, author);
        title = stripLeadingArtist(title, author);
        title = stripFeaturedArtist(title);
        title = stripVersionDecorations(title, author).trim();

        if(title.isEmpty())
            title = rawTitle == null ? "Unknown track" : rawTitle.trim();

        return new ParsedTitle(title, author, normalize(title), extractAliases(rawTitle, title, author));
    }

    public static boolean matches(String guess, ParsedTitle answer, MatchMode mode)
    {
        if(answer == null || guess == null || guess.trim().isEmpty())
            return false;

        String normalizedGuess = normalize(stripFeaturedArtist(stripVersionDecorations(guess)));
        if(normalizedGuess.isEmpty())
            return false;

        List<String> normalizedAnswers = answer.getNormalizedTitles();
        if(normalizedAnswers.isEmpty())
            return false;

        for(String normalizedAnswer : normalizedAnswers)
            if(matchesNormalized(normalizedGuess, normalizedAnswer, mode))
                return true;
        return false;
    }

    private static boolean matchesNormalized(String normalizedGuess, String normalizedAnswer, MatchMode mode)
    {
        if(normalizedAnswer == null || normalizedAnswer.isEmpty())
            return false;

        if(normalizedGuess.equals(normalizedAnswer))
            return true;
        if(mode == MatchMode.STRICT)
            return false;

        String guessKey = contentKey(normalizedGuess);
        String answerKey = contentKey(normalizedAnswer);
        if(!guessKey.isEmpty() && guessKey.equals(answerKey))
            return true;
        if(guessKey.length() >= 4 && answerKey.contains(guessKey))
            return true;
        if(answerKey.length() >= 4 && guessKey.contains(answerKey))
            return true;

        if(normalizedGuess.length() >= 4 && normalizedAnswer.contains(normalizedGuess))
            return true;
        if(normalizedAnswer.length() >= 4 && normalizedGuess.contains(normalizedAnswer))
            return true;
        if(hasNonLatin(normalizedAnswer)
                && (normalizedGuess.contains(normalizedAnswer) || normalizedAnswer.contains(normalizedGuess)))
            return true;

        return similarity(normalizedGuess, normalizedAnswer) >= 0.82;
    }

    public static String normalize(String value)
    {
        if(value == null)
            return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    static boolean hasNonLatin(String value)
    {
        if(value == null)
            return false;
        return value.codePoints().anyMatch(codePoint -> Character.isLetter(codePoint) && !isLatin(codePoint));
    }

    private static boolean hasLatin(String value)
    {
        if(value == null)
            return false;
        return value.codePoints().anyMatch(GuessMusicTitleMatcher::isLatin);
    }

    private static boolean isLatin(int codePoint)
    {
        return Character.isLetter(codePoint)
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    public static String cleanArtist(String value)
    {
        String artist = value == null ? "" : value.trim();
        artist = artist.replaceAll("(?i)\\s*-\\s*topic$", "")
                .replaceAll("(?i)\\s+topic$", "")
                .replaceAll("(?i)\\s+official$", "")
                .replaceAll("(?i)\\s*vevo$", "")
                .trim();
        return artist;
    }

    private static String contentKey(String normalized)
    {
        if(normalized == null || normalized.isBlank())
            return "";
        return Arrays.stream(normalized.split("\\s+"))
                .filter(word -> !OPTIONAL_WORDS.contains(word))
                .collect(Collectors.joining(" "));
    }

    static String stripVersionDecorations(String value)
    {
        return stripVersionDecorations(value, "");
    }

    private static String stripVersionDecorations(String value, String author)
    {
        if(value == null)
            return "";

        String clean = value;
        String previous;
        do
        {
            previous = clean;
            clean = stripTrailingDecorations(clean, author);
            String normalized = normalize(clean);
            for(String label : VERSION_LABELS)
            {
                String labelPattern = "(?i)(^|\\s|[-|:])" + label.replace(" ", "\\s+") + "($|\\s|[-|:])";
                if(containsNormalizedPhrase(normalized, label))
                    clean = clean.replaceAll(labelPattern, " ").trim();
            }
        }
        while(!previous.equals(clean));

        return stripEmptyBrackets(clean).replaceAll("\\s+", " ").trim();
    }

    private static String stripTrailingDecorations(String value, String author)
    {
        String clean = value == null ? "" : value.trim();
        boolean changed;
        do
        {
            changed = false;
            Bracket bracket = trailingBracket(clean);
            if(bracket != null && isDecorativeLabel(bracket.content))
            {
                clean = clean.substring(0, bracket.start).trim();
                changed = true;
                continue;
            }
            SplitTitle suffix = splitArtistTitle(clean);
            if(suffix != null && author != null && !author.isBlank()
                    && !artistLooksLikeAuthor(suffix.artist, author)
                    && isDecorativeLabel(suffix.title))
            {
                clean = suffix.artist.trim();
                changed = true;
            }
        }
        while(changed);
        return clean;
    }

    private static Bracket trailingBracket(String value)
    {
        if(value.endsWith(")"))
            return trailingBracket(value, '(', ')');
        if(value.endsWith("]"))
            return trailingBracket(value, '[', ']');
        if(value.endsWith("}"))
            return trailingBracket(value, '{', '}');
        return null;
    }

    private static Bracket trailingBracket(String value, char open, char close)
    {
        int end = value.length() - 1;
        if(end < 1 || value.charAt(end) != close)
            return null;
        int start = value.lastIndexOf(open, end);
        if(start < 0)
            return null;
        String content = value.substring(start + 1, end);
        return new Bracket(start, content);
    }

    private static boolean isDecorativeLabel(String content)
    {
        String normalized = normalize(content);
        if(normalized.isEmpty())
            return true;
        for(String label : VERSION_LABELS)
            if(containsNormalizedPhrase(normalized, label))
                return true;
        return normalized.startsWith("from ")
                || normalized.startsWith("from the ")
                || containsNormalizedPhrase(normalized, "live")
                || containsNormalizedPhrase(normalized, "acoustic")
                || containsNormalizedPhrase(normalized, "demo")
                || containsNormalizedPhrase(normalized, "edit")
                || containsNormalizedPhrase(normalized, "version")
                || containsNormalizedPhrase(normalized, "clean")
                || containsNormalizedPhrase(normalized, "explicit")
                || containsNormalizedPhrase(normalized, "revisited")
                || containsNormalizedPhrase(normalized, "reimagined")
                || containsNormalizedPhrase(normalized, "re recorded")
                || containsNormalizedPhrase(normalized, "rerecorded")
                || containsNormalizedPhrase(normalized, "anniversary")
                || containsNormalizedPhrase(normalized, "remix")
                || containsNormalizedPhrase(normalized, "remaster")
                || containsNormalizedPhrase(normalized, "visualizer")
                || containsNormalizedPhrase(normalized, "karaoke");
    }

    private static boolean containsNormalizedPhrase(String normalized, String phrase)
    {
        if(normalized == null || phrase == null || phrase.isBlank())
            return false;
        return (" " + normalized + " ").contains(" " + phrase + " ");
    }

    private static String stripWrapping(String value)
    {
        String clean = value;
        while((clean.startsWith("\"") && clean.endsWith("\""))
                || (clean.startsWith("'") && clean.endsWith("'")))
            clean = clean.substring(1, clean.length() - 1).trim();
        return clean;
    }

    private static List<String> extractAliases(String rawTitle, String parsedTitle, String author)
    {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        collectAliases(aliases, rawTitle, parsedTitle, author);
        collectAliases(aliases, parsedTitle, parsedTitle, author);

        String normalizedTitle = normalize(parsedTitle);
        aliases.removeIf(alias ->
        {
            String normalized = normalize(alias);
            return normalized.isEmpty() || normalized.equals(normalizedTitle);
        });
        return new ArrayList<>(aliases);
    }

    private static void collectAliases(Set<String> aliases, String value, String parsedTitle, String author)
    {
        if(value == null || value.isBlank())
            return;

        Matcher matcher = BRACKET_CONTENT.matcher(value);
        while(matcher.find())
            addAlias(aliases, matcher.group(1), parsedTitle, author);

        String[] slashParts = value.split("\\s*[/／|｜]\\s*");
        if(slashParts.length > 1)
        {
            boolean hasNativePart = Arrays.stream(slashParts).anyMatch(GuessMusicTitleMatcher::hasNonLatin);
            boolean hasLatinPart = Arrays.stream(slashParts).anyMatch(GuessMusicTitleMatcher::hasLatin);
            if(hasNativePart && hasLatinPart)
                for(String part : slashParts)
                    addAlias(aliases, part, parsedTitle, author);
        }
    }

    private static void addAlias(Set<String> aliases, String candidate, String parsedTitle, String author)
    {
        String clean = cleanAlias(candidate, author);
        if(clean.isEmpty())
            return;

        String normalized = normalize(clean);
        String normalizedTitle = normalize(parsedTitle);
        if(normalized.length() < 2 || normalized.equals(normalizedTitle))
            return;
        if(artistLooksLikeAuthor(clean, author))
            return;

        boolean complementsNativeTitle = hasNonLatin(parsedTitle) && hasLatin(clean);
        boolean complementsLatinTitle = hasLatin(parsedTitle) && hasNonLatin(clean);
        if(complementsNativeTitle || complementsLatinTitle)
            aliases.add(clean);
    }

    private static String cleanAlias(String value, String author)
    {
        String clean = value == null ? "" : value.trim();
        clean = stripWrapping(clean);
        clean = stripVersionDecorations(clean, author);
        clean = stripLeadingArtist(clean, author);
        clean = stripFeaturedArtist(clean);
        clean = stripVersionDecorations(clean, author);
        return clean.trim();
    }

    private static String stripEmptyBrackets(String value)
    {
        if(value == null)
            return "";
        return value.replaceAll("\\s*[\\[({]\\s*[\\])}]\\s*", " ").trim();
    }

    private static SplitTitle splitArtistTitle(String value)
    {
        String[] separators = {" - ", " – ", " — ", " | ", " : "};
        for(String separator : separators)
        {
            int index = value.indexOf(separator);
            if(index <= 0 || index >= value.length() - separator.length())
                continue;
            String artist = value.substring(0, index).trim();
            String title = value.substring(index + separator.length()).trim();
            if(!artist.isEmpty() && !title.isEmpty())
                return new SplitTitle(artist, title);
        }
        return null;
    }

    private static boolean artistLooksLikeAuthor(String artist, String author)
    {
        String splitArtist = compactNormalize(artist);
        String providedAuthor = compactNormalize(author);
        return !splitArtist.isEmpty() && !providedAuthor.isEmpty()
                && (providedAuthor.contains(splitArtist) || splitArtist.contains(providedAuthor));
    }

    private static String compactNormalize(String value)
    {
        return normalize(cleanArtist(value)).replace(" ", "");
    }

    private static String stripLeadingArtist(String title, String artist)
    {
        String normalizedArtist = normalize(artist);
        if(normalizedArtist.isEmpty())
            return title;

        String normalizedTitle = normalize(title);
        if(!normalizedTitle.startsWith(normalizedArtist + " "))
            return title;

        String[] words = title.split("\\s+");
        int consumed = 0;
        for(int i = 0; i < words.length; i++)
        {
            consumed += normalize(words[i]).length();
            if(consumed >= normalizedArtist.replace(" ", "").length())
                return join(words, i + 1);
        }
        return title;
    }

    private static String stripFeaturedArtist(String title)
    {
        String clean = title == null ? "" : title.trim();
        clean = clean.replaceAll("(?i)\\s*[\\[({]\\s*(feat\\.?|ft\\.?|featuring)\\s+[^\\])}]+[\\])}]\\s*$", "").trim();
        clean = clean.replaceAll("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.+$", "").trim();
        String padded = " " + clean + " ";
        String lower = padded.toLowerCase(Locale.ROOT);
        int earliest = -1;
        for(String marker : FEATURE_MARKERS)
        {
            int index = lower.indexOf(marker);
            if(index >= 0 && (earliest < 0 || index < earliest))
                earliest = index;
        }
        if(earliest >= 0)
            return padded.substring(0, earliest).trim();
        return clean;
    }

    private static String join(String[] words, int start)
    {
        StringBuilder builder = new StringBuilder();
        for(int i = start; i < words.length; i++)
        {
            if(builder.length() > 0)
                builder.append(' ');
            builder.append(words[i]);
        }
        return builder.toString().trim();
    }

    private static double similarity(String a, String b)
    {
        int distance = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        if(max == 0)
            return 1.0;
        return 1.0 - ((double)distance / max);
    }

    private static int levenshtein(String a, String b)
    {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for(int j = 0; j <= b.length(); j++)
            prev[j] = j;
        for(int i = 1; i <= a.length(); i++)
        {
            curr[0] = i;
            for(int j = 1; j <= b.length(); j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private static final class SplitTitle
    {
        private final String artist;
        private final String title;

        private SplitTitle(String artist, String title)
        {
            this.artist = artist;
            this.title = title;
        }
    }

    private static final class Bracket
    {
        private final int start;
        private final String content;

        private Bracket(int start, String content)
        {
            this.start = start;
            this.content = content;
        }
    }

    public enum MatchMode
    {
        FORGIVING,
        STRICT;

        public String displayName()
        {
            return name().substring(0, 1) + name().substring(1).toLowerCase(Locale.ROOT);
        }

        public static MatchMode parse(String value)
        {
            if(value != null && (value.equalsIgnoreCase("strict") || value.equalsIgnoreCase("exact")))
                return STRICT;
            return FORGIVING;
        }
    }

    public static final class ParsedTitle
    {
        private final String title;
        private final String artist;
        private final String normalizedTitle;
        private final List<String> aliases;
        private final List<String> normalizedTitles;

        private ParsedTitle(String title, String artist, String normalizedTitle, List<String> aliases)
        {
            this.title = title;
            this.artist = artist == null ? "" : artist;
            this.normalizedTitle = normalizedTitle == null ? "" : normalizedTitle;
            this.aliases = aliases == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(aliases));

            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            if(!this.normalizedTitle.isEmpty())
                normalized.add(this.normalizedTitle);
            for(String alias : this.aliases)
            {
                String normalizedAlias = normalize(alias);
                if(!normalizedAlias.isEmpty())
                    normalized.add(normalizedAlias);
            }
            this.normalizedTitles = Collections.unmodifiableList(new ArrayList<>(normalized));
        }

        public String getTitle()
        {
            return title;
        }

        public String getArtist()
        {
            return artist;
        }

        public String getNormalizedTitle()
        {
            return normalizedTitle;
        }

        public List<String> getAliases()
        {
            return aliases;
        }

        public List<String> getNormalizedTitles()
        {
            return normalizedTitles;
        }

        public ParsedTitle withAliasesFrom(ParsedTitle other)
        {
            if(other == null)
                return this;
            LinkedHashSet<String> merged = new LinkedHashSet<>(aliases);
            merged.addAll(other.aliases);
            if(hasNonLatin(title) && hasLatin(other.title))
                merged.add(other.title);
            else if(hasLatin(title) && hasNonLatin(other.title))
                merged.add(other.title);
            return new ParsedTitle(title, artist, normalizedTitle, new ArrayList<>(merged));
        }
    }
}
