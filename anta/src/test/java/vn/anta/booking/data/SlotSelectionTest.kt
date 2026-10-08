package vn.anta.booking.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SlotSelectionTest {
    private val nine = Instant.parse("2026-10-09T02:00:00Z") // 09:00 in Vietnam
    private fun slot(hourOffset: Long, yard: String = "y1", available: Boolean = true, amount: Long = 100_000) =
        Slot(yard, "Sân $yard", nine.plusSeconds(hourOffset * 3600), nine.plusSeconds((hourOffset + 1) * 3600), amount, available)

    private fun pick(vararg taps: Slot): SlotSelection? = taps.fold(null as SlotSelection?) { selection, slot -> selection.tap(slot) }

    @Test
    fun `the first tap picks one hour`() {
        val selection = pick(slot(0))!!

        assertEquals(SlotSelection("y1", slot(0).hour, 1), selection)
        assertEquals(nine, selection.startsAt)
        assertEquals(nine.plusSeconds(3600), selection.endsAt)
    }

    @Test
    fun `a tap next to the stretch lengthens it on either side`() {
        assertEquals(SlotSelection("y1", slot(0).hour, 3), pick(slot(1), slot(2), slot(0)))
    }

    @Test
    fun `a tap on either end shortens the stretch and a tap on its only hour clears it`() {
        assertEquals(SlotSelection("y1", slot(1).hour, 2), pick(slot(0), slot(1), slot(2), slot(0)))
        assertEquals(SlotSelection("y1", slot(0).hour, 2), pick(slot(0), slot(1), slot(2), slot(2)))
        assertNull(pick(slot(0), slot(0)))
    }

    @Test
    fun `a tap away from the stretch, in its middle or on another yard starts again`() {
        assertEquals(SlotSelection("y1", slot(5).hour, 1), pick(slot(0), slot(1), slot(5)))
        assertEquals(SlotSelection("y1", slot(1).hour, 1), pick(slot(0), slot(1), slot(2), slot(1)))
        assertEquals(SlotSelection("y2", slot(1, "y2").hour, 1), pick(slot(0), slot(1, "y2")))
    }

    @Test
    fun `a stretch stops growing at eight hours`() {
        val eight = pick(*(0L..7L).map { slot(it) }.toTypedArray())!!

        assertEquals(8, eight.count)
        assertEquals(SlotSelection("y1", slot(8).hour, 1), eight.tap(slot(8)))
    }

    @Test
    fun `the total adds up the picked hours and is unknown once one of them is gone`() {
        val day = listOf(slot(0, amount = 100_000), slot(1, amount = 150_000), slot(2, amount = 150_000), slot(0, "y2", amount = 999_000))
        val selection = pick(day[0], day[1])!!

        assertEquals(250_000L, selection.total(day))
        assertNull(selection.total(listOf(day[0], day[1].copy(available = false))))
        assertNull(selection.total(listOf(day[0])))
    }
}
