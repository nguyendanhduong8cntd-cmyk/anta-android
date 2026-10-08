package vn.anta.booking.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Test

class SportGridTest {
    @Test
    fun `the sports shown fill whole rows of four together with the tile for all venues`() {
        // sports available -> sports shown; one more tile ("all venues") always follows them.
        val expected = mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 3, 6 to 3, 7 to 7, 8 to 7, 10 to 7, 25 to 7)

        assertEquals(expected, expected.keys.associateWith(::sportsShown))
    }
}
