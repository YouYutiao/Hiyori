package eu.kanade.tachiyomi.data.prewarm

/**
 * Pure order transforms for the prewarm queue.
 *
 * The queue is a flat list but the screen renders it grouped by series, so order is defined in two
 * levels:
 *  - the **series blocks** keep their relative order (a block only moves when the user drags its
 *    header),
 *  - chapters are ordered **inside their own block**. A chapter can never move into another series:
 *    its manga is a property of the chapter, not of the queue.
 *
 * Sorting therefore never interleaves series, and dragging a row past another series simply clamps
 * at the edge of its own block.
 */
internal object PrewarmQueueOrder {

    /** Orders the chapters inside every series block, keeping the blocks where they are. */
    fun sorted(list: List<PrewarmItem>, sort: PrewarmSort): List<PrewarmItem> {
        val comparator = when (sort) {
            PrewarmSort.CUSTOM -> return list
            PrewarmSort.QUEUED -> compareBy<PrewarmItem> { it.queuedAt }
            PrewarmSort.CHAPTER_ASC -> compareBy<PrewarmItem> { it.chapterNumber }
            PrewarmSort.CHAPTER_DESC -> compareByDescending<PrewarmItem> { it.chapterNumber }
        }
        return blocks(list).values.flatMap { it.sortedWith(comparator) }
    }

    /**
     * Moves one chapter to [toIndexInSeries] inside its own block. Out-of-range targets clamp to the
     * block edges, which is what the drag gesture needs when the finger leaves the block.
     */
    fun moveChapter(list: List<PrewarmItem>, chapterId: Long, toIndexInSeries: Int): List<PrewarmItem> {
        val current = list.firstOrNull { it.chapterId == chapterId } ?: return list
        val chaptersOfSeries = list.filter { it.mangaId == current.mangaId }
        if (chaptersOfSeries.size <= 1) return list

        val reordered = chaptersOfSeries.toMutableList().apply {
            removeAll { it.chapterId == chapterId }
            add(toIndexInSeries.coerceIn(0, size), current)
        }
        val iterator = reordered.iterator()
        return list.map { item -> if (item.mangaId == current.mangaId) iterator.next() else item }
    }

    /** Moves a whole series block to [toIndex] among the blocks. */
    fun moveSeries(list: List<PrewarmItem>, mangaId: Long, toIndex: Int): List<PrewarmItem> {
        val entries = blocks(list).entries.toMutableList()
        val moving = entries.firstOrNull { it.key == mangaId } ?: return list
        entries.remove(moving)
        entries.add(toIndex.coerceIn(0, entries.size), moving)
        return entries.flatMap { it.value }
    }

    /** Series ids in the order their blocks appear, for drag targets on the headers. */
    fun seriesOrder(list: List<PrewarmItem>): List<Long> = blocks(list).keys.toList()

    private fun blocks(list: List<PrewarmItem>): LinkedHashMap<Long, MutableList<PrewarmItem>> {
        val blocks = LinkedHashMap<Long, MutableList<PrewarmItem>>()
        list.forEach { item -> blocks.getOrPut(item.mangaId) { mutableListOf() } += item }
        return blocks
    }
}
