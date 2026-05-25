package com.example.wakeword

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class WakeWordListener(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onCommandDetected: (String) -> Unit,
    private val onStateChanged: (SpeakerState) -> Unit,
    private val onRmsLevelChanged: (Float) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isCommandMode = false
    private val wakeWords = listOf("малышка", "малыш", "детка", "эй", "проснись", "компьютер", "привет", "слушай")

    // Добавим callback для отладки
    var onDebugText: ((String) -> Unit)? = null

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("WakeWordListener", "Ошибка при очистке рекогнайзера: ${e.message}")
        } finally {
            speechRecognizer = null
        }
    }

    fun startListening(): Boolean {
        // Всегда уничтожаем предыдущий SpeechRecognizer перед запуском нового во избежание зависаний и утечек ресурсов
        destroyRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("WakeWordListener", "SpeechRecognizer не поддерживается на этом устройстве")
            onDebugText?.invoke("Голосовой ввод недоступен на устройстве")
            return false
        }

        try {
            // Принудительно задействуем оригинальный Google Speech Recognition сервис для обхода проблем кастомных прошивок (Xiaomi MIUI/HyperOS, etc.)
            try {
                val googleComponent = android.content.ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
                )
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext, googleComponent)
                Log.d("WakeWordListener", "Успешно привязан к Google Speech Service")
            } catch (e: Exception) {
                Log.e("WakeWordListener", "Google Speech Service недоступен. Использование системного дефолта: ${e.message}")
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onStateChanged(SpeakerState.LISTENING)
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    onRmsLevelChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    if (isCommandMode) {
                        onStateChanged(SpeakerState.THINKING)
                    } else {
                        onStateChanged(SpeakerState.IDLE)
                    }
                }

                override fun onError(error: Int) {
                    val msg = getErrorMessage(error)
                    Log.e("WakeWordListener", "Код ошибки: $error - $msg")
                    onDebugText?.invoke("Ошибка микрофона: $msg")
                    
                    isListening = false
                    isCommandMode = false

                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        return
                    }

                    // Перезапускаем слушатель через 1.5 секунды, давая системе освободить аудио-ресурсы
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startListening()
                    }, 1500)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val bestMatch = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                    Log.d("WakeWordListener", "Распознано: $bestMatch")
                    onDebugText?.invoke("Услышал: $bestMatch")

                    var handledAsCommand = false

                    if (isCommandMode) {
                        if (bestMatch.isNotBlank()) {
                            isCommandMode = false
                            handledAsCommand = true
                            onCommandDetected(bestMatch)
                        } else {
                            onDebugText?.invoke("Не расслышала команду, повторите...")
                        }
                    } else {
                        val triggeredWakeWord = wakeWords.firstOrNull { bestMatch.contains(it) }
                        if (triggeredWakeWord != null) {
                            onWakeWordDetected()
                            val command = bestMatch.substringAfter(triggeredWakeWord).trim()
                            if (command.isNotEmpty()) {
                                handledAsCommand = true
                                onCommandDetected(command)
                            } else {
                                isCommandMode = true
                                onDebugText?.invoke("Активирована! Жду команду...")
                            }
                        }
                    }

                    if (!isCommandMode && !handledAsCommand) {
                        onStateChanged(SpeakerState.IDLE)
                    }

                    isListening = false

                    // Мягкий перезапуск для непрерывного приема голоса
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startListening()
                    }, 500)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val bestMatch = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                    if (bestMatch.isNotBlank()) {
                        onDebugText?.invoke("Слушаю: $bestMatch...")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            return true
        } catch (e: Exception) {
            Log.e("WakeWord", "Исключение при запуске SpeechRecognizer: ${e.message}")
            isListening = false
            return false
        }
    }

    fun stopListening() {
        destroyRecognizer()
        isListening = false
        onStateChanged(SpeakerState.IDLE)
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Ошибка аудио"
            SpeechRecognizer.ERROR_CLIENT -> "Ошибка клиента"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет прав"
            SpeechRecognizer.ERROR_NETWORK -> "Ошибка сети"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Таймаут сети"
            SpeechRecognizer.ERROR_NO_MATCH -> "Не распознано"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Движок занят"
            SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Долгое молчание"
            else -> "Неизвестная ошибка: $errorCode"
        }
    }
}
