/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot;

import com.jagrosh.jmusicbot.queue.FairQueue;
import com.jagrosh.jmusicbot.queue.LinearQueue;
import com.jagrosh.jmusicbot.queue.Queueable;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class FairQueueTest
{
    @Test
    public void differentIdentifierSize()
    {
        FairQueue<Q> queue = new FairQueue<>(null);
        int size = 100;
        for(int i=0; i<size; i++)
            queue.add(new Q(i));
        assertEquals(queue.size(), size);
    }
    
    @Test
    public void sameIdentifierSize()
    {
        FairQueue<Q> queue = new FairQueue<>(null);
        int size = 100;
        for(int i=0; i<size; i++)
            queue.add(new Q(0));
        assertEquals(queue.size(), size);
    }

    @Test
    public void fairBulkAddPreservesFairOrdering()
    {
        FairQueue<Q> queue = new FairQueue<>(null);
        queue.add(new Q(1));
        queue.add(new Q(2));
        queue.add(new Q(1));
        queue.add(new Q(2));

        int firstPosition = queue.addAll(List.of(new Q(3), new Q(3)));

        assertEquals(2, firstPosition);
        assertEquals(List.of(1L, 2L, 3L, 1L, 2L, 3L), List.of(
                queue.get(0).getIdentifier(),
                queue.get(1).getIdentifier(),
                queue.get(2).getIdentifier(),
                queue.get(3).getIdentifier(),
                queue.get(4).getIdentifier(),
                queue.get(5).getIdentifier()));
    }

    @Test
    public void linearBulkAddAppendsAllItems()
    {
        LinearQueue<Q> queue = new LinearQueue<>(null);
        queue.add(new Q(1));

        int firstPosition = queue.addAll(List.of(new Q(2), new Q(3)));

        assertEquals(1, firstPosition);
        assertEquals(3, queue.size());
        assertEquals(2L, queue.get(1).getIdentifier());
        assertEquals(3L, queue.get(2).getIdentifier());
    }
    
    private class Q implements Queueable
    {
        private final long identifier;
        
        private Q(long identifier)
        {
            this.identifier = identifier;
        }
        
        @Override
        public long getIdentifier()
        {
            return identifier;
        }
    }
}
