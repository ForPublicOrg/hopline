package app.hopline.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * The time on a chat-list row. A group that was left stays on Home until it is deleted, and its
 * row's date is all it says about when it was left — so a date from another year shows its year.
 */
class ListTimeTest {
    private val locale = Locale.getDefault()
    @Before fun english() = Locale.setDefault(Locale.US)
    @After fun back() = Locale.setDefault(locale)

    /** Noon on that day, on this machine's clock — far from any midnight, whatever its time zone. */
    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0, 0) }.timeInMillis

    @Test fun aDateFromAnotherYearShowsItsYear() {
        val left = at(2025, Calendar.AUGUST, 12)
        val shown = Ui.listTime(left, at(2026, Calendar.OCTOBER, 3))
        assertEquals("12 Aug 2025", shown)
        // the same day and month a year on must not read like this year's
        assertFalse(shown == Ui.listTime(at(2026, Calendar.AUGUST, 12), at(2026, Calendar.OCTOBER, 3)))
    }

    @Test fun aDateFromThisYearStaysShort() {
        assertEquals("12 Aug", Ui.listTime(at(2026, Calendar.AUGUST, 12), at(2026, Calendar.OCTOBER, 3)))
    }

    @Test fun lastYearStartsAtNewYearNotTwelveMonthsBack() {
        // Left just after Christmas, looked at early in January: eight days ago, but last year.
        assertEquals("28 Dec 2025", Ui.listTime(at(2025, Calendar.DECEMBER, 28), at(2026, Calendar.JANUARY, 5)))
        // Within the last few days it is still a weekday, whichever side of New Year it fell on.
        val recent = Ui.listTime(at(2025, Calendar.DECEMBER, 31), at(2026, Calendar.JANUARY, 3))
        assertEquals("Wed", recent)
    }

    @Test fun todayIsATimeAndNoTimeIsNothing() {
        val now = at(2026, Calendar.OCTOBER, 3)
        assertTrue(Ui.listTime(now - 3_600_000L, now).matches(Regex("\\d\\d:\\d\\d")))
        assertEquals("", Ui.listTime(0, now))
    }
}
