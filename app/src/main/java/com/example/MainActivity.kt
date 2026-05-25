package com.example

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.api.GeminiRepository
import com.example.profile.ProfileManager
import com.example.profile.ProfileScreen
import com.example.profile.UserProfile
import com.example.tts.StoryBufferManager
import com.example.tts.VoiceManager
import com.example.ui.theme.MyApplicationTheme
import com.example.wakeword.ProactiveChatTimer
import com.example.wakeword.SpeakerState
import com.example.wakeword.WakeWordListener
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Менеджмент питания: не даем экрану погаснуть (Блок 1) 
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        var currentScreen by remember { mutableStateOf("home") }
        val context = LocalContext.current
        val profileManager = remember { ProfileManager(context) }
        
        if (currentScreen == "profile") {
            ProfileScreen(
                profileManager = profileManager,
                onBack = { currentScreen = "home" }
            )
        } else {
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
              SmartSpeakerApp(
                  modifier = Modifier.padding(innerPadding),
                  profileManager = profileManager,
                  onSettingsClick = { currentScreen = "profile" }
              )
            }
        }
      }
    }
  }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SmartSpeakerApp(modifier: Modifier = Modifier, profileManager: ProfileManager, onSettingsClick: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val micPermissionState = rememberPermissionState(permission = Manifest.permission.RECORD_AUDIO)
  
  var statusText by remember { mutableStateOf("Загрузка...") }
  var speakerState by remember { mutableStateOf(SpeakerState.IDLE) }
  var currentRms by remember { mutableStateOf(0f) }

  val repository = remember { GeminiRepository() }
  
  val userProfile by profileManager.userProfileFlow.collectAsState(
      initial = UserProfile("", "", "", "", "", "")
  )
  
  val voiceManager = remember {
      VoiceManager(context) { success ->
          if (success) {
              statusText = "Голос готов. Скажите «эй малышка»"
          } else {
              statusText = "Ошибка инициализации голоса"
          }
      }
  }
  
  val bufferManager = remember(voiceManager) { StoryBufferManager(voiceManager) }
  val proactiveTimer = remember { ProactiveChatTimer() }

  val wakeWordListener = remember(userProfile.name) { // Пересоздаем если обновился профиль, чтобы контекст был свежим
      WakeWordListener(
          context = context,
          onWakeWordDetected = {
              scope.launch {
                  proactiveTimer.stopTimer()
                  bufferManager.stop()
                  speakerState = SpeakerState.SPEAKING
                  voiceManager.speak("Слушаю, ${userProfile.name}", flush = true)
                  statusText = "Активирована! Слушаю..."
              }
          },
          onCommandDetected = { command ->
              proactiveTimer.stopTimer() // Пока идет команда или генерация - сбрасываем таймер
              if (command.contains("стоп") || command.contains("хватит") || command.contains("молчи")) {
                  bufferManager.stop()
                  speakerState = SpeakerState.IDLE
                  statusText = "Остановлено"
                  voiceManager.speak("Хорошо, молчу.", flush = true)
                  proactiveTimer.resetTimer()
              } else {
                  speakerState = SpeakerState.THINKING
                  statusText = "Генерирую ответ: $command"
                  val flow = repository.generateStoryStream(command, userProfile)
                  bufferManager.processStream(flow)
                  speakerState = SpeakerState.SPEAKING
                  proactiveTimer.resetTimer()
              }
          },
          onStateChanged = { state -> 
              if (speakerState != SpeakerState.SPEAKING || (speakerState == SpeakerState.SPEAKING && state == SpeakerState.IDLE)) {
                 speakerState = state 
              }
              if (state == SpeakerState.IDLE) {
                  proactiveTimer.resetTimer() // Запускаем таймер проактивности, когда молчим
              }
          },
          onRmsChanged = { rms ->
              if (speakerState == SpeakerState.LISTENING) {
                  currentRms = rms
                  // Любой шум тоже сбрасывает таймер, значит пользователь тут
                  proactiveTimer.resetTimer()
              }
          }
      )
  }

  // Запуск проактивного диалога
  LaunchedEffect(Unit) {
      proactiveTimer.proactiveTrigger.collectLatest {
          val prompt = "[SYSTEM_EVENT: Пользователь молчит уже 3 минуты. Инициируй короткий диалог сама. " +
                       "Спроси как дела, пошути, предложи обсудить трейдинг или велосипеды, или просто мило помурлыкай]"
          speakerState = SpeakerState.THINKING
          statusText = "Проявляю инициативу..."
          val flow = repository.generateStoryStream(prompt, userProfile)
          bufferManager.processStream(flow)
          speakerState = SpeakerState.SPEAKING
          proactiveTimer.resetTimer()
      }
  }

  LaunchedEffect(micPermissionState.status.isGranted) {
      if (micPermissionState.status.isGranted) {
          wakeWordListener.startListening()
          proactiveTimer.resetTimer()
      }
  }

  DisposableEffect(Unit) {
      onDispose {
          wakeWordListener.stopListening()
          bufferManager.stop()
          proactiveTimer.stopTimer()
      }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black) // Тёмная тема AOD
      .clickable {
         bufferManager.stop()
         speakerState = SpeakerState.IDLE
         statusText = "Принудительная остановка (клик)"
      },
    contentAlignment = Alignment.Center
  ) {
    if (micPermissionState.status.isGranted) {
      IconButton(
         onClick = onSettingsClick,
         modifier = Modifier
             .align(Alignment.TopEnd)
             .padding(16.dp)
      ) {
         Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Настройки Профиля",
            tint = Color.White.copy(alpha = 0.6f)
         )
      }
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
      ) {
          // Блок 3: UI Анимация живой материи
          OrbAnimation(
              state = speakerState,
              rms = currentRms
          )
          
          Text(
            text = statusText,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 32.dp)
          )
      }
    } else {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = "Need Microphone Permission for Voice Commands.",
          color = Color.White,
          modifier = Modifier.padding(bottom = 16.dp)
        )
        Button(onClick = { micPermissionState.launchPermissionRequest() }) {
          Text("Grant Permission")
        }
      }
    }
  }
}

@Composable
fun OrbAnimation(state: SpeakerState, rms: Float) {
    val apiKeyMissing = com.example.BuildConfig.GEMINI_API_KEY.isEmpty() || com.example.BuildConfig.GEMINI_API_KEY == "MY_GEMINI_API_KEY"
    val infiniteTransition = rememberInfiniteTransition(label = "OrbTransition")
    
    // Плавное дыхание (Idle)
    val idleScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "IdleScale"
    )
    
    // Вращение (Thinking)
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ThinkingRotation"
    )

    // Аудио пульсация (Listening, Speaking)
    val animatedRms by animateFloatAsState(
        targetValue = maxOf(0f, rms),
        animationSpec = tween(durationMillis = 100),
        label = "RmsScale"
    )
    
    val baseScale = when(state) {
        SpeakerState.IDLE -> idleScale
        SpeakerState.ERROR -> idleScale
        SpeakerState.THINKING -> 1.0f
        SpeakerState.LISTENING -> 1f + (animatedRms / 15f) // RMS обычно от 0 до 10-15
        SpeakerState.SPEAKING -> 1f + (idleScale * 0.2f) // Простая стимуляция речи
    }
    
    val color = when(state) {
        SpeakerState.IDLE -> if (apiKeyMissing) Color.Red else Color(0xFF607D8B) // Серо-синий или красный при ошибке
        SpeakerState.ERROR -> Color.Red // Красный при ошибке ключа
        SpeakerState.LISTENING -> Color(0xFFE91E63) // Розовый эльфийский
        SpeakerState.THINKING -> Color(0xFF2196F3) // Голубой
        SpeakerState.SPEAKING -> Color(0xFF9C27B0) // Фиолетовый
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val radius = 150f * baseScale
            
            // Внутренний шар (сплошной)
            drawCircle(
                color = color.copy(alpha = 0.8f),
                radius = radius,
                center = center
            )
            
            // Внешнее кольцо (или волны)
            if (state == SpeakerState.THINKING) {
                // Вращающиеся дуги
                drawArc(
                    color = color,
                    startAngle = rotation,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = 10f),
                    size = androidx.compose.ui.geometry.Size(radius * 2.5f, radius * 2.5f),
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - radius * 1.25f, center.y - radius * 1.25f)
                )
                drawArc(
                    color = color,
                    startAngle = rotation + 180f,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = 10f),
                    size = androidx.compose.ui.geometry.Size(radius * 2.5f, radius * 2.5f),
                    topLeft = androidx.compose.ui.geometry.Offset(center.x - radius * 1.25f, center.y - radius * 1.25f)
                )
            } else {
                // Внешнее свечение/волны 
                val outerRadius = radius * 1.3f
                drawCircle(
                    color = color.copy(alpha = 0.3f),
                    radius = outerRadius,
                    center = center
                )
            }
        }
    }
}
