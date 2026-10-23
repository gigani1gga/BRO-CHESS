package com.example.chesslab.arena

import com.example.chesslab.engine.Engine
import com.example.chesslab.engine.Searcher
import com.example.chesslab.engine.Weights
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.tanh

data class ArenaStats(
    val games: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val level: Int = 0,
    val recent: Double = 0.0,
    val error: Double = 0.0,
    val positions: Long = 0,
    val note: String = "",
    val recentGames: List<String> = emptyList()
)

/**
 * Trains one evaluation vector against a native UCI engine whose skill level rises as we get stronger.
 * Our side plays with the alpha-beta [Searcher] on top of the current weights; Stockfish's evaluation
 * of every position is the learning signal.
 */
class ArenaTrainer(
    private val scope: CoroutineScope,
    private val binary: File,
    initial: DoubleArray?,
    private val slots: Int
) {
    private val wLock = Any()
    private val w = initial?.copyOf() ?: Weights.random()
    val running = MutableStateFlow(false)
    private val _stats = MutableStateFlow(ArenaStats())
    val stats: StateFlow<ArenaStats> = _stats

    private val recentQueue = ArrayDeque<String>()

    @Volatile private var level = 0
    private var games = 0; private var wins = 0; private var draws = 0; private var losses = 0
    private var recent = 0.0
    private var err = 0.5
    private var positions = 0L
    private var windowSum = 0; private var windowN = 0
    private var job: Job? = null
    private val failures = AtomicInteger()

    fun weights(): DoubleArray = synchronized(wLock) { w.copyOf() }

    fun start() {
        if (job?.isActive == true) return
        running.value = true
        failures.set(0)
        job = scope.launch(Dispatchers.Default) {
            try {
                publish("")
                coroutineScope {
                    repeat(slots) { launch { slotLoop() } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish("Stockfish stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running.value = false
            }
        }
    }

    fun reset() {
        val fresh = Weights.random()
        synchronized(wLock) { System.arraycopy(fresh, 0, w, 0, fresh.size) }
        synchronized(this) {
            games = 0; wins = 0; draws = 0; losses = 0
            recent = 0.0; err = 0.5; positions = 0; windowSum = 0; windowN = 0
            recentQueue.clear()
        }
        level = 0
        publish("")
    }

    fun stop() {
        job?.cancel()
        running.value = false
    }

    /** Keeps one Stockfish process alive for this slot, restarting it if it dies or stalls. */
    private suspend fun slotLoop() {
        while (scope.isActive && job?.isActive == true) {
            val o = UciProcess(binary)
            try {
                o.start()
                playGames(o)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val n = failures.incrementAndGet()
                val why = e.message ?: e.javaClass.simpleName
                if (n >= MAX_FAILURES) {
                    publish("Stockfish failed repeatedly ($why). If this says permission denied, Android blocked " +
                        "running the binary; if it says missing, the APK was built without Stockfish.")
                    return
                }
                publish("Stockfish hiccup ($why) - restarting ($n/$MAX_FAILURES)")
                delay(1000)
            } finally {
                o.close()
            }
        }
    }

    private suspend fun playGames(o: UciProcess) {
        val e = Engine()
        val brain = Searcher(e, 16)
        var applied = -1
        while (scope.isActive && job?.isActive == true) {
            running.first { it }
            val lvl = level
            if (lvl != applied) {
                o.setOption("Skill Level", lvl)
                applied = lvl
            }
            o.newGame()
            e.reset()
            brain.setWeights(synchronized(wLock) { w.copyOf() })
            val ours = if (Math.random() < 0.5) 1 else -1
            val hist = ArrayList<Int>()
            val snaps = ArrayList<IntArray>()
            val targets = ArrayList<Double>()
            var res = ""
            for (ply in 0 until MAX_PLY) {
                res = e.status(hist)
                if (res.isNotEmpty()) break
                val turn = e.side
                running.first { it }
                val r = o.analyse(e.fen(), MOVE_MS)
                snaps.add(e.b.copyOf())
                val cpTarget = if (r.cp == 0) 0.0 else tanh(r.cp.toDouble() / 600.0) * turn
                targets.add(cpTarget)
                val m = when {
                    ply < 5 -> e.randomLegalMove()
                    turn == ours ->
                        if (Math.random() < 0.05) e.randomLegalMove() else brain.best(OUR_MS, OUR_DEPTH).move
                    else -> fromUci(e, r.bestMove)
                }
                if (m < 0) break
                hist.add(m)
                e.apply(m)
            }
            val win = if (res.contains("checkmate")) (if (res[0] == 'W') 1 else -1) else 0
            snaps.add(e.b.copyOf())
            targets.add(win.toDouble())
            val n = snaps.size
            val bs = IntArray(n * 64)
            for (i in 0 until n) System.arraycopy(snaps[i], 0, bs, i * 64, 64)
            val tg = targets.toDoubleArray()
            val er = synchronized(wLock) { e.trainSupervised(w, bs, n, tg, LR) }
            val score = win * ours
            failures.set(0)
            synchronized(this) {
                games++
                positions += n
                when { score > 0 -> wins++; score < 0 -> losses++; else -> draws++ }
                err = 0.95 * err + 0.05 * er
                windowSum += score; windowN++
                if (windowN >= 20) {
                    recent = windowSum / 20.0
                    if (recent > 0.2 && level < 20) level++
                    windowSum = 0; windowN = 0
                }
            }
            rememberGame(hist.toIntArray(), score, res)
            publish("")
        }
    }

    private fun rememberGame(moves: IntArray, score: Int, result: String) {
        val summary = buildGameSummary(moves, score, result)
        synchronized(recentQueue) {
            recentQueue.addFirst(summary)
            while (recentQueue.size > 8) recentQueue.removeLast()
        }
    }

    private fun buildGameSummary(moves: IntArray, score: Int, result: String): String {
        val outcome = when {
            score > 0 -> "Win"
            score < 0 -> "Loss"
            else -> "Draw"
        }
        if (moves.isEmpty()) return "$outcome: opening"
        val text = moves.joinToString(" ") { moveToUci(it) }
        val compact = if (text.length > 90) text.substring(0, 87) + "..." else text
        val detail = if (result.isNotEmpty()) " • ${result.trim()}" else ""
        return "$outcome: $compact$detail"
    }

    private fun moveToUci(move: Int): String {
        val from = move and 63
        val to = (move shr 6) and 63
        val fromFile = ('a' + (from and 7))
        val fromRank = ('8' - (from shr 3))
        val toFile = ('a' + (to and 7))
        val toRank = ('8' - (to shr 3))
        val promo = when ((move shr 12) and 7) {
            2 -> "n"
            3 -> "b"
            4 -> "r"
            5 -> "q"
            else -> ""
        }
        return "$fromFile$fromRank$toFile$toRank$promo"
    }

    private fun publish(note: String) {
        val snapshot = synchronized(this) {
            ArenaStats(
                games,
                wins,
                draws,
                losses,
                level,
                recent,
                err,
                positions,
                note,
                synchronized(recentQueue) { recentQueue.toList() }
            )
        }
        _stats.value = snapshot
    }

    private fun fromUci(e: Engine, mv: String): Int {
        if (mv.length < 4) return -1
        val f = (mv[0] - 'a') + (8 - (mv[1] - '0')) * 8
        val t = (mv[2] - 'a') + (8 - (mv[3] - '0')) * 8
        val pr = if (mv.length > 4) "  nbrq".indexOf(mv[4]) else 0
        for (m in e.legalMoves()) {
            if ((m and 63) == f && ((m shr 6) and 63) == t && ((m shr 12) and 7) == pr) return m
        }
        return -1
    }

    private companion object {
        const val MAX_PLY = 160
        const val MOVE_MS = 40
        const val OUR_MS = 80L
        const val OUR_DEPTH = 6
        const val LR = 0.003
        const val MAX_FAILURES = 5
    }
}
