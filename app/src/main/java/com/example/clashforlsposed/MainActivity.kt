package com.example.clashforlsposed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.clashforlsposed.ui.ClashDashboardScreen
import com.example.clashforlsposed.ui.theme.ClashForLSPosedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClashForLSPosedTheme {
                ClashDashboardScreen()
            }
        }
    }
}
