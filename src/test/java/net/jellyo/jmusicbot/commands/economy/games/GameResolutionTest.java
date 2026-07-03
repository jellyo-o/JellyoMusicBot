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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GameResolutionTest
{
    @Test
    public void exactlyOneCallerClaimsUnderContention() throws Exception
    {
        for(int trial = 0; trial < 200; trial++)
        {
            final ResolveGuard guard = new ResolveGuard();
            final int threads = 8;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(threads);
            final AtomicInteger winners = new AtomicInteger();
            for(int i = 0; i < threads; i++)
            {
                new Thread(() ->
                {
                    try { start.await(); } catch(InterruptedException ignored) { }
                    if(guard.claim())
                        winners.incrementAndGet();
                    done.countDown();
                }).start();
            }
            start.countDown();
            done.await();
            assertEquals("exactly one thread may claim resolution", 1, winners.get());
            assertTrue(guard.isResolved());
        }
    }

    @Test
    public void crashBoundaryRaceYieldsExactlyOneOutcome() throws Exception
    {
        // Crash's button (win) and tick (loss) resolve to OPPOSITE outcomes; the CAS
        // winner's outcome must be the one and only settled outcome (arrival-order).
        for(int trial = 0; trial < 500; trial++)
        {
            final ResolveGuard guard = new ResolveGuard();
            final AtomicReference<String> settled = new AtomicReference<>(null);
            final AtomicInteger settlements = new AtomicInteger();
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(2);
            Runnable button = () ->
            {
                try { start.await(); } catch(InterruptedException ignored) { }
                if(guard.claim()) { settled.set("WIN"); settlements.incrementAndGet(); }
                done.countDown();
            };
            Runnable tick = () ->
            {
                try { start.await(); } catch(InterruptedException ignored) { }
                if(guard.claim()) { settled.set("LOSS"); settlements.incrementAndGet(); }
                done.countDown();
            };
            new Thread(button).start();
            new Thread(tick).start();
            start.countDown();
            done.await();
            assertEquals("the game settles exactly once", 1, settlements.get());
            assertNotNull(settled.get());
            assertTrue("WIN".equals(settled.get()) || "LOSS".equals(settled.get()));
        }
    }
}
