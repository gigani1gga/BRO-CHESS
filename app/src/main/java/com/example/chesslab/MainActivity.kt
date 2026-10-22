package com.example.chesslab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.chesslab.ui.App
import com.example.chesslab.ui.ArenaViewModel
import com.example.chesslab.ui.LabViewModel

class MainActivity : ComponentActivity() {
    private val lab: LabViewModel by viewModels()
    private val arena: ArenaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(lab, arena) }
    }

    // Pause all background learning while the app is not visible (saves battery and heat).
    override fun onStart() {
        super.onStart()
        lab.setForeground(true)
        arena.setForeground(true)
    }

    override fun onStop() {
        lab.setForeground(false)
        arena.setForeground(false)
        super.onStop()
    }
}
