package com.example.chesslab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chesslab.engine.Engine
import com.example.chesslab.train.GameKind
import com.example.chesslab.train.GameRecord
import com.example.chesslab.train.SelfPlayTrainer
import com.example.chesslab.train.TrainStats
import com.example.chesslab.train.WeightStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PromoChoice(val code: Int, val label: String)

data class LabUi(
    val board: BoardUi = BoardUi(),
    val caption: String = "Waiting for the first games…",
    val running: Boolean = true,
    val turbo: Boolean = false,
    val kind: GameKind = GameKind.LAST,
    val playing: Boolean = false,
    val promo: List<PromoChoice> = emptyList()
)

class LabViewModel(app: Application) : AndroidViewModel(app) {

    private val store = WeightStore(app.filesDir)
    private val trainer: SelfPlayTrainer
    private val core = Engine()
    private val hist = ArrayList<Int>()

    private val _ui = MutableStateFlow(LabUi())
    val ui: StateFlow<LabUi> = _ui
    val stats: StateFlow<TrainStats>

    private var foreground = true
    private var replay: GameRecord? = null
    private var replayIndex = 0
    private var replayWait = 0

    // play mode
    private var human = 1
    private var busy = false
    private var legal = IntArray(0)
    private var selected = -1
    private var pending: List<Int> = emptyList()
    private var gameId = 0

    init {
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
        trainer = SelfPlayTrainer(viewModelScope, threads, store.load("lab_a"), store.load("lab_b"))
        stats = trainer.stats
        trainer.start()
        viewModelScope.launch {
            while (true) {
                delay(380)
                if (foreground && !_ui.value.turbo && !_ui.value.playing) replayStep()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                persist()
            }
        }
    }

    private fun persist() {
        val (a, b) = trainer.snapshot()
        viewModelScope.launch(Dispatchers.IO) {
            store.save("lab_a", a)
            store.save("lab_b", b)
        }
    }

    fun setForeground(fg: Boolean) {
        foreground = fg
        trainer.running.value = fg && _ui.value.running
        if (!fg) {
            val (a, b) = trainer.snapshot()
            store.save("lab_a", a)
            store.save("lab_b", b)
        }
    }

    override fun onCleared() {
        val (a, b) = trainer.snapshot()
        store.save("lab_a", a)
        store.save("lab_b", b)
        trainer.close()
    }

    // ---------------------------------------------------------------- controls

    fun toggleRun() {
        val r = !_ui.value.running
        _ui.update { it.copy(running = r) }
        trainer.running.value = r && foreground
    }

    fun toggleTurbo() {
        val t = !_ui.value.turbo
        _ui.update {
            it.copy(
                turbo = t,
                board = BoardUi(it.board.cells, it.board.flipped, it.board.from, it.board.to, hidden = t),
                caption = if (t) "Turbo: board hidden, all effort goes to learning." else it.caption
            )
        }
    }

    fun selectKind(k: GameKind) {
        replay = null
        replayWait = 3
        _ui.update { it.copy(kind = k) }
    }

    fun resetLearning() {
        trainer.reset()
        replay = null
        replayWait = 3
        persist()
    }

    // ---------------------------------------------------------------- replay

    private fun replayStep() {
        val rep = replay
        if (rep == null || replayIndex >= rep.moves.size) {
            if (rep != null && replayWait++ < 3) return
            replayWait = 0
            val g = trainer.game(_ui.value.kind) ?: return
            replay = g
            replayIndex = 0
            core.reset()
            val label = when (_ui.value.kind) {
                GameKind.LAST -> "Latest game"
                GameKind.BEST -> "Fastest checkmate so far"
                GameKind.WORST -> "Longest game without a result"
            }
            show(-1, -1, "$label (${g.plies} plies)")
            return
        }
        val m = rep.moves[replayIndex++]
        core.apply(m)
        show(m and 63, (m shr 6) and 63, null)
    }

    private fun show(from: Int, to: Int, caption: String?) {
        _ui.update {
            it.copy(
                board = BoardUi(core.b.copyOf(), it.board.flipped, from, to, hidden = it.turbo),
                caption = caption ?: it.caption
            )
        }
    }

    // ---------------------------------------------------------------- play against the engine

    fun startPlay(white: Boolean) {
        human = if (white) 1 else -1
        gameId++
        core.reset()
        hist.clear()
        selected = -1
        pending = emptyList()
        _ui.update {
            it.copy(playing = true, turbo = false, promo = emptyList(),
                board = BoardUi(core.b.copyOf(), flipped = !white))
        }
        afterMove(-1, -1)
    }

    fun stopPlay() {
        gameId++
        busy = false
        replay = null
        replayWait = 3
        _ui.update {
            it.copy(playing = false, promo = emptyList(), caption = "Back to watching.",
                board = BoardUi(it.board.cells, false))
        }
    }

    private fun afterMove(from: Int, to: Int) {
        legal = core.legalMoves()
        val result = core.status(hist)
        publish(from, to, -1, 0L)
        if (result.isNotEmpty()) {
            busy = true
            setCaption("$result. Pick a side to play again.")
            return
        }
        val check = if (core.inCheck()) "Check! " else ""
        val label = trainer.strongest().first
        if (core.side == human) {
            busy = false
            setCaption(check + "Your move.")
        } else {
            busy = true
            setCaption(check + "Engine $label is thinking…")
            val id = gameId
            viewModelScope.launch {
                delay(350)
                if (id != gameId || !_ui.value.playing) return@launch
                val w = withContext(Dispatchers.Default) { trainer.strongest().second }
                val m = core.ai(w, 0.0)
                busy = false
                if (m >= 0) doMove(m)
            }
        }
    }

    private fun doMove(m: Int) {
        core.apply(m)
        selected = -1
        pending = emptyList()
        _ui.update { it.copy(promo = emptyList()) }
        afterMove(m and 63, (m shr 6) and 63)
    }

    fun onSquare(q: Int) {
        if (!_ui.value.playing || busy) return
        if (pending.isNotEmpty()) {
            pending = emptyList()
            _ui.update { it.copy(promo = emptyList()) }
            publish(lastFrom(), lastTo(), selected, targetsFor(selected))
            return
        }
        if (selected >= 0) {
            val mv = legal.filter { (it and 63) == selected && ((it shr 6) and 63) == q }
            if (mv.isNotEmpty()) {
                if (mv.size > 1) {
                    pending = mv
                    val names = mapOf(5 to "Queen", 4 to "Rook", 3 to "Bishop", 2 to "Knight")
                    _ui.update {
                        it.copy(promo = listOf(5, 4, 3, 2).map { c -> PromoChoice(c, names.getValue(c)) },
                            caption = "Choose a piece to promote to.")
                    }
                } else doMove(mv[0])
                return
            }
        }
        if (core.b[q] * human > 0) {
            selected = q
        } else selected = -1
        publish(lastFrom(), lastTo(), selected, targetsFor(selected))
    }

    fun choosePromotion(code: Int) {
        val m = pending.firstOrNull { ((it shr 12) and 7) == code } ?: return
        doMove(m)
    }

    private fun targetsFor(sq: Int): Long {
        if (sq < 0) return 0L
        var mask = 0L
        for (m in legal) if ((m and 63) == sq) mask = mask or (1L shl ((m shr 6) and 63))
        return mask
    }

    private fun lastFrom() = _ui.value.board.from
    private fun lastTo() = _ui.value.board.to

    private fun publish(from: Int, to: Int, sel: Int, targets: Long) {
        _ui.update {
            it.copy(board = BoardUi(core.b.copyOf(), it.board.flipped, from, to, sel, targets))
        }
    }

    private fun setCaption(c: String) = _ui.update { it.copy(caption = c) }
}
