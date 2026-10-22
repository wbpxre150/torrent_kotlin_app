package com.torrent.app.backup

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.nio.charset.StandardCharsets

/**
 * Pure, quote-aware splitter for the incremental dump SQL stream (plan 3,
 * Stage 1): feeds one complete statement at a time to [onStatement] so the
 * Kotlin-side importer can replay a dump without ever materialising the whole
 * `.sql` text in heap.
 *
 * The state machine is deliberately tiny and driven only by SQL string-literal
 * quoting:
 * - `'` outside a literal opens one; `'` inside closes it;
 * - `''` inside a literal is the standard SQL doubled-quote escape and appends
 *   a single `'` while the literal stays open — the only escaping
 *   [SqlDumpWriter.renderSqlLiteral] emits;
 * - `;` outside a literal terminates the statement;
 * - every other character (including raw `\n` / `\r`, which occur verbatim
 *   inside dumped text — see [renderSqlLiteral] — and inside the trigger
 *   bodies) is ordinary statement content. Line-based splitting is therefore
 *   unsafe, which the native restore code agrees on
 *   (`patches/restore-database.patch`).
 *
 * Blob literals (`x'0a0b'`) contain no quotes of their own, so they pass
 * through untouched. Statements are emitted **without** the terminating `;`
 * and trimmed; statements that are blank after trimming (a stray `;;` or
 * trailing whitespace) are skipped. An unterminated final statement at end of
 * stream is still emitted, so a truncated dump fails at execution rather than
 * silently losing its last chunk.
 *
 * No Android imports — plain JUnit4 testable.
 */
object SqlStatementSplitter {

    /**
     * Runaway guard: a statement longer than this means the stream is not a
     * dump we understand (an unbalanced quote swallows the rest of the file).
     * Fails loudly with [IllegalStateException] instead of growing the buffer
     * until the process is killed.
     */
    const val MAX_STATEMENT_CHARS: Int = 8 * 1024 * 1024

    private const val QUOTE = '\''
    private const val TERMINATOR = ';'

    /**
     * Reads [input] as UTF-8 and invokes [onStatement] once per non-blank SQL
     * statement, in stream order.
     *
     * @throws IllegalStateException when a single statement grows beyond
     *   [MAX_STATEMENT_CHARS].
     */
    fun split(input: InputStream, onStatement: (String) -> Unit) {
        val reader = PushbackReader(
            InputStreamReader(BufferedInputStream(input), StandardCharsets.UTF_8),
            2,
        )
        val buffer = StringBuilder()
        var inQuote = false
        while (true) {
            val next = reader.read()
            if (next == -1) break
            val c = next.toChar()
            if (inQuote) {
                buffer.append(c)
                if (c == QUOTE) {
                    val after = reader.read()
                    if (after == QUOTE.code) {
                        // Doubled quote: one literal apostrophe, literal stays open.
                        buffer.append(QUOTE)
                    } else {
                        inQuote = false
                        if (after != -1) reader.unread(after)
                    }
                }
            } else when (c) {
                QUOTE -> {
                    inQuote = true
                    buffer.append(c)
                }

                TERMINATOR -> emit(buffer, onStatement)

                else -> buffer.append(c)
            }
            if (buffer.length > MAX_STATEMENT_CHARS) {
                throw IllegalStateException(
                    "SQL statement exceeds $MAX_STATEMENT_CHARS chars (unbalanced quote?)",
                )
            }
        }
        emit(buffer, onStatement)
    }

    /** Emits the trimmed content of [buffer] (if any) and clears it. */
    private fun emit(buffer: StringBuilder, onStatement: (String) -> Unit) {
        val statement = buffer.toString().trim()
        buffer.setLength(0)
        if (statement.isNotEmpty()) onStatement(statement)
    }
}
