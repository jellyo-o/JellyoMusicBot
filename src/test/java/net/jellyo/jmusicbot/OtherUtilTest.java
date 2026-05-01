package com.jagrosh.jmusicbot;

import com.jagrosh.jmusicbot.utils.OtherUtil;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class OtherUtilTest
{
    @Test
    public void compareVersionsHandlesForkCalverPrereleases()
    {
        assertTrue(OtherUtil.compareVersions("2026.5.0", "2026.5.0-a1") > 0);
        assertTrue(OtherUtil.compareVersions("2026.5.0-b1", "2026.5.0-a1") > 0);
        assertTrue(OtherUtil.compareVersions("2026.5.0-rc1", "2026.5.0-b1") > 0);
        assertTrue(OtherUtil.compareVersions("2026.5.0-a10", "2026.5.0-a2") > 0);
        assertTrue(OtherUtil.compareVersions("2026.5.1", "2026.5.0") > 0);
        assertTrue(OtherUtil.compareVersions("2026.6.0", "2026.5.9") > 0);
    }
}
