package com.example.chesslab.arena

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

class UciResult(val bestMove: String, val cp: Int)

/** Minimal UCI client around a native engine executable (for example a Stockfish build). */
class UciProcess(private val binary: File, private val scope: CoroutineScope) {
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private val lines = Channel<String>(Channel.UNLIMITED)

    suspend fun start() {
        val p = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
        proc = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        scope.launch(Dispatchers.IO) {
            try {
                p.inputStream.bufferedReader().useLines { seq -> seq.forEach { lines.trySend(it) } }
            } catch (_: Exception) {
            } finally {
                lines.close()
            }
        }
        send("uci")
        waitFor("uciok")
        send("isready")
        waitFor("readyok")
    }

    private fun send(cmd: String) {
        val w = writer ?: error("engine not started")
        w.write(cmd); w.newLine(); w.flush()
    }

    private suspend fun waitFor(token: String) {
        withTimeout(10_000) {
            while (true) {
                val l = lines.receive()
                if (l.startsWith(token)) break
            }
        }
    }

    fun setOption(name: String, value: Int) = send("setoption name $name value $value")

    /** Asks for a best move after [ms] milliseconds; cp is from the side to move's point of view. */
    suspend fun analyse(fen: String, ms: Int): UciResult {
        send("position fen $fen")
        send("go movetime $ms")
        var cp = 0
        var best = ""
        withTimeout(ms + 10_000L) {
            while (true) {
                val l = lines.receive()
                if (l.startsWith("bestmove")) {
                    best = l.split(" ").getOrElse(1) { "" }
                    break
                }
                if (l.startsWith("info") && l.contains(" score ")) {
                    val m = SCORE.find(l)
                    if (m != null) {
                        val v = m.groupValues[2].toInt()
                        cp = if (m.groupValues[1] == "cp") v else (if (v > 0) 1 else -1) * 10000
                    }
                }
            }
        }
        return UciResult(best, cp)
    }

    fun close() {
        try { writer?.let { it.write("quit"); it.newLine(); it.flush() } } catch (_: Exception) {}
        proc?.destroy()
        lines.close()
    }

    private companion object {
        val SCORE = Regex("score (cp|mate) (-?\\d+)")
    }
}
