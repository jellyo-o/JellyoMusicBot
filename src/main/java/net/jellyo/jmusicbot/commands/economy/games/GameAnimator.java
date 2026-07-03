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
package com.jagrosh.jmusicbot.commands.economy.games;

import com.jagrosh.jmusicbot.Bot;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;

/**
 * Plays a short cosmetic "spin" animation for instant games by editing a message
 * a few times on the dedicated games scheduler, then settling. Each frame only
 * calls {@code editMessageEmbeds(...).queue()} (the REST runs on JDA's pool), so
 * the scheduler thread never blocks.
 *
 * <p>These frames are <b>cosmetic</b>: when the global animation budget is
 * saturated, the animation is skipped and settlement runs immediately, so a busy
 * guild never blocks anyone from playing. (Games whose frames <i>are</i> the
 * decision surface — e.g. crash — do not use this and handle load themselves.)
 */
public final class GameAnimator
{
    /** Cap on concurrent cosmetic animations, to bound scheduler + Discord-edit load. */
    static final int MAX_CONCURRENT = 24;
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private GameAnimator() {}

    /**
     * Edits {@code message} through {@code frames} (one every {@code frameMs}),
     * then runs {@code onSettle}. Falls back to running {@code onSettle}
     * immediately when there are no frames or the budget is saturated.
     */
    public static void play(Bot bot, Message message, List<MessageEmbed> frames, long frameMs, Runnable onSettle)
    {
        if(frames == null || frames.isEmpty() || ACTIVE.get() >= MAX_CONCURRENT)
        {
            onSettle.run();
            return;
        }
        ACTIVE.incrementAndGet();
        ScheduledExecutorService scheduler = bot.getGamesScheduler();
        for(int i = 0; i < frames.size(); i++)
        {
            MessageEmbed frame = frames.get(i);
            scheduler.schedule(() -> message.editMessageEmbeds(frame).queue(x -> {}, t -> {}),
                    frameMs * (i + 1L), TimeUnit.MILLISECONDS);
        }
        scheduler.schedule(() ->
        {
            try
            {
                onSettle.run();
            }
            finally
            {
                ACTIVE.decrementAndGet();
            }
        }, frameMs * (frames.size() + 1L), TimeUnit.MILLISECONDS);
    }
}
