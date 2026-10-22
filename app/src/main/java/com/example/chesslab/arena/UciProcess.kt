package com.example.chesslab.arena

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

class UciResult(val bestMove: String, val cp: Int)

/** Minimal UCI client around a native engine executable (for example a Stockfish build). */
class UciProcess(private val binary: File) {
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private val lines = Channel<String>(Channel.UNLIMITED)

    suspend fun start() {
        if (!binary.exists()) throw IllegalStateException("Stockfish binary missing: ${binary.name}")
        if (!binary.canExecute()) binary.setExecutable(true)
        val p = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
        proc = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        val reader = Thread({
            try {
                p.inputStream.bufferedReader().useLines { seq -> seq.forEach { lines.trySend(it) } }
            } catch (_: Exception) {
            } finally {
                lines.close()
            }
        }, "uci-reader")
        reader.isDaemon = true
        reader.start()
        send("uci")
        waitFor("uciok")
        send("setoption name Threads value 1")
        send("setoption name Hash value 16")
        sync()
    }

    private fun send(cmd: String) {
        val w = writer ?: error("engine not started")
        w.write(cmd); w.newLine(); w.flush()
    }

    private suspend fun receive(): String =
        try {
            lines.receive()
        } catch (_: NoSuchElementException) {
            throw IllegalStateException("Stockfish process exited unexpectedly")
        }

    private suspend fun waitFor(token: String) {
        try {
            withTimeout(15_000) {
                while (true) {
                    if (receive().startsWith(token)) break
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("Stockfish did not answer '$token' in time")
        }
    }

    private suspend fun sync() {
        send("isready")
        waitFor("readyok")
    }

    suspend fun setOption(name: String, value: Int) {
        send("setoption name $name value $value")
        sync()
    }

    suspend fun newGame() {
        send("ucinewgame")
        sync()
    }

    /** Asks for a best move after [ms] milliseconds; cp is from the side to move's point of view. */
    suspend fun analyse(fen: String, ms: Int): UciResult {
        send("position fen $fen")
        send("go movetime $ms")
        var cp = 0
        var best = ""
        try {
            withTimeout(ms + 10_000L) {
                while (true) {
                    val l = receive()
                    if (l.startsWith("bestmove")) {
                        best = l.split(" ").getOrElse(1) { "" }
                        break
                    }
                    if (l.startsWith("info") && l.contains(" score ")) {
                        val m = SCORE.find(l)
                        if (m != null) {
                            val v = m.groupValues[2].toInt()
                            cp = if (m.groupValues[1] == "cp") v else (if (v >= 0) 1 else -1) * 10000
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("Stockfish did not return a move in time")
        }
        return UciResult(best, cp)
    }

    fun close() {
        try { writer?.let { it.write("quit"); it.newLine(); it.flush() } } catch (_: Exception) {}
        try { proc?.destroy() } catch (_: Exception) {}
        lines.close()
    }

    private companion object {
        val SCORE = Regex("score (cp|mate) (-?\\d+)")
    }
}
