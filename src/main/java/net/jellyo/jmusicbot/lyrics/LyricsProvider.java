package com.jagrosh.jmusicbot.lyrics;

import java.io.IOException;
import java.util.Optional;

interface LyricsProvider
{
    Optional<LyricsResult> search(String query, boolean allowDifferentArtistFallback) throws IOException;
}
