package com.slipstream.core.media

/**
 * Parses HTTP `Range` request headers (RFC 7233 §2.1, single-range only — the only form
 * Slipstream's media clients send). Supports `bytes=start-end`, open-ended `bytes=start-`,
 * and suffix `bytes=-length` forms.
 */
object RangeHeader {

    /** An inclusive byte range, resolved against a known content length. */
    data class Range(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    sealed class ParseResult {
        /** No `Range` header was present - serve the whole entity. */
        object Absent : ParseResult()

        /** A `Range` header was present but names a range outside the entity. */
        object Unsatisfiable : ParseResult()

        /** A `Range` header was present and resolves to a satisfiable byte range. */
        data class Satisfiable(val range: Range) : ParseResult()
    }

    private val PATTERN = Regex("""bytes=(\d*)-(\d*)""")

    /**
     * Parses [headerValue] against an entity of [contentLength] bytes. Malformed headers are
     * treated as [ParseResult.Unsatisfiable] rather than silently falling back to the whole
     * file, per RFC 7233 - a client that sent a `Range` header expects either a partial
     * response or a 416, never a full 200.
     */
    fun parse(headerValue: String?, contentLength: Long): ParseResult {
        if (headerValue == null) return ParseResult.Absent
        val match = PATTERN.matchEntire(headerValue.trim()) ?: return ParseResult.Unsatisfiable
        val (startText, endText) = match.destructured

        if (contentLength <= 0) return ParseResult.Unsatisfiable

        val range = when {
            // Suffix form: bytes=-500 -> last 500 bytes.
            startText.isEmpty() && endText.isNotEmpty() -> {
                val suffixLength = endText.toLongOrNull() ?: return ParseResult.Unsatisfiable
                if (suffixLength <= 0) return ParseResult.Unsatisfiable
                val start = maxOf(0L, contentLength - suffixLength)
                Range(start, contentLength - 1)
            }
            // Open-ended form: bytes=500- -> from 500 to the end.
            startText.isNotEmpty() && endText.isEmpty() -> {
                val start = startText.toLongOrNull() ?: return ParseResult.Unsatisfiable
                if (start < 0) return ParseResult.Unsatisfiable
                Range(start, contentLength - 1)
            }
            // Closed form: bytes=500-999.
            startText.isNotEmpty() && endText.isNotEmpty() -> {
                val start = startText.toLongOrNull() ?: return ParseResult.Unsatisfiable
                val end = endText.toLongOrNull() ?: return ParseResult.Unsatisfiable
                Range(start, minOf(end, contentLength - 1))
            }
            else -> return ParseResult.Unsatisfiable
        }

        if (range.start < 0 || range.start >= contentLength || range.start > range.end) {
            return ParseResult.Unsatisfiable
        }
        return ParseResult.Satisfiable(range)
    }
}
