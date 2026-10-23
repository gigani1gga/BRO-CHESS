package com.example.chesslab.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.example.chesslab.ui.theme.BoardDark
import com.example.chesslab.ui.theme.BoardDarkNight
import com.example.chesslab.ui.theme.BoardLight
import com.example.chesslab.ui.theme.BoardLightNight

/** Immutable snapshot of everything the board needs to draw. A new instance triggers a redraw. */
class BoardUi(
    val cells: IntArray = initialCells(),
    val flipped: Boolean = false,
    val from: Int = -1,
    val to: Int = -1,
    val selected: Int = -1,
    val targets: Long = 0L,
    val hidden: Boolean = false
) {
    companion object {
        fun initialCells(): IntArray {
            val k = intArrayOf(4, 2, 3, 5, 6, 3, 2, 4)
            val b = IntArray(64)
            for (f in 0 until 8) {
                b[f] = -k[f]; b[8 + f] = -1; b[48 + f] = 1; b[56 + f] = k[f]
            }
            return b
        }
    }
}

private const val GLYPHS = "\u265F\u265E\u265D\u265C\u265B\u265A"   // pawn..king, filled shapes

@Composable
fun BoardView(ui: BoardUi, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val light = if (dark) BoardLightNight else BoardLight
    val shade = if (dark) BoardDarkNight else BoardDark
    val fill = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER } }
    val line = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE } }
    val glyph = remember { Array(7) { if (it == 0) "" else GLYPHS[it - 1].toString() + "\uFE0E" } }
    val flipped = ui.flipped

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(flipped) {
                detectTapGestures { o ->
                    val sq = size.width / 8f
                    val c = (o.x / sq).toInt().coerceIn(0, 7)
                    val r = (o.y / sq).toInt().coerceIn(0, 7)
                    val i = r * 8 + c
                    onTap(if (flipped) 63 - i else i)
                }
            }
    ) {
        val sq = size.width / 8f
        for (i in 0 until 64) {
            val q = if (flipped) 63 - i else i
            val x = (i and 7) * sq
            val y = (i shr 3) * sq
            val base = if ((((q shr 3) + q) and 1) == 0) light else shade
            drawRect(base, Offset(x, y), Size(sq, sq))
            if (q == ui.from || q == ui.to) drawRect(Color(0x66F2D35E), Offset(x, y), Size(sq, sq))
            if (q == ui.selected) drawRect(Color(0x884C9BE8), Offset(x, y), Size(sq, sq))
        }
        if (!ui.hidden) {
            fill.textSize = sq * 0.8f
            line.textSize = sq * 0.8f
            line.strokeWidth = sq * 0.035f
            val fm = fill.fontMetrics
            val dy = -(fm.ascent + fm.descent) / 2f
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                for (i in 0 until 64) {
                    val q = if (flipped) 63 - i else i
                    val p = ui.cells[q]
                    if (p == 0) continue
                    val cx = (i and 7) * sq + sq / 2f
                    val cy = (i shr 3) * sq + sq / 2f + dy
                    val white = p > 0
                    val g = glyph[if (p > 0) p else -p]
                    fill.color = if (white) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt()
                    line.color = if (white) 0xFF1B1B1B.toInt() else 0xFFD8D8D8.toInt()
                    nc.drawText(g, cx, cy, fill)
                    nc.drawText(g, cx, cy, line)
                }
            }
        }
        for (i in 0 until 64) {
            val q = if (flipped) 63 - i else i
            if (((ui.targets shr q) and 1L) == 0L) continue
            val cx = (i and 7) * sq + sq / 2f
            val cy = (i shr 3) * sq + sq / 2f
            if (ui.cells[q] != 0) {
                drawCircle(Color(0xAA2A2A2A), sq * 0.46f, Offset(cx, cy), style = Stroke(sq * 0.07f))
            } else {
                drawCircle(Color(0x882A2A2A), sq * 0.14f, Offset(cx, cy))
            }
        }
    }
}
