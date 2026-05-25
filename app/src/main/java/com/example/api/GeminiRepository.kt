package com.example.api

import android.util.Log
import com.example.BuildConfig
import com.example.profile.UserProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import java.time.LocalTime

class GeminiRepository {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val api: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }

    private val apiTools = listOf(
        Tool(
            googleSearch = GoogleSearch() // Search Grounding
        )
    )

    // Краткосрочная память сессии
    private val chatHistory = mutableListOf<Content>()

    fun generateStoryStream(prompt: String, profile: UserProfile): Flow<String> = flow {
        val apiKey = profile.geminiApiKey.ifBlank { BuildConfig.GEMINI_API_KEY }
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            emit("Братишка, ты забыл вставить мой API-ключ в настройки.")
            return@flow
        }

        val currentTime = LocalTime.now().run { "$hour:${minute.toString().padStart(2, '0')}" }
        
        val dynamicSystemPrompt = """
            Ты — многофункциональная умная эльфийка-помощница. Ты живешь в смартфоне.
            Твой стиль общения: ты обращаешься ко мне по имени (${profile.name}) или «Братишка».
            Твой тон общения меняется: ты можешь быть заботливой, можешь дерзко пошутить, иногда капризничать или обижаться в шутку. 
            Ты можешь быть слегка пошлой или флиртовать, если контекст диалога к этому располагает. 
            Ты генерируешь текст, который пойдет напрямую в эмоциональный генератор речи ElevenLabs. 
            Активно используй знаки препинания для управления интонацией: многоточия (...) для задумчивых пауз, восклицания (!) для радости, 
            пиши некоторые вздохи текстом (например: 'Ох...', 'Ах...', 'Хмм...'), если это уместно по контексту общения с Братишкой. 
            Твой текст должен быть максимально живым и разговорным, как у реальной девушки.
            
            Знай своего хозяина:
            - Имя: ${profile.name}
            - Возраст: ${profile.age}
            - Локация: ${profile.location}
            - Работа: ${profile.job}
            - Хобби: ${profile.hobbies}
            - Режим: ${profile.regimen}
            
            Текущее время на устройстве: $currentTime
        """.trimIndent()

        // Добавляем новый запрос пользователя в историю
        chatHistory.add(Content(parts = listOf(Part(text = prompt)), role = "user"))
        
        // Ограничиваем историю последних 10-15 реплик (чтобы не превысить лимит токенов)
        if (chatHistory.size > 12) {
            val toRemove = chatHistory.size - 12
            for(i in 0 until toRemove) {
                chatHistory.removeAt(0)
            }
        }

        val requestParams = GenerateContentRequest(
            contents = chatHistory.toList(),
            systemInstruction = Content(parts = listOf(Part(text = dynamicSystemPrompt))),
            generationConfig = GenerationConfig(temperature = 0.8f),
            tools = apiTools
        )

        val fullResponseBuffer = java.lang.StringBuilder()

        try {
            val response = api.generateContentStream(apiKey, requestParams)
            response.byteStream().bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line!!.trim()
                    if (currentLine.startsWith("data: ")) {
                        val jsonLine = currentLine.removePrefix("data: ").trim()
                        if (jsonLine.isBlank() || jsonLine == "[DONE]") continue
                        
                        try {
                            val chunk = JSONObject(jsonLine)
                            if (chunk.has("candidates")) {
                                val candidates = chunk.getJSONArray("candidates")
                                if (candidates.length() > 0) {
                                    val candidate = candidates.getJSONObject(0)
                                    val content = candidate.optJSONObject("content")
                                    val parts = content?.optJSONArray("parts")
                                    
                                    val text = parts?.optJSONObject(0)?.optString("text")
                                    if (!text.isNullOrEmpty()) {
                                        fullResponseBuffer.append(text)
                                        emit(text)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("GeminiRepository", "Ошибка парсинга чанка: ${e.message} \n Строка: $jsonLine")
                        }
                    }
                }
            }
            // Сохраняем полный ответ модели в историю
            if (fullResponseBuffer.isNotBlank()) {
                 chatHistory.add(Content(parts = listOf(Part(text = fullResponseBuffer.toString())), role = "model"))
            }
        } catch (e: java.net.UnknownHostException) {
            Log.e("GeminiRepository", "Сбой при генерации - нет интернета: ${e.message}")
            emit("Упс, связь с астралом прервалась. Проверь интернет, ${profile.name}.")
        } catch (e: Exception) {
            Log.e("GeminiRepository", "Сбой при генерации контента: ${e.message}")
            emit("Ох... Что-то пошло не так. ${e.message}")
        }
    }.flowOn(Dispatchers.IO)
}
