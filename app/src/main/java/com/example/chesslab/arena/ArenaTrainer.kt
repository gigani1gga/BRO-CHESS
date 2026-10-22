package com.example.chesslab.arena

import com.example.chesslab.engine.Engine
import com.example.chesslab.engine.Weights
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
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
    val note: String = ""
)

/** Trains one evaluation vector against a native UCI engine whose skill level rises as we get stronger. */
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

    @Volatile private var level = 0
    private var games = 0; private var wins = 0; private var draws = 0; private var losses = 0
    private var recent = 0.0
    private var err = 0.5
    private var positions = 0L
    private var windowSum = 0; private var windowN = 0
    private var job: Job? = null

    fun weights(): DoubleArray = synchronized(wLock) { w.copyOf() }

    fun start() {
        if (job?.isActive == true) return
        running.value = true
        job = scope.launch(Dispatchers.Default) {
            val procs = ArrayList<UciProcess>()
            try {
                repeat(slots) {
                    val p = UciProcess(binary, this)
                    p.start()
                    procs.add(p)
                }
                publish("")
                procs.map { p -> launch { slot(p) } }.joinAll()
            } catch (e: Exception) {
                publish("Engine stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                procs.forEach { it.close() }
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
        }
        level = 0
        publish("")
    }

    fun stop() {
        job?.cancel()
        running.value = false
    }

    private suspend fun slot(o: UciProcess) {
        val e = Engine()
        var applied = -1
        while (scope.isActive && job?.isActive == true) {
            running.first { it }
            val lvl = level
            if (lvl != applied) { o.setOption("Skill Level", lvl); applied = lvl }
            e.reset()
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
                targets.add(tanh(r.cp / 300.0) * turn)
                val m = when {
                    ply < 4 -> e.randomLegalMove()
                    turn == ours -> synchronized(wLock) { e.ai(w, 0.03) }
                    else -> fromUci(e, r.bestMove)
                }
                if (m < 0) break
                e.apply(m)
            }
            val win = if (res.contains("checkmate")) (if (res[0] == 'W') 1 else -1) else 0
            snaps.add(e.b.copyOf()); targets.add(win.toDouble())
            val n = snaps.size
            val bs = IntArray(n * 64)
            for (i in 0 until n) System.arraycopy(snaps[i], 0, bs, i * 64, 64)
            val tg = targets.toDoubleArray()
            val er = synchronized(wLock) { e.trainSupervised(w, bs, n, tg, LR) }
            val score = win * ours
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
            publish("")
        }
    }

    private fun publish(note: String) {
        synchronized(this) {
            _stats.value = ArenaStats(games, wins, draws, losses, level, recent, err, positions, note)
        }
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
        const val MOVE_MS = 30
        const val LR = 0.003
    }
}
