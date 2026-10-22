package com.example.chesslab.train

import com.example.chesslab.engine.Engine
import com.example.chesslab.engine.GameBuf

/** A finished game kept for replay or for extra learning. [boards] is null for UI-only copies. */
class GameRecord(
    @JvmField val moves: IntArray,
    @JvmField val plies: Int,
    @JvmField val z: Double,
    @JvmField val mate: Boolean,
    @JvmField val aWhite: Boolean,
    @JvmField val boards: IntArray?
)

class BatchResult(
    val games: Int,
    val aWins: Int,
    val bWins: Int,
    val mates: Int,
    val best: GameRecord?,
    val worst: GameRecord?,
    val last: GameRecord?
)

/** Plays self-play games for a time budget and trains both weight sets in place. One per thread. */
class SelfPlayWorker(seed: Long) {
    private val eng = Engine(seed)
    private val buf = GameBuf()
    private val best = ArrayList<GameRecord>()    // fastest mates, shortest first
    private val worst = ArrayList<GameRecord>()   // longest games without result, longest first
    private var flip = 0

    private fun keep(): GameRecord = GameRecord(
        buf.moves.copyOf(buf.plies), buf.plies, buf.z, buf.mate, buf.aWhite,
        buf.boards.copyOf(buf.plies * 64)
    )

    private fun pack(g: GameRecord?): GameRecord? =
        g?.let { GameRecord(it.moves, it.plies, it.z, it.mate, it.aWhite, null) }

    fun runBatch(a: DoubleArray, b: DoubleArray, ms: Long, k: Double): BatchResult {
        val la = 0.002 * k
        val lb = 0.0026 * k
        val limit = ms * 1_000_000L
        val t0 = System.nanoTime()
        var games = 0
        var aw = 0
        var bw = 0
        var mates = 0
        var last: GameRecord? = null
        while (System.nanoTime() - t0 < limit) {
            val aWhite = (flip++ and 1) == 0
            eng.play(
                if (aWhite) a else b, if (aWhite) b else a,
                if (aWhite) EPS_A else EPS_B, if (aWhite) EPS_B else EPS_A, buf
            )
            buf.aWhite = aWhite
            eng.trainTD(a, buf.boards, buf.plies, buf.z, la, LAMBDA)
            eng.trainTD(b, buf.boards, buf.plies, buf.z, lb, LAMBDA)
            val sc = if (aWhite) buf.z else -buf.z
            games++
            if (buf.mate) {
                mates++
                if (sc > 0) aw++ else bw++
                if (best.size < KEEP || buf.plies < best[best.size - 1].plies) {
                    best.add(keep())
                    best.sortBy { it.plies }
                    if (best.size > KEEP) best.removeAt(best.size - 1)
                }
            } else if (worst.size < KEEP || buf.plies > worst[worst.size - 1].plies) {
                worst.add(keep())
                worst.sortByDescending { it.plies }
                if (worst.size > KEEP) worst.removeAt(worst.size - 1)
            }
        }
        if (games > 0) last = GameRecord(buf.moves.copyOf(buf.plies), buf.plies, buf.z, buf.mate, buf.aWhite, null)
        for (g in best) {
            val bs = g.boards ?: continue
            eng.trainTD(a, bs, g.plies, g.z, la * 0.5, LAMBDA)
            eng.trainTD(b, bs, g.plies, g.z, lb * 0.5, LAMBDA)
        }
        for (g in worst) {
            val bs = g.boards ?: continue
            eng.trainTD(a, bs, g.plies, g.z, la * 0.25, LAMBDA)
            eng.trainTD(b, bs, g.plies, g.z, lb * 0.25, LAMBDA)
        }
        return BatchResult(games, aw, bw, mates, pack(best.firstOrNull()), pack(worst.firstOrNull()), last)
    }

    private companion object {
        const val EPS_A = 0.04
        const val EPS_B = 0.10
        const val LAMBDA = 0.7
        const val KEEP = 6
    }
}
