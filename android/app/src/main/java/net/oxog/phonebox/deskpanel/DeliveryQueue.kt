package net.oxog.phonebox.deskpanel

/**
 * Notifications that could not reach the Mac, kept in memory for a short while: at most [max], none older than [ttlMs]
 * (a login code older than two minutes is no use). Nothing is written to disk.
 */
class DeliveryQueue<T>(private val max: Int = 20, private val ttlMs: Long = 120_000, private val clock: () -> Long) {
    class Entry<T>(val at: Long, val item: T)

    private val items = ArrayDeque<Entry<T>>()

    /**
     * Counts [clear]s. A batch taken before a clear is void: the switch went off (perhaps on again while one request was
     * out) or another computer was chosen, so none of it may be sent or put back afterwards.
     */
    private var clears = 0

    /** [at] is when the item first happened: a retry puts it back with its original time so it still expires on schedule. */
    @Synchronized
    fun add(item: T, at: Long = clock()) {
        items.add(items.indexOfLast { it.at <= at } + 1, Entry(at, item))
        while (items.size > max) items.removeFirst()
    }

    @Synchronized
    fun isEmpty(): Boolean = items.isEmpty()

    /** Forgets everything waiting (the switch was turned off), including a batch already taken by [takeBatch]. */
    @Synchronized
    fun clear() { items.clear(); clears++ }

    @Synchronized
    fun mark(): Int = clears

    @Synchronized
    fun clearIfCurrent(mark: Int): Boolean {
        if (!isCurrent(mark)) return false
        clear()
        return true
    }

    @Synchronized
    fun addIfCurrent(item: T, mark: Int, at: Long = clock()): Boolean {
        if (!isCurrent(mark)) return false
        add(item, at)
        return true
    }

    /** Whether an entry taken earlier is still inside its time limit (a slow send can make later ones of a batch too old). */
    fun isFresh(entry: Entry<T>): Boolean = clock() - entry.at < ttlMs

    /** Empties the queue and returns what is still fresh, oldest first. */
    @Synchronized
    fun take(): List<Entry<T>> {
        val now = clock()
        val fresh = items.filter { now - it.at < ttlMs }
        items.clear()
        return fresh
    }

    /** [take], with a mark for [isCurrent] and [restore]: taken in one step, so no clear can fall between the two. */
    @Synchronized
    fun takeBatch(): Pair<Int, List<Entry<T>>> = clears to take()

    @Synchronized
    fun takeIfCurrent(mark: Int): List<Entry<T>>? = if (isCurrent(mark)) take() else null

    /** False once the queue was cleared after the batch with this [mark] was taken. */
    @Synchronized
    fun isCurrent(mark: Int): Boolean = clears == mark

    /**
     * Puts unsent entries back in timestamp order and drops the oldest beyond [max].
     * Nothing goes back if the queue was cleared since [mark]; returns whether they went back.
     */
    @Synchronized
    fun restore(entries: List<Entry<T>>, mark: Int): Boolean {
        if (clears != mark) return false
        val pending = (entries + items).sortedBy { it.at }.takeLast(max)
        items.clear()
        items.addAll(pending)
        return true
    }
}
