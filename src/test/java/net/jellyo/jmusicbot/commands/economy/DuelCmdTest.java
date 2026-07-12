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
package com.jagrosh.jmusicbot.commands.economy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Locks the argument parsing that both the prefix and slash paths depend on. The slash dispatch
 * hands DuelCmd a raw "opponentId amount" string, so the id must be read as the opponent and the
 * amount must not be mistaken for a snowflake (and vice versa).
 */
public class DuelCmdTest
{
    @Test
    public void parsesRawIdOpponentAndAmountFromSlashArgs()
    {
        String args = "123456789012345678 500";
        assertEquals(123456789012345678L, DuelCmd.findUserId(args));
        assertEquals("500", DuelCmd.findAmountToken(args));
    }

    @Test
    public void parsesMentionOpponentAndAmountFromPrefixArgs()
    {
        String args = "<@!123456789012345678> all";
        assertEquals(123456789012345678L, DuelCmd.findUserId(args));
        assertEquals("all", DuelCmd.findAmountToken(args));
    }

    @Test
    public void amountKeywordsAreNotTreatedAsOpponents()
    {
        assertEquals(-1, DuelCmd.findUserId("half"));
        assertEquals("half", DuelCmd.findAmountToken("<@123456789012345678> half"));
    }

    @Test
    public void snowflakeIsNeverReturnedAsAmount()
    {
        // A lone snowflake has an opponent but no amount, so the command should reject it as
        // missing an amount rather than betting the id.
        assertEquals(123456789012345678L, DuelCmd.findUserId("123456789012345678"));
        assertNull(DuelCmd.findAmountToken("123456789012345678"));
    }
}
