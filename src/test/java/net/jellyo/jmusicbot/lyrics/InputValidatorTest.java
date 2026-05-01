package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;

import java.util.Set;

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
}
