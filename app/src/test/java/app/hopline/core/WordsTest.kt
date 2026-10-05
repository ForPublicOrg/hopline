package app.hopline.core

import org.junit.Assert.*
import org.junit.Test

/** Group codes: making them, checking what someone typed, and fixing what they probably meant. */
class WordsTest {

    // ---------------------------------------------------------------- three words (groups from before 2.4)

    @Test fun `a three-word code is still a valid code`() {
        assertTrue(Words.looksValid("tiger river lamp"))
        assertTrue(Words.isKnownCode("Tiger, RIVER lamp"))
        assertEquals("tiger-river-lamp", Words.normalise("  Tiger, RIVER   lamp "))
        assertEquals("tiger river lamp", Words.pretty("Tiger-River-Lamp"))
        assertFalse("a word not on the list", Words.isKnownCode("doughnut river lamp"))
        assertEquals(listOf("doughnut"), Words.unknownWords("doughnut river lamp"))
        assertFalse("one-letter words", Words.looksValid("a b c"))
        assertEquals(3, Words.randomCode(3).split(' ').size)
    }

    @Test fun `typos, other spellings and run-together words in a three-word code are fixed`() {
        assertEquals("tiger river lamp", Words.suggest("tigre river lamp"))
        assertEquals("tiger river lamp", Words.suggest("tiger rivr lamp"))
        assertEquals("donut river lamp", Words.suggest("doughnut river lamp"))
        assertEquals("tiger river lamp", Words.suggest("tigerriver lamp"))
        assertEquals("tiger river lamp", Words.suggest("tigerriverlamp"))
        assertEquals("yoyo river lamp", Words.suggest("yo yo river lamp"))
        assertEquals("ladybug river lamp", Words.suggest("lady bug river lamp"))
        assertEquals("tiger river lamp", Words.suggest("Tigre, River LAMP"))
    }

    @Test fun `nothing is suggested for a three-word code that is right or can't be told`() {
        assertNull("already right", Words.suggest("tiger river lamp"))
        assertNull("already right, just typed loosely", Words.suggest("Tiger, RIVER lamp"))
        assertNull("pine, pink or pond: a coin toss", Words.suggest("pind river lamp"))
        assertNull("nothing close", Words.suggest("xqzv river lamp"))
        assertNull(Words.suggest(""))
        assertNull(Words.suggest("  ,, "))
    }

    // ---------------------------------------------------------------- four words (from 2.4)

    @Test fun `a new code is four words from the list`() {
        repeat(50) {
            val code = Words.randomCode()
            assertEquals(code, 4, code.split(' ').size)
            assertTrue(code, code.split(' ').all { it in Words.LIST })
            assertTrue(code, Words.looksValid(code))
            assertTrue(code, Words.isKnownCode(code))
            assertNull(code, Words.suggest(code))
        }
    }

    @Test fun `three or four words are a code, two or five are not`() {
        assertTrue(Words.looksValid("tiger river lamp hat"))
        assertTrue(Words.isKnownCode("Tiger, River LAMP hat"))
        assertFalse(Words.looksValid("tiger river"))
        assertFalse(Words.isKnownCode("tiger river"))
        assertFalse(Words.looksValid("tiger river lamp hat moon"))
        assertFalse(Words.isKnownCode("tiger river lamp hat moon"))
        assertFalse(Words.isKnownCode("tiger river lamp doughnut"))
        assertEquals("tiger-river-lamp-hat", Words.normalise("Tiger River, lamp HAT!"))
    }

    @Test fun `typos, other spellings and run-together words in a four-word code are fixed`() {
        assertEquals("tiger river lamp hat", Words.suggest("tigre river lamp hat"))
        assertEquals("tiger river lamp hat", Words.suggest("tiger river lammp hat"))
        assertEquals("donut river lamp hat", Words.suggest("doughnut river lamp hat"))
        assertEquals("tiger river lamp hat", Words.suggest("tigerriver lamp hat"))
        assertEquals("tiger river lamp hat", Words.suggest("tiger riverlamphat"))
        assertEquals("tiger river lamp hat", Words.suggest("tigerriverlamphat"))
        assertEquals("yoyo river lamp hat", Words.suggest("yo yo river lamp hat"))
        assertEquals("ladybug river lamp hat", Words.suggest("lady bug river lamp hat"))
        assertEquals("tiger river lamp hat", Words.suggest("tigerriver lampp hat"))
    }

    @Test fun `nothing is suggested when the four-word code is right, too short or long, or could be either size`() {
        assertNull(Words.suggest("tiger river lamp hat"))
        assertNull("two words", Words.suggest("tiger river"))
        assertNull("five words", Words.suggest("tiger river lamp hat moon"))
        assertNull("five once cut apart", Words.suggest("tigerriver lamp hat moon"))
        assertNull("a tie", Words.suggest("pind river lamp hat"))
        // "car pet" is "carpet" (a three-word code), or "car" and a typo of "jet" (a four-word one): no telling which.
        assertEquals("bus jet lamp hat", Words.suggest("bus pet lamp hat"))
        assertTrue(Words.isKnownCode("carpet lamp hat"))
        assertNull(Words.suggest("car pet lamp hat"))
    }
}
