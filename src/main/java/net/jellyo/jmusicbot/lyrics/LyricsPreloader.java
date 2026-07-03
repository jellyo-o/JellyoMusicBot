package com.jagrosh.jmusicbot.lyrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * De-duplicates and asynchronously warms lyrics for a set of query keys. As the
 * queue advances one track at a time the next-N window overlaps heavily; the
 * bounded LRU of attempted keys prevents redundant fetches (and repeated misses
 * for songs the provider doesn't have).
 */
public class LyricsPreloader
{
    /** Warms a single query key (e.g. LyricsService::fetchAndCache). */
    public interface Warmer { void warm(String query); }

    private final Executor executor;
    private final Warmer warmer;
    private final Set<String> attempted;

    public LyricsPreloader(Executor executor, Warmer warmer, int maxRemembered)
    {
        this.executor = executor;
        this.warmer = warmer;
        final int cap = Math.max(1, maxRemembered);
        this.attempted = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(16, 0.75f, false)
        {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest)
            {
                return size() > cap;
            }
        });
    }

    /** Submit each not-yet-attempted key for warming; blank keys are ignored. */
    public void preloadKeys(List<String> keys)
    {
        if(keys == null)
            return;
        for(String key : keys)
        {
            if(key == null || key.trim().isEmpty())
                continue;
            final String q = key.trim();
            boolean fresh;
            synchronized(attempted)
            {
                fresh = attempted.add(q);
            }
            if(fresh)
                executor.execute(() -> warmer.warm(q));
        }
    }
}
