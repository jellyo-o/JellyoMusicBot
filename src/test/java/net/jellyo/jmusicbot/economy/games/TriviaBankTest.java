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
package com.jagrosh.jmusicbot.economy.games;

import com.jagrosh.jmusicbot.economy.games.TriviaBank.Question;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TriviaBankTest
{
    @Test
    public void everyQuestionIsWellFormed()
    {
        assertTrue("bank is non-trivial", TriviaBank.size() >= 20);
        for(int i = 0; i < TriviaBank.size(); i++)
        {
            Question q = TriviaBank.get(i);
            assertTrue("question text present", q.getText() != null && !q.getText().isBlank());
            String[] options = q.getOptions();
            assertEquals("exactly four options", 4, options.length);
            Set<String> distinct = new HashSet<>();
            for(String option : options)
            {
                assertTrue("option present", option != null && !option.isBlank());
                assertTrue("options are distinct", distinct.add(option));
            }
            assertTrue("correct index in range", q.getCorrect() >= 0 && q.getCorrect() < 4);
            assertTrue(q.isCorrect(q.getCorrect()));
            assertTrue("reward is positive", q.getDifficulty().getReward() > 0);
        }
    }

    @Test
    public void randomReturnsAQuestion()
    {
        Random rng = new Random(1);
        for(int i = 0; i < 100; i++)
            assertTrue(TriviaBank.random(rng) != null);
    }
}
