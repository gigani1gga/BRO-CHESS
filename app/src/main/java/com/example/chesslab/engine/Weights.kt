package com.example.chesslab.engine

import com.example.chesslab.engine.Tables.MAT
import com.example.chesslab.engine.Tables.NF
import com.example.chesslab.engine.Tables.OW
import java.util.Locale
import java.util.Random

/** Helpers for the 198-number evaluation vector (white-positive scores). */
object Weights {

    fun random(rng: Random = Random()): DoubleArray =
        DoubleArray(NF) { i -> if (i > MAT) 0.05 else (rng.nextDouble() - 0.5) * 0.02 }

    @JvmStatic
    fun clamp(x: Double): Double = if (x > 3.0) 3.0 else if (x < -3.0) -3.0 else x

    /** Score of the position stored at bs[o until o+64], from white's point of view. */
    fun score(w: DoubleArray, bs: IntArray, o: Int): Double {
        var s = 0.0
        for (i in 0 until 64) {
            val p = bs[o + i]
            if (p == 0) continue
            val a = if (p > 0) 1 else -1
            val q = a * p
            s += a * (w[(q - 1) * 32 + OW[if (a > 0) i else 64 + i]] + (if (q < 6) w[MAT + q] else 0.0))
        }
        return s
    }

    /**
     * Fills [v] (size >= 840) with per-(piece, square) scores and, at 832+q, material values.
     * Index of piece p on square s is (p + 6) * 64 + s.
     */
    fun buildValues(w: DoubleArray, v: DoubleArray) {
        for (q in 1..5) v[832 + q] = w[MAT + q]
        for (p in -6..6) {
            if (p == 0) continue
            val a = if (p > 0) 1 else -1
            val q = a * p
            val m = if (q < 6) w[MAT + q] else 0.0
            val base = (p + 6) * 64
            for (s in 0 until 64) {
                v[base + s] = a * (w[(q - 1) * 32 + OW[if (a > 0) s else 64 + s]] + m)
            }
        }
    }

    /** Average learned value of piece type q. */
    fun pieceValue(w: DoubleArray, q: Int): Double {
        var s = 0.0
        for (i in 0 until 32) s += w[(q - 1) * 32 + i]
        return s / 32 + (if (q < 6) w[MAT + q] else 0.0)
    }

    fun describe(w: DoubleArray, pawnFloor: Double = 0.05): String {
        val p = pieceValue(w, 1)
        if (p < pawnFloor) return "still discovering what a pawn is worth"
        fun f(q: Int) = String.format(Locale.US, "%.1f", pieceValue(w, q) / p)
        return "knight ${f(2)}, bishop ${f(3)}, rook ${f(4)}, queen ${f(5)}"
    }
}
