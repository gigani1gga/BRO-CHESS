package com.example.chesslab.engine

/** 64-bit hash keys: [0,832) piece/square, 832 side, 833..848 castling rights, 849..856 en-passant file. */
object Zobrist64 {
    @JvmField
    val K: LongArray = makeKeys()

    private fun makeKeys(): LongArray {
        val keys = LongArray(870)
        val rnd = java.util.Random(0x2545F4914F6CDD1DL)
        var i = 0
        while (i < keys.size) {
            keys[i] = rnd.nextLong()
            i++
        }
        return keys
    }
}
