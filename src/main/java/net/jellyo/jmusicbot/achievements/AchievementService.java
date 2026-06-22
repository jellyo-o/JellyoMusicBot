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
package com.jagrosh.jmusicbot.achievements;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.economy.EconomyEvent;
import com.jagrosh.jmusicbot.economy.EconomyObserver;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyStore;
import com.jagrosh.jmusicbot.economy.UserProfile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates and grants achievements in response to economy activity. Registered
 * as the {@link EconomyObserver}, so any currency/XP/stat change triggers a
 * check. Newly-unlocked achievements grant their bonus currency and XP and are
 * announced to the user by DM (best effort). The full earned set is always
 * visible via the stats/achievements commands.
 */
public class AchievementService implements EconomyObserver
{
    private static final Logger LOG = LoggerFactory.getLogger(AchievementService.class);

    private final Bot bot;

    public AchievementService(Bot bot)
    {
        this.bot = bot;
    }

    @Override
    public void onEconomyEvent(long userId, EconomyEvent event, int localHour, MessageChannel announceChannel)
    {
        evaluate(userId, event, localHour, announceChannel);
    }

    /**
     * Grants every newly-satisfied achievement for the user, awarding bonuses
     * and announcing unlocks. Re-runs until stable so a bonus that crosses a
     * further threshold unlocks in the same pass.
     *
     * @param announceChannel channel to announce unlocks in (tagging the user); null falls back to a DM
     * @return the achievements unlocked by this evaluation (possibly empty)
     */
    public List<Achievement> evaluate(long userId, EconomyEvent event, int localHour, MessageChannel announceChannel)
    {
        EconomyService economy = bot.getEconomyService();
        if(economy == null || !economy.isEnabled())
            return Collections.emptyList();
        EconomyStore store = economy.getStore();
        if(store == null)
            return Collections.emptyList();

        long now = Instant.now().getEpochSecond();
        List<Achievement> newlyEarned = new ArrayList<>();
        int maxPasses = Achievement.values().length + 1;
        for(int pass = 0; pass < maxPasses; pass++)
        {
            Set<String> earned = store.earnedAchievementIds(userId);
            if(earned.size() >= Achievement.values().length)
                break;
            UserProfile profile = store.getProfile(userId);
            boolean changed = false;
            for(Achievement achievement : Achievement.values())
            {
                if(earned.contains(achievement.getId()))
                    continue;
                if(!achievement.isEarned(profile, event, localHour))
                    continue;
                if(store.grantAchievement(userId, achievement.getId(), now))
                {
                    economy.award(userId, achievement.getCoinReward(), achievement.getXpReward(), announceChannel);
                    newlyEarned.add(achievement);
                    changed = true;
                }
            }
            if(!changed)
                break;
        }

        if(!newlyEarned.isEmpty())
            announce(userId, newlyEarned, announceChannel);
        return newlyEarned;
    }

    private void announce(long userId, List<Achievement> newlyEarned, MessageChannel channel)
    {
        String message = formatUnlockMessage(newlyEarned);
        // Prefer announcing in chat, tagging only the user.
        if(channel != null)
        {
            try
            {
                channel.sendMessage("<@" + userId + ">\n" + message)
                        .setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                        .queue(success -> {}, failure ->
                                LOG.debug("Could not announce achievement for {}: {}", userId, failure.toString()));
                return;
            }
            catch(RuntimeException ex)
            {
                LOG.debug("Failed to announce achievement in channel for {}; falling back to DM", userId, ex);
            }
        }
        // Fallback (e.g. passive listening unlocks with no channel): DM the user.
        JDA jda = bot.getJDA();
        if(jda == null)
            return;
        try
        {
            jda.retrieveUserById(userId)
                    .flatMap(user -> user.openPrivateChannel())
                    .flatMap(privateChannel -> privateChannel.sendMessage(message))
                    .queue(success -> {}, failure ->
                            LOG.debug("Could not DM achievement unlock to {}: {}", userId, failure.toString()));
        }
        catch(Exception ex)
        {
            LOG.debug("Failed to send achievement DM to {}", userId, ex);
        }
    }

    public static String formatUnlockMessage(List<Achievement> achievements)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(achievements.size() == 1
                ? "🏆 **Achievement unlocked!**"
                : "🏆 **" + achievements.size() + " achievements unlocked!**");
        for(Achievement achievement : achievements)
        {
            sb.append("\n\n").append(achievement.getEmoji()).append(" **")
                    .append(achievement.getDisplayName()).append("** — ").append(achievement.getDescription());
            String reward = rewardText(achievement);
            if(!reward.isEmpty())
                sb.append("\n Reward: ").append(reward);
        }
        return sb.toString();
    }

    public static String rewardText(Achievement achievement)
    {
        StringBuilder sb = new StringBuilder();
        if(achievement.getCoinReward() > 0)
            sb.append(EconomyService.coins(achievement.getCoinReward()));
        if(achievement.getXpReward() > 0)
        {
            if(sb.length() > 0)
                sb.append(" + ");
            sb.append(String.format("%,d", achievement.getXpReward())).append(" XP");
        }
        return sb.toString();
    }
}
