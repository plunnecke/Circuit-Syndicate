package com.circuitsyndicate.findingtheway

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe interaction logger.
 * Initialise once in Application.onCreate(); call static methods anywhere afterwards.
 */
object InteractionLogger {

    private val logs = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US)
    private val formatterLock = Any()
    private val lock = Any()

    private const val MAX_ENTRIES = 8_000
    private const val TARGET_STABILITY_MS = 4L * 60L * 60L * 1000L
    private const val MAX_PENDING_PERSIST_WRITES = 1_500

    private const val PREF_NAME = "interaction_logger_state"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_SESSION_START_WALL_MS = "session_start_wall_ms"
    private const val KEY_SESSION_START_ELAPSED_MS = "session_start_elapsed_ms"
    private const val KEY_SESSION_ACTIVE = "session_active"

    private val persistentWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "InteractionLoggerWriter").apply { isDaemon = true }
    }
    private val pendingPersistWrites = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sessionId: String = newSessionId()

    @Volatile
    private var sessionStartWallMs: Long = System.currentTimeMillis()

    @Volatile
    private var sessionStartElapsedMs: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var sessionActive: Boolean = false

    private val connectedSinceMsByDevice = ConcurrentHashMap<String, Long>()
    private val cumulativeConnectedMsByDevice = ConcurrentHashMap<String, Long>()
    private val disconnectCountByDevice = ConcurrentHashMap<String, Int>()
    private val interruptionCountBySource = ConcurrentHashMap<String, Int>()

    private fun timestamp(): String {
        synchronized(formatterLock) {
            return formatter.format(Date())
        }
    }

    private fun offsetDuration(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun newSessionId(): String =
        UUID.randomUUID().toString().substring(0, 8)

    private fun currentOffsetMs(): Long =
        (SystemClock.elapsedRealtime() - sessionStartElapsedMs).coerceAtLeast(0L)

    private fun sessionDirectory(context: Context): File {
        return File(context.filesDir, "session_evidence").apply { mkdirs() }
    }

    private fun sessionLiveFile(context: Context, sid: String = sessionId): File =
        File(sessionDirectory(context), "session_${sid}_live.log")

    private fun appendPersistentLog(entry: String, sid: String) {
        val context = appContext ?: return
        if (pendingPersistWrites.incrementAndGet() > MAX_PENDING_PERSIST_WRITES) {
            pendingPersistWrites.decrementAndGet()
            return
        }
        persistentWriter.execute {
            runCatching {
                sessionLiveFile(context, sid).appendText(entry + "\n")
            }.also {
                pendingPersistWrites.decrementAndGet()
            }
        }
    }

    private fun persistStateLocked() {
        val context = appContext ?: return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_SESSION_START_WALL_MS, sessionStartWallMs)
            .putLong(KEY_SESSION_START_ELAPSED_MS, sessionStartElapsedMs)
            .putBoolean(KEY_SESSION_ACTIVE, sessionActive)
            .apply()
    }

    private fun beginNewSessionLocked(reason: String, clearExisting: Boolean): String {
        if (clearExisting) {
            logs.clear()
        }
        connectedSinceMsByDevice.clear()
        cumulativeConnectedMsByDevice.clear()
        disconnectCountByDevice.clear()
        interruptionCountBySource.clear()

        sessionId = newSessionId()
        sessionStartWallMs = System.currentTimeMillis()
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        sessionActive = true
        persistStateLocked()

        logInternalLocked(
            type = "SESSION",
            source = "ANDROID",
            message = "START id=$sessionId reason=$reason target=${offsetDuration(TARGET_STABILITY_MS)}"
        )
        return sessionId
    }

    private fun ensureSessionLocked() {
        if (!sessionActive) {
            beginNewSessionLocked(reason = "AUTO_START", clearExisting = false)
        }
    }

    private fun logInternalLocked(type: String, source: String, message: String) {
        val entry = "[${timestamp()}] [+${offsetDuration(currentOffsetMs())}] [SID:$sessionId] [$type] [$source] $message"
        if (logs.size >= MAX_ENTRIES) {
            logs.removeFirst()
        }
        logs.addLast(entry)
        appendPersistentLog(entry, sessionId)
    }

    private fun consumeConnectedDurationLocked(device: String): Long {
        val startedAt = connectedSinceMsByDevice.remove(device) ?: return 0L
        val delta = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val previous = cumulativeConnectedMsByDevice[device] ?: 0L
        cumulativeConnectedMsByDevice[device] = previous + delta
        return delta
    }

    private fun totalConnectedDurationLocked(device: String): Long {
        val cumulative = cumulativeConnectedMsByDevice[device] ?: 0L
        val startedAt = connectedSinceMsByDevice[device]
        return if (startedAt != null) {
            cumulative + (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        } else {
            cumulative
        }
    }

    fun initialize(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            val prefs = appContext!!.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

            val storedSessionId = prefs.getString(KEY_SESSION_ID, null)
            val storedActive = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()

            if (!storedSessionId.isNullOrBlank() && storedActive) {
                sessionId = storedSessionId
                sessionStartWallMs = prefs.getLong(KEY_SESSION_START_WALL_MS, nowWall)
                sessionStartElapsedMs = prefs.getLong(KEY_SESSION_START_ELAPSED_MS, nowElapsed)
                    .takeIf { it > 0L } ?: nowElapsed
                sessionActive = true

                interruptionCountBySource["ANDROID_PROCESS"] =
                    (interruptionCountBySource["ANDROID_PROCESS"] ?: 0) + 1

                logInternalLocked(
                    type = "INTERRUPTION",
                    source = "ANDROID",
                    message = "Recovered active session after app process restart"
                )
            } else {
                beginNewSessionLocked(reason = "APP_BOOT", clearExisting = false)
            }
            persistStateLocked()
        }
    }

    fun startEvidenceSession(reason: String = "MANUAL_START", clearExisting: Boolean = true): String {
        synchronized(lock) {
            return beginNewSessionLocked(reason, clearExisting)
        }
    }

    fun endEvidenceSession(reason: String = "MANUAL_END") {
        synchronized(lock) {
            ensureSessionLocked()
            val devices = connectedSinceMsByDevice.keys.toList()
            devices.forEach { consumeConnectedDurationLocked(it) }

            sessionActive = false
            logInternalLocked(
                type = "SESSION",
                source = "ANDROID",
                message = "END id=$sessionId reason=$reason elapsed=${offsetDuration(currentOffsetMs())}"
            )
            persistStateLocked()
        }
    }

    fun currentSessionId(): String = sessionId

    fun currentSessionElapsedMs(): Long = currentOffsetMs()

    fun log(type: String, source: String, message: String) {
        synchronized(lock) {
            ensureSessionLocked()
            logInternalLocked(type, source, message)
        }
    }

    fun logSessionEvidence(source: String, event: String, details: String = "") {
        val suffix = if (details.isBlank()) "" else " $details"
        log("EVIDENCE", source, "event=$event$suffix")
    }

    fun logInterruption(source: String, cause: String) {
        synchronized(lock) {
            ensureSessionLocked()
            interruptionCountBySource[source] = (interruptionCountBySource[source] ?: 0) + 1
            logInternalLocked("INTERRUPTION", source, "cause=$cause")
        }
    }

    fun logConnection(device: String, connected: Boolean) {
        logConnection(device, connected, "state_change")
    }

    fun logConnection(device: String, connected: Boolean, detail: String) {
        synchronized(lock) {
            ensureSessionLocked()

            if (connected) {
                connectedSinceMsByDevice.putIfAbsent(device, SystemClock.elapsedRealtime())
                logInternalLocked("CONNECTION", device, "Connected detail=$detail")
                return
            }

            val disconnectCount = (disconnectCountByDevice[device] ?: 0) + 1
            disconnectCountByDevice[device] = disconnectCount

            val justConnectedMs = consumeConnectedDurationLocked(device)
            val totalConnectedMs = totalConnectedDurationLocked(device)

            interruptionCountBySource[device] = (interruptionCountBySource[device] ?: 0) + 1
            logInternalLocked(
                "CONNECTION",
                device,
                "Disconnected detail=$detail connected_ms=$justConnectedMs total_connected_ms=$totalConnectedMs disconnect_count=$disconnectCount"
            )
        }
    }

    fun buildSessionSummary(): String {
        synchronized(lock) {
            ensureSessionLocked()
            val elapsedMs = currentOffsetMs()
            val reachedTarget = elapsedMs >= TARGET_STABILITY_MS
            val interruptionTotal = interruptionCountBySource.values.sum()

            val vestConnectedMs = totalConnectedDurationLocked("VEST")
            val glassesConnectedMs = totalConnectedDurationLocked("GLASSES")

            val vestDisconnects = disconnectCountByDevice["VEST"] ?: 0
            val glassesDisconnects = disconnectCountByDevice["GLASSES"] ?: 0

            return "session=$sessionId elapsed=${offsetDuration(elapsedMs)} target=${offsetDuration(TARGET_STABILITY_MS)} reached_target=$reachedTarget interruptions=$interruptionTotal vest_connected=${offsetDuration(vestConnectedMs)} vest_disconnects=$vestDisconnects glasses_connected=${offsetDuration(glassesConnectedMs)} glasses_disconnects=$glassesDisconnects"
        }
    }

    fun exportEvidence(context: Context? = appContext): File? {
        val safeContext = context ?: return null
        val outputFile: File
        val linesForExport: List<String>
        val summary: String

        synchronized(lock) {
            ensureSessionLocked()
            summary = buildSessionSummary()

            val liveFile = sessionLiveFile(safeContext)
            val liveLines = if (liveFile.exists()) liveFile.readLines() else emptyList()
            val memoryLines = logs.toList()

            val merged = LinkedHashSet<String>()
            merged.addAll(liveLines)
            merged.addAll(memoryLines)
            linesForExport = merged.toList()

            val exportDir = sessionDirectory(safeContext)
            outputFile = File(exportDir, "session_${sessionId}_export_${System.currentTimeMillis()}.txt")
            outputFile.writeText(
                buildString {
                    appendLine("Phase 6 Unified Session Evidence")
                    appendLine("Session: $sessionId")
                    appendLine("Summary: $summary")
                    appendLine("ExportedAt: ${timestamp()}")
                    appendLine("Entries: ${linesForExport.size}")
                    appendLine("")
                    linesForExport.forEach { appendLine(it) }
                }
            )

            logInternalLocked("SESSION", "ANDROID", "Evidence exported: ${outputFile.absolutePath}")
        }

        return outputFile
    }

    fun logCommand(command: String, source: String) = log("COMMAND", source, command)
    fun logBattery(device: String, level: Int) = log("BATTERY", device, "$level%")
    fun logDetection(objectName: String, source: String) = log("DETECTION", source, objectName)
    fun logHapticFeedback(pattern: String, reason: String) = log("HAPTIC", "VEST", "$pattern ($reason)")
    fun logStateChange(setting: String, enabled: Boolean, source: String) =
        log("STATE", source, "$setting = ${if (enabled) "ON" else "OFF"}")

    fun getLogs(): List<String> {
        synchronized(lock) {
            val summary = buildSessionSummary()
            return listOf("[${timestamp()}] [SESSION] [ANDROID] $summary") + logs.toList().asReversed()
        }
    }

    fun clear() {
        synchronized(lock) {
            val context = appContext
            logs.clear()
            if (context != null) {
                runCatching { sessionLiveFile(context).delete() }
            }
            beginNewSessionLocked(reason = "MANUAL_CLEAR", clearExisting = false)
        }
    }
}
