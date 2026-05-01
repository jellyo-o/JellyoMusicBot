package com.jagrosh.jmusicbot.utils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DependencyUpdateCheckerTest
{
    @Test
    public void parseLatestMavenVersionPrefersRelease()
            throws Exception
    {
        String metadata = "<metadata><versioning>"
                + "<latest>2.0.0-SNAPSHOT</latest>"
                + "<release>1.9.0</release>"
                + "<versions><version>1.8.0</version><version>1.9.0</version></versions>"
                + "</versioning></metadata>";

        assertEquals("1.9.0", DependencyUpdateChecker.parseLatestMavenVersion(metadata));
    }

    @Test
    public void parseProjectDependencyVersionsResolvesPomProperties()
            throws Exception
    {
        String pom = "<project>"
                + "<properties><jda.version>6.4.0</jda.version></properties>"
                + "<dependencies><dependency>"
                + "<groupId>net.dv8tion</groupId>"
                + "<artifactId>JDA</artifactId>"
                + "<version>${jda.version}</version>"
                + "</dependency></dependencies>"
                + "</project>";

        Map<String, String> versions = DependencyUpdateChecker.parseProjectDependencyVersions(
                new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8)));

        assertEquals("6.4.0", versions.get("net.dv8tion:JDA"));
    }

    @Test
    public void dependencyComparisonHandlesSemanticAndPinnedCommitVersions()
    {
        assertTrue(DependencyUpdateChecker.isNewerDependencyVersion("6.3.1", "6.4.0"));
        assertFalse(DependencyUpdateChecker.isNewerDependencyVersion("6.3.1", "6.3.0"));
        assertFalse(DependencyUpdateChecker.isNewerDependencyVersion("ce725965e", "ce725965e"));
        assertTrue(DependencyUpdateChecker.isNewerDependencyVersion("ce725965e", "0123456789"));
    }
}
