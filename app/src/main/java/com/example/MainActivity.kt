package com.example

import android.Manifest
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
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
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // БЛОК 1: Глобальный отлов ошибок
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        val intent = Intent(this, CrashActivity::class.java).apply {
            putExtra("crash_error", throwable.stackTraceToString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(1)
    }
    
    // Блокировка экрана на Xiaomi/Android 14+ и оптимизация батареи
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Игнорируем если активности нет
            }
        }
    }
    
    // Менеджмент питания: не даем экрану погаснуть 
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
  
  var statusText by remember { mutableStateOf("Спящий режим. Нажмите на сферу для активации.") }
  var speakerState by remember { mutableStateOf(SpeakerState.IDLE) }
  var currentRms by remember { mutableStateOf(0f) }
  
  var engineState by remember { mutableStateOf(0) } // 0 = Спит, 1 = Инициализация, 2 = Готов
  var isMicWorking by remember { mutableStateOf(true) }
  var textInput by remember { mutableStateOf("") }

  // Эти объекты мы создадим ТОЛЬКО когда нажмут старт
  var repository by remember { mutableStateOf<GeminiRepository?>(null) }
  var voiceManager by remember { mutableStateOf<VoiceManager?>(null) }
  var bufferManager by remember { mutableStateOf<StoryBufferManager?>(null) }
  var wakeWordListener by remember { mutableStateOf<WakeWordListener?>(null) }
  val proactiveTimer = remember { ProactiveChatTimer() }

  val userProfile by profileManager.userProfileFlow.collectAsState(
      initial = UserProfile("", "", "", "", "", "", "", "")
  )
  
  val startEngine = {
      if (engineState == 0) {
          engineState = 1
      }
  }

  // Запуск системы (Асинхронно, чтобы не блокировать Main Thread)
  LaunchedEffect(engineState) {
      if (engineState == 1) {
          try {
              statusText = "Инициализация подсистем..."
              speakerState = SpeakerState.THINKING
              
              // Имитируем загрузку для плавности UI, даем отрисоваться
              delay(1000)

              // Создание репозиториев в IO-потоке
              val repo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                  GeminiRepository()
              }
              repository = repo

              val vm = VoiceManager(context) { success ->
                  if (!success) statusText = "Ошибка инициализации TTS"
              }
              vm.updateApiKeys(userProfile.elevenLabsApiKey)
              voiceManager = vm
              val bm = StoryBufferManager(vm)
              bufferManager = bm
              
              val wwl = WakeWordListener(
                  context = context,
                  onWakeWordDetected = {
                      scope.launch {
                          proactiveTimer.stopTimer()
                          bm.stop()
                          speakerState = SpeakerState.SPEAKING
                          vm.speak("Слушаю, ${userProfile.name}", flush = true)
                          statusText = "Активирована! Жду команду..."
                      }
                  },
                  onCommandDetected = { command ->
                      scope.launch {
                          try {
                              proactiveTimer.stopTimer()
                              if (command.contains("стоп") || command.contains("хватит") || command.contains("молчи")) {
                                  bm.stop()
                                  speakerState = SpeakerState.IDLE
                                  statusText = "Остановлено"
                                  vm.speak("Хорошо, молчу.", flush = true)
                                  proactiveTimer.resetTimer()
                              } else {
                                  speakerState = SpeakerState.THINKING
                                  statusText = "Генерирую ответ: $command"
                                  val flow = repo.generateStoryStream(command, userProfile)
                                  bm.processStream(flow) { text ->
                                      statusText = text
                                  }
                                  speakerState = SpeakerState.SPEAKING
                                  proactiveTimer.resetTimer()
                              }
                          } catch(e: Exception) {
                              statusText = "Ошибка: ${e.message}"
                              speakerState = SpeakerState.ERROR
                              proactiveTimer.resetTimer()
                          }
                      }
                  },
                  onStateChanged = { state -> 
                      if (speakerState != SpeakerState.SPEAKING || (speakerState == SpeakerState.SPEAKING && state == SpeakerState.IDLE)) {
                         speakerState = state 
                      }
                      if (state == SpeakerState.IDLE) {
                          proactiveTimer.resetTimer()
                      }
                  },
                  onRmsLevelChanged = { rms ->
                      if (speakerState == SpeakerState.LISTENING) {
                          currentRms = rms
                          proactiveTimer.resetTimer()
                      }
                  }
              )
              
              wwl.onDebugText = { debugMsg ->
                  statusText = debugMsg
              }
              
              wakeWordListener = wwl
              
              // Запуск слушателя
              delay(500)
              val started = wwl.startListening()
              if (!started) {
                  isMicWorking = false
                  Toast.makeText(context, "Внимание: Микрофон недоступен. Режим чата.", Toast.LENGTH_LONG).show()
                  statusText = "Микрофон недоступен. Напишите текст."
              } else {
                  statusText = "Анализ звука запущен. Скажите «Эй малышка»"
                  proactiveTimer.resetTimer()
              }
              speakerState = SpeakerState.IDLE
              engineState = 2
          } catch(e: Exception) {
              statusText = "Ошибка при запуске: ${e.message}"
              speakerState = SpeakerState.ERROR
              engineState = 0
          }
      }
  }

  LaunchedEffect(userProfile.elevenLabsApiKey) {
      voiceManager?.updateApiKeys(userProfile.elevenLabsApiKey)
  }

  // Запуск проактивного диалога
  LaunchedEffect(engineState) {
      if (engineState == 2) {
          proactiveTimer.proactiveTrigger.collectLatest {
              try {
                  val prompt = "[SYSTEM_EVENT: Пользователь молчит уже 3 минуты. Инициируй короткий диалог сама. " +
                               "Спроси как дела, пошути, предложи обсудить трейдинг или велосипеды, или просто мило помурлыкай]"
                  speakerState = SpeakerState.THINKING
                  statusText = "Проявляю инициативу..."
                  val flow = repository?.generateStoryStream(prompt, userProfile)
                  if (flow != null) {
                      bufferManager?.processStream(flow) { text ->
                          statusText = text
                      }
                  }
                  speakerState = SpeakerState.SPEAKING
                  proactiveTimer.resetTimer()
              } catch (e: Exception) {
                  statusText = "Сбой проактивности: ${e.message}"
              }
          }
      }
  }

  DisposableEffect(Unit) {
      onDispose {
          wakeWordListener?.stopListening()
          bufferManager?.stop()
          proactiveTimer.stopTimer()
      }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      .then(
          if (engineState == 0) {
              Modifier.clickable {
                  if (micPermissionState.status.isGranted) {
                      startEngine()
                  }
              }
          } else {
              Modifier
          }
      ),
    contentAlignment = Alignment.Center
  ) {
    if (micPermissionState.status.isGranted) {
      if (engineState > 0) {
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
              verticalArrangement = Arrangement.Center,
              modifier = Modifier.fillMaxWidth().padding(16.dp)
          ) {
              Box(
                  modifier = Modifier
                      .clip(CircleShape)
                      .clickable {
                          if (engineState > 0) {
                              bufferManager?.stop()
                              speakerState = SpeakerState.IDLE
                              statusText = "Принудительная остановка"
                          }
                      }
              ) {
                  OrbAnimation(
                      state = speakerState,
                      rms = currentRms,
                      geminiApiKey = userProfile.geminiApiKey
                  )
              }
              
              Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                  Text(
                    text = statusText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                  )
              }
              
              Spacer(modifier = Modifier.padding(top = 16.dp))
              
              OutlinedTextField(
                  value = textInput,
                  onValueChange = { textInput = it },
                  placeholder = { Text("Введи команду или позови «эй малыш»...") },
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                  trailingIcon = {
                      IconButton(onClick = {
                          if (textInput.isNotBlank()) {
                              scope.launch {
                                  try {
                                      proactiveTimer.stopTimer()
                                      bufferManager?.stop()
                                      speakerState = SpeakerState.THINKING
                                      statusText = "Запрос: $textInput"
                                      val flow = repository?.generateStoryStream(textInput, userProfile)
                                      if (flow != null) bufferManager?.processStream(flow) { text ->
                                          statusText = text
                                      }
                                      speakerState = SpeakerState.SPEAKING
                                      textInput = ""
                                      proactiveTimer.resetTimer()
                                  } catch(e: Exception) {
                                      statusText = "Ошибка: ${e.message}"
                                      speakerState = SpeakerState.ERROR
                                      proactiveTimer.resetTimer()
                                  }
                              }
                          }
                      }) {
                          Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = Color.White)
                      }
                  },
                  colors = OutlinedTextFieldDefaults.colors(
                      focusedTextColor = Color.White,
                      unfocusedTextColor = Color.White,
                      focusedBorderColor = Color(0xFF9C27B0),
                      unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                      focusedLabelColor = Color(0xFF9C27B0),
                      unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                  )
              )
          }
      } else {
          // Экран до старта (ожидаем клика для активации движка)
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
              OrbAnimation(state = SpeakerState.IDLE, rms = 0f, geminiApiKey = userProfile.geminiApiKey)
              Text(
                text = "Сплю. Нажми в любое место, чтобы разбудить.",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 24.dp)
              )
          }
      }
    } else {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = "Требуется доступ к микрофону для разговора.",
          color = Color.White,
          modifier = Modifier.padding(bottom = 16.dp)
        )
        Button(onClick = { micPermissionState.launchPermissionRequest() }) {
          Text("Выдать разрешение")
        }
      }
    }
  }
}

@Composable
fun OrbAnimation(state: SpeakerState, rms: Float, geminiApiKey: String) {
    val currentKey = geminiApiKey.ifBlank { com.example.BuildConfig.GEMINI_API_KEY }
    val apiKeyMissing = currentKey.isEmpty() || currentKey == "MY_GEMINI_API_KEY"
    val infiniteTransition = rememberInfiniteTransition(label = "OrbTransition")
    
    val idleScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "IdleScale"
    )
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "ThinkingRotation"
    )

    val animatedRms by animateFloatAsState(
        targetValue = maxOf(0f, rms),
        animationSpec = tween(durationMillis = 100),
        label = "RmsScale"
    )
    
    val baseScale = when(state) {
        SpeakerState.IDLE -> idleScale
        SpeakerState.ERROR -> idleScale
        SpeakerState.THINKING -> 1.0f
        SpeakerState.LISTENING -> 1f + (animatedRms / 15f)
        SpeakerState.SPEAKING -> 1f + (idleScale * 0.2f)
    }
    
    val color = when(state) {
        SpeakerState.IDLE -> if (apiKeyMissing) Color.Red else Color(0xFF607D8B)
        SpeakerState.ERROR -> Color.Red
        SpeakerState.LISTENING -> Color(0xFFE91E63)
        SpeakerState.THINKING -> Color(0xFF2196F3)
        SpeakerState.SPEAKING -> Color(0xFF9C27B0)
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val radius = 150f * baseScale
            
            drawCircle(
                color = color.copy(alpha = 0.8f),
                radius = radius,
                center = center
            )
            
            if (state == SpeakerState.THINKING) {
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
