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
package com.jagrosh.jmusicbot.audio.filter;

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds Lavaplayer PCM filter chains for the bot's preset effects.
 */
public class AudioFilterChainFactory implements PcmFilterFactory
{
    private static final double NIGHTCORE_SPEED = 1.25d;
    private static final double VAPORWAVE_SPEED = 0.82d;
    private static final double EIGHT_D_ROTATION_HZ = 0.08d;
    private static final double TREMOLO_HZ = 4.0d;
    private static final double TREMOLO_DEPTH = 0.55d;
    private static final double KARAOKE_LEVEL = 0.72d;

    private static final float[] BASS_BOOST_GAINS = {
            0.35f, 0.30f, 0.25f, 0.18f, 0.10f,
            0.04f, 0.00f, -0.03f, -0.05f, -0.05f,
            -0.04f, -0.03f, 0.00f, 0.00f, 0.00f
    };

    private static final float[] VAPORWAVE_GAINS = {
            0.18f, 0.16f, 0.12f, 0.06f, 0.02f,
            0.00f, -0.02f, -0.03f, -0.04f, -0.04f,
            -0.03f, -0.02f, 0.00f, 0.00f, 0.00f
    };

    private final AudioFilterPreset preset;

    public AudioFilterChainFactory(AudioFilterPreset preset)
    {
        this.preset = preset;
    }

    @Override
    public List<AudioFilter> buildChain(AudioTrack track, AudioDataFormat format, UniversalPcmAudioFilter output)
    {
        List<AudioFilter> filters = new ArrayList<>();
        FloatPcmAudioFilter next = output;

        switch(preset)
        {
            case BASS_BOOST:
                addEqualizer(format, next, filters, BASS_BOOST_GAINS);
                break;
            case NIGHTCORE:
                addSpeed(next, filters, NIGHTCORE_SPEED);
                break;
            case EIGHT_D:
                addRotation(format, next, filters);
                break;
            case VAPORWAVE:
                next = addEqualizer(format, next, filters, VAPORWAVE_GAINS);
                addSpeed(next, filters, VAPORWAVE_SPEED);
                break;
            case TREMOLO:
                addTremolo(format, next, filters);
                break;
            case KARAOKE:
                addKaraoke(next, filters);
                break;
            case OFF:
            default:
                break;
        }

        return filters;
    }

    private FloatPcmAudioFilter addEqualizer(AudioDataFormat format, FloatPcmAudioFilter next,
                                             List<AudioFilter> filters, float[] gains)
    {
        if(!Equalizer.isCompatible(format))
            return next;

        Equalizer equalizer = new Equalizer(format.channelCount, next, gains.clone());
        filters.add(0, equalizer);
        return equalizer;
    }

    private FloatPcmAudioFilter addSpeed(FloatPcmAudioFilter next, List<AudioFilter> filters, double speed)
    {
        SpeedPcmAudioFilter filter = new SpeedPcmAudioFilter(next, speed);
        filters.add(0, filter);
        return filter;
    }

    private FloatPcmAudioFilter addRotation(AudioDataFormat format, FloatPcmAudioFilter next,
                                            List<AudioFilter> filters)
    {
        StereoRotationPcmAudioFilter filter = new StereoRotationPcmAudioFilter(next, format.sampleRate, EIGHT_D_ROTATION_HZ);
        filters.add(0, filter);
        return filter;
    }

    private FloatPcmAudioFilter addTremolo(AudioDataFormat format, FloatPcmAudioFilter next,
                                           List<AudioFilter> filters)
    {
        TremoloPcmAudioFilter filter = new TremoloPcmAudioFilter(next, format.sampleRate, TREMOLO_HZ, TREMOLO_DEPTH);
        filters.add(0, filter);
        return filter;
    }

    private FloatPcmAudioFilter addKaraoke(FloatPcmAudioFilter next, List<AudioFilter> filters)
    {
        KaraokePcmAudioFilter filter = new KaraokePcmAudioFilter(next, KARAOKE_LEVEL);
        filters.add(0, filter);
        return filter;
    }

    private static final class SpeedPcmAudioFilter implements FloatPcmAudioFilter
    {
        private final FloatPcmAudioFilter next;
        private final double speed;
        private double position;
        private boolean hasPreviousSamples;
        private float[] previousSamples = new float[0];
        private float[][] outputBuffers = new float[0][0];

        private SpeedPcmAudioFilter(FloatPcmAudioFilter next, double speed)
        {
            this.next = next;
            this.speed = speed;
        }

        @Override
        public void process(float[][] input, int offset, int length) throws InterruptedException
        {
            if(length <= 0)
                return;

            int channelCount = input.length;
            if(!hasPreviousSamples && position < 0.0d)
                position = 0.0d;

            int estimatedLength = Math.max(1, (int)Math.ceil((length + Math.max(0.0d, -position) + speed) / speed) + 2);
            ensureOutputBuffers(channelCount, estimatedLength);
            ensurePreviousSamples(channelCount);

            int outputLength = 0;
            while(position < length - 1)
            {
                if(outputLength >= outputBuffers[0].length)
                    ensureOutputBuffers(channelCount, outputBuffers[0].length * 2);

                int baseIndex = (int)Math.floor(position);
                float fraction = (float)(position - baseIndex);

                for(int channel = 0; channel < channelCount; channel++)
                {
                    float current = baseIndex < 0 ? previousSamples[channel] : input[channel][offset + baseIndex];
                    float following = input[channel][offset + baseIndex + 1];
                    outputBuffers[channel][outputLength] = current + (following - current) * fraction;
                }

                outputLength++;
                position += speed;
            }

            position -= length;
            for(int channel = 0; channel < channelCount; channel++)
                previousSamples[channel] = input[channel][offset + length - 1];
            hasPreviousSamples = true;

            if(outputLength > 0)
                next.process(outputBuffers, 0, outputLength);
        }

        private void ensureOutputBuffers(int channelCount, int length)
        {
            if(outputBuffers.length == channelCount && outputBuffers.length > 0 && outputBuffers[0].length >= length)
                return;

            outputBuffers = new float[channelCount][length];
        }

        private void ensurePreviousSamples(int channelCount)
        {
            if(previousSamples.length != channelCount)
                previousSamples = new float[channelCount];
        }

        @Override
        public void seekPerformed(long requestedTime, long providedTime)
        {
            position = 0.0d;
            hasPreviousSamples = false;
        }

        @Override
        public void flush()
        {
        }

        @Override
        public void close()
        {
        }
    }

    private static final class StereoRotationPcmAudioFilter implements FloatPcmAudioFilter
    {
        private static final double TWO_PI = Math.PI * 2.0d;

        private final FloatPcmAudioFilter next;
        private final double radiansPerSample;
        private double phase;

        private StereoRotationPcmAudioFilter(FloatPcmAudioFilter next, int sampleRate, double frequencyHz)
        {
            this.next = next;
            this.radiansPerSample = TWO_PI * frequencyHz / sampleRate;
        }

        @Override
        public void process(float[][] input, int offset, int length) throws InterruptedException
        {
            if(input.length >= 2)
            {
                for(int i = offset; i < offset + length; i++)
                {
                    float mono = (input[0][i] + input[1][i]) * 0.5f;
                    double pan = Math.sin(phase);
                    float leftGain = (float)Math.sqrt((1.0d - pan) * 0.5d);
                    float rightGain = (float)Math.sqrt((1.0d + pan) * 0.5d);

                    input[0][i] = clamp(mono * leftGain);
                    input[1][i] = clamp(mono * rightGain);
                    advancePhase();
                }
            }

            next.process(input, offset, length);
        }

        private void advancePhase()
        {
            phase += radiansPerSample;
            if(phase >= TWO_PI)
                phase -= TWO_PI;
        }

        @Override
        public void seekPerformed(long requestedTime, long providedTime)
        {
            phase = 0.0d;
        }

        @Override
        public void flush()
        {
        }

        @Override
        public void close()
        {
        }
    }

    private static final class TremoloPcmAudioFilter implements FloatPcmAudioFilter
    {
        private static final double TWO_PI = Math.PI * 2.0d;

        private final FloatPcmAudioFilter next;
        private final double radiansPerSample;
        private final double depth;
        private double phase;

        private TremoloPcmAudioFilter(FloatPcmAudioFilter next, int sampleRate, double frequencyHz, double depth)
        {
            this.next = next;
            this.radiansPerSample = TWO_PI * frequencyHz / sampleRate;
            this.depth = depth;
        }

        @Override
        public void process(float[][] input, int offset, int length) throws InterruptedException
        {
            for(int i = offset; i < offset + length; i++)
            {
                float gain = (float)(1.0d - depth * (0.5d + Math.sin(phase) * 0.5d));
                for(int channel = 0; channel < input.length; channel++)
                    input[channel][i] = clamp(input[channel][i] * gain);
                advancePhase();
            }

            next.process(input, offset, length);
        }

        private void advancePhase()
        {
            phase += radiansPerSample;
            if(phase >= TWO_PI)
                phase -= TWO_PI;
        }

        @Override
        public void seekPerformed(long requestedTime, long providedTime)
        {
            phase = 0.0d;
        }

        @Override
        public void flush()
        {
        }

        @Override
        public void close()
        {
        }
    }

    private static final class KaraokePcmAudioFilter implements FloatPcmAudioFilter
    {
        private final FloatPcmAudioFilter next;
        private final double level;

        private KaraokePcmAudioFilter(FloatPcmAudioFilter next, double level)
        {
            this.next = next;
            this.level = level;
        }

        @Override
        public void process(float[][] input, int offset, int length) throws InterruptedException
        {
            if(input.length >= 2)
            {
                for(int i = offset; i < offset + length; i++)
                {
                    float left = input[0][i];
                    float right = input[1][i];
                    input[0][i] = clamp(left - right * (float)level);
                    input[1][i] = clamp(right - left * (float)level);
                }
            }

            next.process(input, offset, length);
        }

        @Override
        public void seekPerformed(long requestedTime, long providedTime)
        {
        }

        @Override
        public void flush()
        {
        }

        @Override
        public void close()
        {
        }
    }

    private static float clamp(float value)
    {
        return Math.max(-1.0f, Math.min(1.0f, value));
    }
}
