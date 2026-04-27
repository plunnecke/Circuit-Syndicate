package com.circuitsyndicate.findingtheway

import java.util.concurrent.CopyOnWriteArrayList

object ObjectHistory {

    private const val MAX_HISTORY_ITEMS = 100
    private val historyItems = CopyOnWriteArrayList<String>()

    fun addDetection(detection: String) {
        historyItems.add(0, detection)

        while (historyItems.size > MAX_HISTORY_ITEMS) {
            historyItems.removeAt(historyItems.lastIndex)
        }
    }

    fun getHistory(): List<String> {
        return historyItems.toList()
    }

    fun getLastDetection(): String? {
        return historyItems.firstOrNull()
    }

    fun clear() {
        historyItems.clear()
    }
}
