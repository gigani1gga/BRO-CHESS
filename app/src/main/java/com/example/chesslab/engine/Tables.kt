package com.example.chesslab.engine

/**
 * Immutable lookup tables shared by every [Engine] instance (safe across threads).
 *
 * Board: index 0 = a8 ... 63 = h1. White pieces are positive, black negative:
 * 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king.
 */
object Tables {
    const val NF = 198      // learned parameters: 6 piece tables of 32 + material values
    const val MAXP = 100    // plies per self-play game
    const val MAT = 191     // material value of piece q lives at index MAT + q

    private val DR = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
    private val DF = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)

    /** Rays per square; directions 0..3 are rook-like, 4..7 bishop-like. */
    @JvmField
    val RY: Array<Array<IntArray>> = Array(64) { sq ->
        Array(8) { d ->
            val out = ArrayList<Int>(7)
            var r = (sq shr 3) + DR[d]
            var f = (sq and 7) + DF[d]
            while (r in 0..7 && f in 0..7) {
                out.add(r * 8 + f)
                r += DR[d]
                f += DF[d]
            }
            out.toIntArray()
        }
    }

    /** King destinations (first square of every ray). */
    @JvmField
    val KI: Array<IntArray> = Array(64) { sq ->
        RY[sq].filter { it.isNotEmpty() }.map { it[0] }.toIntArray()
    }

    @JvmField
    val KN: Array<IntArray> = Array(64) { sq ->
        val r = sq shr 3
        val f = sq and 7
        val jumps = intArrayOf(-2, -1, -2, 1, -1, -2, -1, 2, 1, -2, 1, 2, 2, -1, 2, 1)
        val out = ArrayList<Int>(8)
        var i = 0
        while (i < jumps.size) {
            val rr = r + jumps[i]
            val ff = f + jumps[i + 1]
            if (rr in 0..7 && ff in 0..7) out.add(rr * 8 + ff)
            i += 2
        }
        out.toIntArray()
    }

    /** Maps (side, square) to the left/right-folded 8x4 table slot. [0,64) white view, [64,128) black view. */
    @JvmField
    val OW = IntArray(128).also { ow ->
        for (s in 0 until 64) {
            val r = s shr 3
            val f = s and 7
            val fo = if (f > 3) 7 - f else f
            ow[s] = r * 4 + fo
            ow[64 + s] = (7 - r) * 4 + fo
        }
    }

    /** Castling-rights mask applied when a piece moves from or to a square. */
    @JvmField
    val CM = IntArray(64) { 15 }.also {
        it[60] = 12; it[63] = 14; it[56] = 13
        it[4] = 3; it[7] = 11; it[0] = 7
    }

    @JvmField
    val ZOB = IntArray(832).also { z ->
        val rnd = java.util.Random(0x5DEECE66DL)
        for (i in z.indices) z[i] = rnd.nextInt()
    }

    /** 64-bit keys: [0,832) piece/square, 832 side, 833..848 castling rights, 849..856 en-passant file. */
    @JvmField
    val Z64 = LongArray(870).also { z ->
        val rnd = java.util.Random(0x2545F4914F6CDD1DL)
        for (i in z.indices) z[i] = rnd.nextLong()
    }

    /** Trigger masks for "could this move give a direct check": indexed by piece type. */
    @JvmField
    val TM = intArrayOf(0, 1, 2, 4, 8, 12, 0)
}
