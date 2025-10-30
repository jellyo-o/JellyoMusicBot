package com.jagrosh.jmusicbot.lyrics;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RateLimiter {
    private final long intervalMillis;
    private final long burstWindowMillis;
    private final Path lockFile;

    public RateLimiter(long intervalMillis) { this(intervalMillis, Long.getLong("lyrics.rateBurstMillis", 2500L)); }
    public RateLimiter(long intervalMillis, long burstWindowMillis) { this.intervalMillis = Math.max(0, intervalMillis); this.burstWindowMillis = Math.max(0, burstWindowMillis); Path candidate = Path.of("genius-rate.lock"); if (!Files.isWritable(candidate.getParent()==null?Path.of("."):candidate.getParent())) { candidate = Path.of(System.getProperty("user.home"), ".genius-rate.lock"); } this.lockFile = candidate; }
    public void acquire(boolean burstEligible) { if (intervalMillis==0L) return; long now = System.currentTimeMillis(); try { ensureFile(); try (RandomAccessFile raf = new RandomAccessFile(lockFile.toFile(), "rw"); FileChannel ch = raf.getChannel(); FileLock lock = ch.lock()) { long last = readLastTimestamp(raf); long since = now - last; boolean withinBurst = burstEligible && last>0 && since>=0 && since <= burstWindowMillis; if (!withinBurst) { long waitFor = (last + intervalMillis) - now; if (waitFor>0) { try { Thread.sleep(waitFor);} catch (InterruptedException ie) { Thread.currentThread().interrupt(); } now = System.currentTimeMillis(); } raf.setLength(0); raf.seek(0); raf.write(Long.toString(now).getBytes(StandardCharsets.UTF_8)); } } } catch (IOException e) { /* fail open */ } }
    private void ensureFile() throws IOException { if (!Files.exists(lockFile)) { Files.createFile(lockFile); } }
    private long readLastTimestamp(RandomAccessFile raf) throws IOException { raf.seek(0); long len = raf.length(); if (len<=0 || len>64) return 0L; byte[] buf = new byte[(int)len]; raf.readFully(buf); try { return Long.parseLong(new String(buf, StandardCharsets.UTF_8).trim()); } catch (NumberFormatException e) { return 0L; } }
}
