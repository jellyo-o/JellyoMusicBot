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
package com.jagrosh.jmusicbot.commands;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ButtonPaginatorTest
{
    @Test
    public void parsesButtonRequest()
    {
        ButtonPaginator.Request request = ButtonPaginator.parse("jmb-page:queue:q:123:456:2");

        assertEquals("queue", request.getNamespace());
        assertEquals("q", request.getState());
        assertEquals(123L, request.getGuildId());
        assertEquals(456L, request.getUserId());
        assertEquals(2, request.getPage());
    }

    @Test
    public void ignoresInvalidButtonRequest()
    {
        assertNull(ButtonPaginator.parse("jmb-other:queue:q:123:456:2"));
        assertNull(ButtonPaginator.parse("jmb-page:queue:q:bad:456:2"));
        assertNull(ButtonPaginator.parse("jmb-page:queue:q:123:456"));
    }

    @Test
    public void clampsPagesAndSlicesItems()
    {
        List<Integer> items = List.of(1, 2, 3, 4, 5);

        assertEquals(3, ButtonPaginator.pageCount(items.size(), 2));
        assertEquals(1, ButtonPaginator.clampPage(-4, items.size(), 2));
        assertEquals(3, ButtonPaginator.clampPage(99, items.size(), 2));
        assertEquals(List.of(3, 4), ButtonPaginator.pageItems(items, 2, 2));
        assertEquals(List.of(5), ButtonPaginator.pageItems(items, 3, 2));
    }
}
