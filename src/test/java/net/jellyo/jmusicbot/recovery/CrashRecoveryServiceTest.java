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
package com.jagrosh.jmusicbot.recovery;

import com.jagrosh.jmusicbot.recovery.QueueSnapshotStore.Entry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code clearSnapshot} is called when a queue plays out naturally, so a finished queue is no longer
 * re-offered as a "saved queue from before". It must drop only the rolling snapshot and never a
 * pending restore the user has not yet acted on.
 */
public class CrashRecoveryServiceTest
{
    private static final long GUILD = 4242L;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private QueueSnapshotStore store;

    @Before
    public void setUp() throws Exception
    {
        Path db = folder.getRoot().toPath().resolve("jmusicbot.db");
        store = new QueueSnapshotStore(db);
        store.init();
    }

    @After
    public void tearDown()
    {
        if(store != null)
            store.close();
    }

    private static List<Entry> entries(int count)
    {
        List<Entry> list = new ArrayList<>();
        for(int i = 0; i < count; i++)
        {
            Entry e = new Entry();
            e.url = "https://example.com/" + i;
            e.title = "Track " + i;
            list.add(e);
        }
        return list;
    }

    @Test
    public void clearSnapshotDropsRollingSnapshot()
    {
        store.save(GUILD, entries(1));
        CrashRecoveryService service = new CrashRecoveryService(null, store);

        service.clearSnapshot(GUILD);

        assertTrue("the naturally-finished queue is no longer restorable", store.load(GUILD).isEmpty());
    }

    @Test
    public void clearSnapshotLeavesPendingRestoreIntact()
    {
        store.savePending(GUILD, entries(3));
        store.save(GUILD, entries(1));
        CrashRecoveryService service = new CrashRecoveryService(null, store);

        service.clearSnapshot(GUILD);

        assertTrue(store.load(GUILD).isEmpty());
        assertEquals("a pending offer the user has not acted on survives", 3, store.loadPending(GUILD).size());
    }

    @Test
    public void clearSnapshotOnEmptyStoreIsANoOp()
    {
        // No snapshot saved: clearing must not throw and must leave both slots empty.
        new CrashRecoveryService(null, store).clearSnapshot(GUILD);

        assertTrue(store.load(GUILD).isEmpty());
        assertTrue(store.loadPending(GUILD).isEmpty());
    }
}
