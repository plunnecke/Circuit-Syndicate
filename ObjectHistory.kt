package com.circuitsyndicate.findingtheway

import java.util.concurrent.CopyOnWriteArrayList

object ObjectHistory {

    private const val MAX = 100
    private val history = CopyOnWriteArrayList<String>()

    fun addDetection(detection: String) {
        history.add(0, detection)
        while (history.size > MAX) history.removeAt(history.size - 1)
    }

    fun getHistory(): List<String> = history.toList()
    fun getLastDetection(): String? = history.firstOrNull()
    fun clear() = history.clear()
}
