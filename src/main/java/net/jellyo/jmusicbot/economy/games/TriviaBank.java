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

import java.util.List;
import java.util.Random;

/**
 * A small bundled trivia bank. Each question has four options, a correct index and
 * a difficulty that sets the coin reward. Kept dependency-free (no external data
 * files) so it always ships with the jar.
 */
public final class TriviaBank
{
    public enum Difficulty
    {
        EASY(30), MEDIUM(50), HARD(80);

        private final long reward;

        Difficulty(long reward) { this.reward = reward; }

        public long getReward() { return reward; }
    }

    public static final class Question
    {
        private final String text;
        private final String[] options;
        private final int correct;
        private final Difficulty difficulty;

        Question(String text, String[] options, int correct, Difficulty difficulty)
        {
            this.text = text;
            this.options = options;
            this.correct = correct;
            this.difficulty = difficulty;
        }

        public String getText() { return text; }
        public String[] getOptions() { return options.clone(); }
        public int getCorrect() { return correct; }
        public Difficulty getDifficulty() { return difficulty; }
        public boolean isCorrect(int index) { return index == correct; }
    }

    private static Question q(String text, Difficulty d, int correct, String a, String b, String c, String e)
    {
        return new Question(text, new String[]{a, b, c, e}, correct, d);
    }

    private static final List<Question> QUESTIONS = List.of(
            q("How many strings does a standard guitar have?", Difficulty.EASY, 2, "4", "5", "6", "7"),
            q("Which planet is known as the Red Planet?", Difficulty.EASY, 1, "Venus", "Mars", "Jupiter", "Saturn"),
            q("What is the chemical symbol for water?", Difficulty.EASY, 0, "H2O", "O2", "CO2", "NaCl"),
            q("How many continents are there on Earth?", Difficulty.EASY, 2, "5", "6", "7", "8"),
            q("Which instrument has 88 keys?", Difficulty.EASY, 1, "Organ", "Piano", "Harp", "Accordion"),
            q("What colour do you get by mixing blue and yellow?", Difficulty.EASY, 2, "Purple", "Orange", "Green", "Brown"),
            q("How many sides does a hexagon have?", Difficulty.EASY, 1, "5", "6", "7", "8"),
            q("Which ocean is the largest?", Difficulty.EASY, 3, "Atlantic", "Indian", "Arctic", "Pacific"),
            q("What is the capital of Japan?", Difficulty.MEDIUM, 2, "Kyoto", "Osaka", "Tokyo", "Nagoya"),
            q("Who painted the Mona Lisa?", Difficulty.MEDIUM, 1, "Raphael", "Leonardo da Vinci", "Michelangelo", "Donatello"),
            q("How many notes are in a standard major scale (before repeating)?", Difficulty.MEDIUM, 2, "5", "6", "7", "8"),
            q("Which element has the atomic number 1?", Difficulty.MEDIUM, 0, "Hydrogen", "Helium", "Oxygen", "Carbon"),
            q("In what year did the first Moon landing happen?", Difficulty.MEDIUM, 1, "1965", "1969", "1972", "1958"),
            q("What is the largest mammal on Earth?", Difficulty.MEDIUM, 3, "Elephant", "Giraffe", "Hippo", "Blue whale"),
            q("Which country hosted the first modern Olympic Games?", Difficulty.MEDIUM, 2, "France", "USA", "Greece", "Italy"),
            q("How many minutes are in a full day?", Difficulty.MEDIUM, 1, "720", "1440", "2400", "3600"),
            q("What language has the most native speakers worldwide?", Difficulty.MEDIUM, 0, "Mandarin Chinese", "English", "Spanish", "Hindi"),
            q("Which musical term means 'gradually getting louder'?", Difficulty.MEDIUM, 2, "Staccato", "Legato", "Crescendo", "Ritardando"),
            q("What is the tallest mountain above sea level?", Difficulty.HARD, 1, "K2", "Mount Everest", "Kangchenjunga", "Lhotse"),
            q("Who composed 'The Four Seasons'?", Difficulty.HARD, 0, "Vivaldi", "Bach", "Mozart", "Handel"),
            q("What is the hardest known natural material?", Difficulty.HARD, 2, "Quartz", "Titanium", "Diamond", "Graphene"),
            q("How many hearts does an octopus have?", Difficulty.HARD, 2, "1", "2", "3", "4"),
            q("Which planet has the most moons (as of the 2020s)?", Difficulty.HARD, 3, "Jupiter", "Neptune", "Uranus", "Saturn"),
            q("What note is 440 Hz commonly tuned to?", Difficulty.HARD, 0, "A4", "C4", "G4", "E4"),
            q("In which country was the composer Beethoven born?", Difficulty.HARD, 1, "Austria", "Germany", "Netherlands", "Belgium"),
            q("What is the smallest prime number?", Difficulty.HARD, 0, "2", "1", "3", "0")
    );

    private TriviaBank() {}

    public static int size()
    {
        return QUESTIONS.size();
    }

    public static Question get(int index)
    {
        return QUESTIONS.get(index);
    }

    public static Question random(Random rng)
    {
        return QUESTIONS.get(rng.nextInt(QUESTIONS.size()));
    }
}
