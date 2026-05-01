package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InputValidatorTest
{
    @Test
    public void lookupTermsCleanYoutubeTitleNoise()
    {
        Set<String> terms = InputValidator.lookupTerms("Dua Lipa - Levitating Featuring DaBaby (Official Music Video)");

        assertTrue(terms.contains("dua lipa levitating"));
        assertTrue(terms.contains("levitating"));
    }

    @Test
    public void coverAttributionTitleProducesOriginalArtistSearchVariants()
    {
        String query = "Counting Stars - OneRepublic | Alex Goot, Chrissy Costanza, KHS";

        assertEquals("Counting Stars - OneRepublic", InputValidator.cleanSongQuery(query));

        Set<String> lookupTerms = InputValidator.lookupTerms(query);
        assertTrue(lookupTerms.contains("onerepublic counting stars"));
        assertTrue(lookupTerms.contains("counting stars onerepublic"));

        Set<String> providerQueries = InputValidator.providerQueries(query);
        assertTrue(providerQueries.contains("OneRepublic Counting Stars"));
        assertTrue(providerQueries.contains("OneRepublic - Counting Stars"));
    }

    @Test
    public void bracketedCoverArtistProducesOriginalArtistSearchVariants()
    {
        String query = "Alex Goot - Counting Stars (OneRepublic Cover)";

        Set<String> lookupTerms = InputValidator.lookupTerms(query);
        assertTrue(lookupTerms.contains("onerepublic counting stars"));

        Set<String> providerQueries = InputValidator.providerQueries(query);
        assertTrue(providerQueries.contains("OneRepublic Counting Stars"));
        assertTrue(providerQueries.contains("OneRepublic - Counting Stars"));
    }
}
