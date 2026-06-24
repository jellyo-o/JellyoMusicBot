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
import com.jagrosh.jmusicbot.recovery.QueueSnapshotStore.Info;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QueueSnapshotStoreTest
{
    private static final long GUILD = 1234L;

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

    private static Entry entry(String url, String title, long requesterId, long positionMs)
    {
        Entry e = new Entry();
        e.url = url;
        e.title = title;
        e.author = "Author of " + title;
        e.requesterId = requesterId;
        e.requesterName = "user" + requesterId;
        e.positionMs = positionMs;
        return e;
    }

    private static List<Entry> entries(int count)
    {
        List<Entry> list = new ArrayList<>();
        for(int i = 0; i < count; i++)
            list.add(entry("https://example.com/" + i, "Track " + i, 100L + i, i * 1000L));
        return list;
    }

    @Test
    public void savesAndLoadsSnapshotRoundTrip()
    {
        store.save(GUILD, entries(3));

        List<Entry> loaded = store.load(GUILD);
        assertEquals(3, loaded.size());
        assertEquals("https://example.com/0", loaded.get(0).url);
        assertEquals("Track 0", loaded.get(0).title);
        assertEquals(100L, loaded.get(0).requesterId);
        assertEquals(2000L, loaded.get(2).positionMs);
    }

    @Test
    public void peekReportsCountAndSampleTitle()
    {
        store.save(GUILD, entries(5));

        Optional<Info> info = store.peek(GUILD);
        assertTrue(info.isPresent());
        assertEquals(5, info.get().getCount());
        assertEquals("Track 0", info.get().getSampleTitle());
    }

    @Test
    public void savingEmptyListClearsTheSlot()
    {
        store.save(GUILD, entries(2));
        store.save(GUILD, new ArrayList<>());

        assertFalse(store.peek(GUILD).isPresent());
        assertTrue(store.load(GUILD).isEmpty());
    }

    @Test
    public void pendingRestoreSurvivesSnapshotOverwrite()
    {
        // Saved queue that the user is offered to restore...
        store.savePending(GUILD, entries(21));
        // ...then the rolling autosave overwrites the live snapshot with the new song.
        store.save(GUILD, entries(1));

        List<Entry> pending = store.loadPending(GUILD);
        assertEquals("pending restore is not clobbered by the rolling snapshot", 21, pending.size());

        List<Entry> snapshot = store.load(GUILD);
        assertEquals("rolling snapshot tracks only the live queue", 1, snapshot.size());
    }

    @Test
    public void snapshotAndPendingAreIndependentlyDeletable()
    {
        store.save(GUILD, entries(2));
        store.savePending(GUILD, entries(4));

        store.delete(GUILD);
        assertTrue("deleting the snapshot leaves the pending restore", store.peekPending(GUILD).isPresent());
        assertEquals(4, store.loadPending(GUILD).size());

        store.deletePending(GUILD);
        assertFalse(store.peekPending(GUILD).isPresent());
    }

    @Test
    public void moveSnapshotToPendingTransfersEntriesAndClearsSnapshot()
    {
        store.save(GUILD, entries(3));

        List<Entry> moved = store.moveSnapshotToPending(GUILD);
        assertEquals(3, moved.size());
        assertTrue("snapshot slot is cleared after the move", store.load(GUILD).isEmpty());
        assertEquals("entries land in the pending slot", 3, store.loadPending(GUILD).size());
        assertEquals("Track 0", store.loadPending(GUILD).get(0).title);
    }

    @Test
    public void moveSnapshotToPendingReturnsEmptyWhenNothingSaved()
    {
        assertTrue(store.moveSnapshotToPending(GUILD).isEmpty());
        assertFalse(store.peekPending(GUILD).isPresent());
    }

    @Test
    public void peekPendingReportsCountAndSample()
    {
        store.savePending(GUILD, entries(7));

        Optional<Info> info = store.peekPending(GUILD);
        assertTrue(info.isPresent());
        assertEquals(7, info.get().getCount());
        assertEquals("Track 0", info.get().getSampleTitle());
    }

    @Test
    public void deleteExpiredPendingRemovesOnlyRowsBeforeCutoff()
    {
        store.savePending(GUILD, entries(3));

        // A cutoff in the distant past leaves the freshly-saved row alone.
        assertEquals(0, store.deleteExpiredPending(0L));
        assertTrue(store.peekPending(GUILD).isPresent());

        // A cutoff in the far future treats the row as expired.
        assertEquals(1, store.deleteExpiredPending(Long.MAX_VALUE));
        assertFalse(store.peekPending(GUILD).isPresent());
    }

    @Test
    public void deleteExpiredPendingLeavesRollingSnapshotUntouched()
    {
        store.save(GUILD, entries(2));
        store.savePending(GUILD, entries(2));

        store.deleteExpiredPending(Long.MAX_VALUE);

        assertFalse("expired pending is removed", store.peekPending(GUILD).isPresent());
        assertTrue("rolling crash-recovery snapshot is not subject to pending expiry", store.peek(GUILD).isPresent());
    }

    @Test
    public void loadingAbsentSlotsReturnsEmpty()
    {
        assertTrue(store.load(GUILD).isEmpty());
        assertTrue(store.loadPending(GUILD).isEmpty());
        assertFalse(store.peek(GUILD).isPresent());
        assertFalse(store.peekPending(GUILD).isPresent());
    }
}
