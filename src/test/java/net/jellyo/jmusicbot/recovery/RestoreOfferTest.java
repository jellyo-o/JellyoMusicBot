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

import com.jagrosh.jmusicbot.recovery.CrashRecoveryService.RestoreOffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RestoreOfferTest
{
    @Test
    public void pluralizesTrackCount()
    {
        assertTrue(new RestoreOffer(1, "Song").describe().contains("**1** track ("));
        assertTrue(new RestoreOffer(21, "Song").describe().contains("**21** tracks ("));
    }

    @Test
    public void includesSampleTitleWhenPresent()
    {
        assertTrue(new RestoreOffer(3, "On Top Of The World").describe().contains("On Top Of The World"));
    }

    @Test
    public void omitsSampleWhenBlank()
    {
        assertFalse(new RestoreOffer(3, "  ").describe().contains("e.g."));
        assertFalse(new RestoreOffer(3, null).describe().contains("e.g."));
    }

    @Test
    public void truncatesOverlongSampleTitle()
    {
        String longTitle = "x".repeat(120);
        String described = new RestoreOffer(2, longTitle).describe();
        assertTrue("overlong sample is truncated with an ellipsis", described.contains("…"));
        assertFalse("the full overlong title is not shown verbatim", described.contains(longTitle));
    }
}
