package com.circuitsyndicate.findingtheway

/**
 * Determines the payload offset for the first burst chunk.
 *
 * Legacy burst chunk-0 format:
 *   [frameIdx_lo, frameIdx_hi, burstSeq, orientation, ...jpeg]
 *
 * Current burst chunk-0 format:
 *   [frameIdx_lo, frameIdx_hi, burstSeq, orientation, captureTimeMs_le(4), ...jpeg]
 */
object BurstChunkHeaderPolicy {

    private const val LEGACY_HEADER_BYTES = 4
    private const val TIMESTAMP_HEADER_BYTES = 8

    /**
     * Returns the best-effort JPEG payload start offset for frameIndex == 0 burst chunks.
     * This remains backward-compatible with older firmware that does not include captureTimeMs.
     */
    fun payloadOffset(firstBurstChunk: ByteArray): Int {
        if (hasJpegHeaderAt(firstBurstChunk, TIMESTAMP_HEADER_BYTES)) {
            return TIMESTAMP_HEADER_BYTES
        }
        if (hasJpegHeaderAt(firstBurstChunk, LEGACY_HEADER_BYTES)) {
            return LEGACY_HEADER_BYTES
        }

        // If header bytes are ambiguous, prefer the newer layout when possible.
        return if (firstBurstChunk.size >= TIMESTAMP_HEADER_BYTES) {
            TIMESTAMP_HEADER_BYTES
        } else {
            LEGACY_HEADER_BYTES
        }
    }

    /**
     * Returns captureTimeMs from a timestamped burst chunk-0 header, or null
     * when the payload appears to use the legacy header format.
     */
    fun captureTimeMs(firstBurstChunk: ByteArray): Long? {
        if (!hasJpegHeaderAt(firstBurstChunk, TIMESTAMP_HEADER_BYTES)) {
            return null
        }
        if (firstBurstChunk.size < TIMESTAMP_HEADER_BYTES) {
            return null
        }

        val b0 = firstBurstChunk[4].toLong() and 0xFF
        val b1 = firstBurstChunk[5].toLong() and 0xFF
        val b2 = firstBurstChunk[6].toLong() and 0xFF
        val b3 = firstBurstChunk[7].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun hasJpegHeaderAt(data: ByteArray, offset: Int): Boolean {
        if (data.size < offset + 3) return false
        return data[offset] == 0xFF.toByte() &&
            data[offset + 1] == 0xD8.toByte() &&
            data[offset + 2] == 0xFF.toByte()
    }
}
