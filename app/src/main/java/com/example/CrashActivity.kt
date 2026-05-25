package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorMsg = intent.getStringExtra("crash_error") ?: "Неизвестная ошибка"
        
        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFB71C1C)) // Темно-красный фон
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "FATAL CRASH",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Приложение перехватило критический сбой. Отправьте этот лог разработчику:",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMsg,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
