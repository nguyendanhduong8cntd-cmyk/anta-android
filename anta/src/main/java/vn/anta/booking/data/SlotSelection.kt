package vn.anta.booking.data

import java.time.Instant

/** The hours a user has picked on one yard: [count] consecutive hours starting at [firstHour] (hours since the epoch). */
data class SlotSelection(val yardId: String, val firstHour: Long, val count: Int) {
    val lastHour: Long get() = firstHour + count - 1
    val startsAt: Instant get() = Instant.ofEpochSecond(firstHour * 3600)
    val endsAt: Instant get() = Instant.ofEpochSecond((firstHour + count) * 3600)

    operator fun contains(slot: Slot): Boolean = slot.yardId == yardId && slot.hour in firstHour..lastHour

    companion object {
        /** The backend books whole hours, between one and eight of them. */
        const val MAX_HOURS = 8
    }
}

/**
 * The selection after the user taps a free slot.
 *
 * A booking is one unbroken stretch on one yard, so a tap next to the stretch lengthens it, a tap on either end
 * shortens it, and a tap anywhere else starts again from the tapped hour.
 */
fun SlotSelection?.tap(slot: Slot): SlotSelection? {
    val fresh = SlotSelection(slot.yardId, slot.hour, 1)
    if (this == null || slot.yardId != yardId) return fresh
    return when (slot.hour) {
        firstHour - 1 -> if (count < SlotSelection.MAX_HOURS) copy(firstHour = firstHour - 1, count = count + 1) else fresh
        lastHour + 1 -> if (count < SlotSelection.MAX_HOURS) copy(count = count + 1) else fresh
        firstHour -> if (count == 1) null else copy(firstHour = firstHour + 1, count = count - 1)
        lastHour -> copy(count = count - 1)
        else -> fresh
    }
}

/** What the picked hours cost, or null when one of them is no longer on offer. */
fun SlotSelection.total(slots: List<Slot>): Long? {
    val picked = slots.filter { it in this && it.available }
    return if (picked.size == count) picked.sumOf(Slot::amount) else null
}
