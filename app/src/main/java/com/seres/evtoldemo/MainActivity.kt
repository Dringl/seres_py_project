package com.seres.evtoldemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.seres.evtoldemo.ui.MainRoute
import com.seres.evtoldemo.ui.theme.EvtolLanDemoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EvtolLanDemoTheme {
                MainRoute()
            }
        }
    }
}
