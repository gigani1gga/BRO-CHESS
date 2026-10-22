package com.example.chesslab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chesslab.arena.ArenaStats
import com.example.chesslab.arena.ArenaTrainer
import com.example.chesslab.train.WeightStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ArenaUi(
    val available: Boolean = false,
    val running: Boolean = false,
    val stats: ArenaStats = ArenaStats()
)

class ArenaViewModel(app: Application) : AndroidViewModel(app) {
    private val store = WeightStore(app.filesDir)
    private val binary = File(app.applicationInfo.nativeLibraryDir, "libstockfish.so")
    private val trainer = ArenaTrainer(
        viewModelScope, binary, store.load("arena"),
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    )
    private val _ui = MutableStateFlow(ArenaUi(available = binary.exists()))
    val ui: StateFlow<ArenaUi> = _ui
    private var userWantsRun = false
    private var foreground = true

    init {
        viewModelScope.launch {
            trainer.stats.collect { s -> _ui.update { it.copy(stats = s) } }
        }
        viewModelScope.launch {
            trainer.running.collect { r -> _ui.update { it.copy(running = r) } }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                persist()
            }
        }
    }

    private fun persist() {
        val w = trainer.weights()
        viewModelScope.launch(Dispatchers.IO) { store.save("arena", w) }
    }

    fun toggle() {
        if (!_ui.value.available) return
        if (userWantsRun) {
            userWantsRun = false
            trainer.stop()
            persist()
        } else {
            userWantsRun = true
            trainer.start()
        }
    }

    fun reset() {
        trainer.reset()
        persist()
    }

    fun setForeground(fg: Boolean) {
        foreground = fg
        if (!fg) {
            trainer.running.value = false
            store.save("arena", trainer.weights())
        } else if (userWantsRun) {
            trainer.running.value = true
        }
    }

    override fun onCleared() {
        trainer.stop()
        store.save("arena", trainer.weights())
    }
}
