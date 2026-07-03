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
package com.jagrosh.jmusicbot.economy;

import com.jagrosh.jmusicbot.database.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;

public class EconomyStoreMigrationTest
{
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /** A legacy user_profiles table that predates the biggest_win / rebate columns. */
    private static final String LEGACY_SCHEMA =
            "CREATE TABLE user_profiles ("
            + "user_id INTEGER PRIMARY KEY,"
            + "currency INTEGER NOT NULL DEFAULT 0,"
            + "xp INTEGER NOT NULL DEFAULT 0,"
            + "songs_requested INTEGER NOT NULL DEFAULT 0,"
            + "ms_listened INTEGER NOT NULL DEFAULT 0,"
            + "guesses_correct INTEGER NOT NULL DEFAULT 0,"
            + "guess_wins INTEGER NOT NULL DEFAULT 0,"
            + "games_played INTEGER NOT NULL DEFAULT 0,"
            + "gamble_wins INTEGER NOT NULL DEFAULT 0,"
            + "gamble_losses INTEGER NOT NULL DEFAULT 0,"
            + "gamble_wagered INTEGER NOT NULL DEFAULT 0,"
            + "gamble_net INTEGER NOT NULL DEFAULT 0,"
            + "daily_streak INTEGER NOT NULL DEFAULT 0,"
            + "last_daily_at INTEGER NOT NULL DEFAULT 0,"
            + "last_seen_at INTEGER NOT NULL DEFAULT 0,"
            + "last_username TEXT,"
            + "last_avatar TEXT,"
            + "created_at INTEGER NOT NULL,"
            + "updated_at INTEGER NOT NULL)";

    @Test
    public void migratesLegacySchemaPreservingRowsAndIsIdempotent() throws Exception
    {
        Path db = folder.getRoot().toPath().resolve("jmusicbot.db");
        try(Connection c = Database.open(db); Statement st = c.createStatement())
        {
            st.executeUpdate(LEGACY_SCHEMA);
            st.executeUpdate("INSERT INTO user_profiles(user_id, currency, xp, created_at, updated_at) "
                    + "VALUES(42, 500, 1000, 0, 0)");
        }

        EconomyStore store = new EconomyStore(db);
        store.init(); // adds the missing columns via ALTER TABLE
        store.init(); // must be idempotent — no error, no duplicate columns

        UserProfile p = store.getProfile(42);
        assertEquals("existing currency preserved", 500, p.getCurrency());
        assertEquals("existing xp preserved", 1000, p.getXp());
        assertEquals("new column defaults to zero", 0, p.getBiggestWin());

        // The new columns are usable after migration.
        store.updateBiggestWin(42, 777);
        assertEquals(777, store.getProfile(42).getBiggestWin());
        store.close();
    }

    @Test
    public void addRebateCappedEnforcesDailyCapAndResetsNextDay() throws Exception
    {
        Path db = folder.getRoot().toPath().resolve("jmusicbot.db");
        EconomyStore store = new EconomyStore(db);
        try
        {
            store.init();
            long day = 100;
            long cap = 1000;
            assertEquals("first grant within cap", 400, store.addRebateCapped(7, 400, day, cap));
            assertEquals("second grant fills the cap", 600, store.addRebateCapped(7, 900, day, cap));
            assertEquals("cap exhausted for the day", 0, store.addRebateCapped(7, 900, day, cap));
            assertEquals("rebate credited to balance", 1000, store.getBalance(7));
            // The next day resets the accrual.
            assertEquals("new day resets", 500, store.addRebateCapped(7, 500, day + 1, cap));
            assertEquals(1500, store.getBalance(7));
        }
        finally
        {
            store.close();
        }
    }
}
