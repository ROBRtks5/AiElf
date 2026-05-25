package com.example.tts

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.Retrofit
import java.io.File
import java.util.Locale
import java.io.FileOutputStream

// API Клиент для ElevenLabs
interface ElevenLabsApiService {
    @POST("v1/text-to-speech/{voice_id}/stream")
    suspend fun textToSpeechStream(
        @Path("voice_id") voiceId: String,
        @Header("xi-api-key") apiKey: String,
        @Body request: Map<String, Any>
    ): ResponseBody
}

class VoiceManager(private val context: Context, private val onInitCompleted: (Boolean) -> Unit) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private val mediaPlayer = MediaPlayer()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var playbackJob: Job? = null
    
    // Вставь свой ключ сюда
    private val ELEVEN_LABS_API_KEY = "" 
    private val VOICE_ID = "EXAVITQu4vr4xnSDxMaL" // Sarah или Bella
    
    private val elevenLabsApi: ElevenLabsApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.elevenlabs.io/")
            .build()
            .create(ElevenLabsApiService::class.java)
    }
    
    init {
        tts = TextToSpeech(context, this)
        startPlaybackLoop()
    }
    
    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            for (text in queue) {
                playChunk(text)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("VoiceManager", "Язык не поддерживается или отсутствуют голосовые данные")
                onInitCompleted(false)
            } else {
                tts?.setPitch(1.3f)
                tts?.setSpeechRate(1.1f)
                
                tts?.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("VoiceManager", "Начали говорить")
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d("VoiceManager", "Закончили говорить")
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e("VoiceManager", "Ошибка TTS")
                    }
                })
                
                onInitCompleted(true)
            }
        } else {
            Log.e("VoiceManager", "Ошибка инициализации TTS")
            onInitCompleted(false)
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (flush) {
            stop()
        }
        
        if (ELEVEN_LABS_API_KEY.isNotBlank() && ELEVEN_LABS_API_KEY != "YOUR_KEY_HERE") {
            // Отправляем в очередь ElevenLabs
            queue.trySend(text)
        } else {
            fallbackTts(text, flush)
        }
    }

    private suspend fun playChunk(text: String) {
        try {
            val request = mapOf(
                "text" to text,
                "model_id" to "eleven_multilingual_v2"
            )
            val response = elevenLabsApi.textToSpeechStream(VOICE_ID, ELEVEN_LABS_API_KEY, request)
            val tempFile = File.createTempFile("voice", ".mp3", context.cacheDir)
            
            response.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Воспроизводим и ждем окончания
            val completionDeferred = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                try {
                    mediaPlayer.reset()
                    mediaPlayer.setDataSource(tempFile.absolutePath)
                    mediaPlayer.setOnCompletionListener {
                        completionDeferred.complete(Unit)
                    }
                    mediaPlayer.setOnPreparedListener { 
                        it.start() 
                    }
                    mediaPlayer.prepareAsync()
                } catch (e: Exception) {
                    Log.e("VoiceManager", "MediaPlayer error: ${e.message}")
                    fallbackTts(text, true)
                    completionDeferred.complete(Unit)
                }
            }
            completionDeferred.await() // Ждем, пока этот кусочек доиграет, перед следующим
        } catch (e: Exception) {
            Log.e("VoiceManager", "ElevenLabs error: ${e.message}. Fallbacking to local TTS.")
            withContext(Dispatchers.Main) {
                fallbackTts(text, true)
            }
        }
    }

    private fun fallbackTts(text: String, flush: Boolean) {
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, "UtteranceId_" + System.currentTimeMillis())
    }

    fun stop() {
        tts?.stop()
        if (mediaPlayer.isPlaying) {
            mediaPlayer.stop()
        }
        // Очищаем очередь путем пересоздания цикла
        // Это прервет текущий chunk и сбросит очередь
        while(queue.tryReceive().isSuccess) { /* drain */ }
        startPlaybackLoop()
    }

    fun shutdown() {
        playbackJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        mediaPlayer.release()
    }
}
