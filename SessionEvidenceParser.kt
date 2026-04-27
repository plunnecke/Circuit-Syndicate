package com.circuitsyndicate.findingtheway

/**
 * Parsing and validation helpers for Phase 6/7 session evidence artifacts.
 */
object SessionEvidenceParser {

    data class FirmwareEvidence(
        val device: String,
        val event: String,
        val uptimeMs: Long,
        val fields: Map<String, String>,
        val payload: String
    )

    data class AndroidTimelineEntry(
        val timestamp: String,
        val offset: String,
        val sessionId: String,
        val type: String,
        val source: String,
        val message: String
    )

    data class ExportSummary(
        val sessionId: String,
        val elapsed: String,
        val target: String,
        val reachedTarget: Boolean,
        val interruptions: Int,
        val rawFields: Map<String, String>
    )

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val summary: ExportSummary?,
        val timelineEntryCount: Int
    )

    private val timelineRegex = Regex(
        """^\[(?<timestamp>[^\]]+)] \[\+(?<offset>\d{2}:\d{2}:\d{2})] \[SID:(?<sid>[^\]]+)] \[(?<type>[^\]]+)] \[(?<source>[^\]]+)] (?<message>.+)$"""
    )

    private val hmsRegex = Regex("""^(\d{2}):(\d{2}):(\d{2})$""")

    fun parseFirmwareEvidenceLine(line: String): FirmwareEvidence? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("SESSION_EVIDENCE:")) return null

        val payload = trimmed.removePrefix("SESSION_EVIDENCE:")
        val fields = parseSemicolonFields(payload)

        val device = fields["DEV"] ?: return null
        val event = fields["EV"] ?: return null
        val uptimeMs = fields["UP"]?.toLongOrNull() ?: return null
        if (uptimeMs < 0L) return null

        return FirmwareEvidence(
            device = device,
            event = event,
            uptimeMs = uptimeMs,
            fields = fields,
            payload = payload
        )
    }

    fun parseSessionResetCause(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("SESSION_RESET:")) return null
        val cause = trimmed.removePrefix("SESSION_RESET:").trim()
        return cause.ifEmpty { null }
    }

    fun parseAndroidTimelineEntry(line: String): AndroidTimelineEntry? {
        val match = timelineRegex.matchEntire(line.trim()) ?: return null
        return AndroidTimelineEntry(
            timestamp = match.groups["timestamp"]?.value ?: return null,
            offset = match.groups["offset"]?.value ?: return null,
            sessionId = match.groups["sid"]?.value ?: return null,
            type = match.groups["type"]?.value ?: return null,
            source = match.groups["source"]?.value ?: return null,
            message = match.groups["message"]?.value ?: return null
        )
    }

    fun parseSummaryLine(line: String): ExportSummary? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("Summary:")) return null

        val payload = trimmed.removePrefix("Summary:").trim()
        if (payload.isEmpty()) return null

        val fields = parseSpaceDelimitedFields(payload)
        val sessionId = fields["session"] ?: return null
        val elapsed = fields["elapsed"] ?: return null
        val target = fields["target"] ?: return null
        val reachedTarget = fields["reached_target"]?.toBooleanStrictOrNull() ?: return null
        val interruptions = fields["interruptions"]?.toIntOrNull() ?: return null

        if (interruptions < 0) return null
        if (!isDuration(elapsed) || !isDuration(target)) return null

        return ExportSummary(
            sessionId = sessionId,
            elapsed = elapsed,
            target = target,
            reachedTarget = reachedTarget,
            interruptions = interruptions,
            rawFields = fields
        )
    }

    fun validateExport(lines: List<String>): ValidationResult {
        val errors = mutableListOf<String>()
        val trimmedLines = lines.map { it.trimEnd() }

        if (trimmedLines.none { it.trim() == "Phase 6 Unified Session Evidence" }) {
            errors += "Missing export header: Phase 6 Unified Session Evidence"
        }

        val summaryLine = trimmedLines.firstOrNull { it.trimStart().startsWith("Summary:") }
        val summary = summaryLine?.let(::parseSummaryLine)
        if (summaryLine == null) {
            errors += "Missing Summary line"
        } else if (summary == null) {
            errors += "Summary line is malformed"
        }

        if (summary != null && summary.target != "04:00:00") {
            errors += "Summary target must be 04:00:00"
        }

        val declaredEntries = trimmedLines
            .firstOrNull { it.trimStart().startsWith("Entries:") }
            ?.substringAfter("Entries:", "")
            ?.trim()
            ?.toIntOrNull()

        val timelineEntries = trimmedLines.mapNotNull(::parseAndroidTimelineEntry)

        if (declaredEntries != null && declaredEntries != timelineEntries.size) {
            errors += "Entries count mismatch: declared=$declaredEntries parsed=${timelineEntries.size}"
        }

        if (timelineEntries.isEmpty()) {
            errors += "No Android timeline entries found"
        }

        if (summary != null) {
            val sidMismatch = timelineEntries.any { it.sessionId != summary.sessionId }
            if (sidMismatch) {
                errors += "Timeline contains entries with a different session ID"
            }

            val startFound = timelineEntries.any {
                it.type == "SESSION" && it.source == "ANDROID" && it.message.startsWith("START ")
            }
            if (!startFound) {
                errors += "Missing Android session START entry"
            }

            val heartbeatFound = timelineEntries.any {
                it.type == "EVIDENCE" &&
                    it.source == "ANDROID_RUNTIME" &&
                    it.message.contains("event=HEARTBEAT")
            }
            if (!heartbeatFound) {
                errors += "Missing Android runtime HEARTBEAT evidence"
            }

            val elapsedMs = parseDurationSeconds(summary.elapsed)
            val targetMs = parseDurationSeconds(summary.target)
            if (elapsedMs != null && targetMs != null) {
                if (summary.reachedTarget && elapsedMs < targetMs) {
                    errors += "reached_target=true but elapsed is below target"
                }
                if (!summary.reachedTarget && elapsedMs >= targetMs) {
                    errors += "reached_target=false but elapsed reached target"
                }
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            summary = summary,
            timelineEntryCount = timelineEntries.size
        )
    }

    fun validateExportText(text: String): ValidationResult {
        return validateExport(text.lines())
    }

    private fun parseSemicolonFields(payload: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        payload.split(';').forEach { token ->
            val entry = token.trim()
            if (entry.isEmpty()) return@forEach
            val delimiter = entry.indexOf('=')
            if (delimiter <= 0) return@forEach

            val key = entry.substring(0, delimiter).trim()
            if (key.isEmpty()) return@forEach

            val value = entry.substring(delimiter + 1).trim()
            out[key] = value
        }
        return out
    }

    private fun parseSpaceDelimitedFields(payload: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        payload.split(Regex("\\s+")).forEach { token ->
            val entry = token.trim()
            if (entry.isEmpty()) return@forEach
            val delimiter = entry.indexOf('=')
            if (delimiter <= 0) return@forEach

            val key = entry.substring(0, delimiter).trim()
            if (key.isEmpty()) return@forEach

            val value = entry.substring(delimiter + 1).trim()
            out[key] = value
        }
        return out
    }

    private fun isDuration(value: String): Boolean = hmsRegex.matches(value)

    private fun parseDurationSeconds(value: String): Long? {
        val match = hmsRegex.matchEntire(value) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        if (minutes !in 0..59 || seconds !in 0..59) return null
        return hours * 3600 + minutes * 60 + seconds
    }
}
