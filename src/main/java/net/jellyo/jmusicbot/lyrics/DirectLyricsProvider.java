package com.jagrosh.jmusicbot.lyrics;

import java.io.IOException;
import java.util.Optional;

interface DirectLyricsProvider extends LyricsProvider
{
    Optional<LyricsResult> fetchByUrl(String url) throws IOException;
}
