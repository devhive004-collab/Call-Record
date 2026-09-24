package com.example.data.gemini

import android.util.Base64
import java.io.File
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val TAG = "GeminiClient"
    // gemini-3.5-flash does not exist (404 always). Use a real Flash model.
    private const val MODEL_NAME = "gemini-2.0-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    const val MOCK_PREFIX = "⚠️"

    private const val MAX_AUDIO_BYTES = 15 * 1024 * 1024
    private const val MAX_TRANSCRIPT_CHARS = 12000

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var apiKey: String = ""

    fun setApiKey(key: String) {
        apiKey = key.trim()
    }

    /**
     * Checks if the Gemini API key is configured and not the placeholder.
     */
    fun isKeyConfigured(): Boolean {
        val key = apiKey.ifEmpty { BuildConfig.GEMINI_API_KEY }
        return key.isNotEmpty() && key != "MY_GEMINI_API_KEY" && !key.contains("PLACEHOLDER")
    }

    /**
     * Generates a realistic transcript using Gemini based on call parameters (simulated transcription engine)
     *
     * NOTE: returns mock text prefixed with MOCK_PREFIX when no key is
     * configured or on API failure. Callers MUST NOT persist mock text as a
     * real transcript (see CallRecorderViewModel).
     */
    suspend fun generateTranscript(
        callerName: String,
        source: String,
        durationSec: Int,
        userNotes: String?,
        audioFilePath: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (!isKeyConfigured()) {
            return@withContext getOfflineMockTranscript(callerName, source, durationSec, userNotes)
        }

        val safeName = sanitizeForPrompt(callerName, 200)
        val safeSource = sanitizeForPrompt(source, 50)
        val safeNotes = sanitizeForPrompt(userNotes ?: "لا توجد ملاحظات إضافية", 1000)
        val prompt = if (audioFilePath != null && File(audioFilePath).exists()) {
            """
            قم بتفريغ المقطع الصوتي المرفق لهذه المكالمة الهاتفية إلى نص مكتوب باللغة العربية.
            المعلومات المتاحة عن المكالمة:
            - اسم الطرف الآخر: $safeName
            - منصة الاتصال: $safeSource
            - ملاحظات المستخدم أو سياق المكالمة: $safeNotes
            
            الشروط:
            1. استخدم تنسيق المتحدثين بوضوح مثل: "المتصل ($safeName): [نص الكلام]" و "أنت: [نص الكلام]".
            2. لا تذكر أي نصوص تمهيدية أو استهلالية خارج نص الحوار نفسه. ابدأ بكتابة تفريغ المكالمة مباشرة.
            """.trimIndent()
        } else {
            """
            اكتب نص حوار (ترجمة/تفريغ صوتي) مفصل واحترافي باللغة العربية لمكالمة هاتفية مسجلة بالكامل.
            المعلومات المتاحة عن المكالمة:
            - اسم الطرف الآخر: $safeName
            - منصة الاتصال: $safeSource (مثال: WhatsApp, Messenger, Cellular)
            - مدة المكالمة بالثواني: $durationSec ثانية
            - ملاحظات المستخدم أو سياق المكالمة: $safeNotes

            الشروط والتعليمات:
            1. يجب أن يبدأ الحوار بتحية وينتهي بختام منطقي ومناسب للمدة والسياق.
            2. استخدم تنسيق المتحدثين بوضوح مثل:
               "المتصل ($safeName): [نص الكلام]"
               "أنت: [نص الكلام]"
            3. اجعل الحوار يبدو واقعياً جداً واحترافياً يحتوي على تفاصيل دقيقة وتفاعلية تناسب مدة المكالمة ($durationSec ثانية).
            4. اكتب الحوار باللغة العربية الفصحى المبسطة أو اللهجة المصرية/الخليجية البيضاء المفهومة جداً.
            5. لا تذكر أي نصوص تمهيدية أو استهلالية خارج نص الحوار نفسه. ابدأ بكتابة تفريغ المكالمة مباشرة.
            """.trimIndent()
        }

        try {
            return@withContext callGeminiApi(prompt, audioFilePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating transcript via Gemini", e)
            return@withContext getOfflineMockTranscript(callerName, source, durationSec, userNotes)
        }
    }

    /**
     * Summarizes, gets sentiment, and extracts important points from a transcript.
     */
    suspend fun analyzeTranscript(transcript: String): AnalysisResult = withContext(Dispatchers.IO) {
        if (!isKeyConfigured()) {
            return@withContext getOfflineMockAnalysis(transcript)
        }
        // Never waste quota analyzing our own mock warning text.
        if (transcript.startsWith(MOCK_PREFIX)) {
            return@withContext getOfflineMockAnalysis(transcript)
        }

        val safeTranscript = sanitizeForPrompt(transcript, MAX_TRANSCRIPT_CHARS)
        val prompt = """
            قم بتحليل تفريغ المكالمة الهاتفية التالي بدقة واستخرج النتائج باللغة العربية.
            
            نص تفريغ المكالمة:
            \"\"\"
            $safeTranscript
            \"\"\"

            المطلوب هو إرجاع النتيجة بتنسيق JSON حصرياً وصالح للاستخدام البرمجي مباشرة (ولا تضع أي وسوم markdown مثل ```json أو أي نصوص قبل أو بعد الـ JSON).
            يجب أن يحتوي الـ JSON على المفاتيح التالية تماماً وباللغة العربية للقيم:
            {
               "summary": "ملخص شامل وذكي للمكالمة في سطرين أو ثلاثة",
               "sentiment": "اختر واحدة فقط من القيم التالية: 'إيجابي' أو 'متعادل' أو 'سلبي'",
               "importantPoints": "قائمة نقطية منسقة تفصل القرارات المتخذة أو النقاط الهامة والخطوات التالية"
            }
        """.trimIndent()

        try {
            val jsonResponse = callGeminiApi(prompt)
            // Parse response (clean markdown wrappers if the model ignored instructions)
            val cleanedJson = jsonResponse
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val jsonObject = JSONObject(cleanedJson)
            return@withContext AnalysisResult(
                summary = jsonObject.optString("summary", "لم يتم توليد الملخص بسب خطأ في الاستجابة."),
                sentiment = jsonObject.optString("sentiment", "متعادل"),
                importantPoints = jsonObject.optString("importantPoints", "• لم يتم استخراج نقاط هامة.")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing transcript via Gemini", e)
            return@withContext getOfflineMockAnalysis(transcript)
        }
    }

    private suspend fun callGeminiApi(prompt: String, audioFilePath: String? = null): String {
        val keyToUse = apiKey.ifEmpty { BuildConfig.GEMINI_API_KEY }
        if (keyToUse.isBlank() || keyToUse == "MY_GEMINI_API_KEY") {
            throw IllegalStateException("Gemini API key not configured")
        }
        // WARNING: client-side key is extractable from the APK. For production,
        // proxy through your server or use Firebase AI + App Check instead.
        // See metadata.json MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API.
        val url = "$BASE_URL?key=$keyToUse"

        val partArray = JSONArray()
        partArray.put(JSONObject().put("text", prompt))

        if (audioFilePath != null) {
            val file = File(audioFilePath)
            if (file.exists()) {
                if (file.length() > MAX_AUDIO_BYTES) {
                    throw IllegalArgumentException(
                        "Audio file too large (${file.length()} bytes > $MAX_AUDIO_BYTES). Trim or upload via Files API."
                    )
                }
                try {
                    val bytes = file.readBytes()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val inlineDataObj = JSONObject().apply {
                        put("mime_type", guessAudioMime(file))
                        put("data", base64)
                    }
                    partArray.put(JSONObject().put("inline_data", inlineDataObj))
                } catch (e: Exception) {
                    Log.e(TAG, "Error encoding audio file", e)
                    throw e
                }
            }
        }

        val contentObj = JSONObject().put("parts", partArray)
        val contentArray = JSONArray().put(contentObj)
        val requestBodyJson = JSONObject().put("contents", contentArray)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBodyJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        // Cancellable: OkHttp execute() blocks and ignores coroutine
        // cancellation (60s hang on VM clear). Use enqueue + continuation.
        val responseBodyString = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { try { call.cancel() } catch (_: Exception) { } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (!cont.isCompleted) cont.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                val errBody = try { it.body?.string() } catch (_: Exception) { null }
                                val serverMsg = try {
                                    errBody?.let { b -> JSONObject(b).optJSONObject("error")?.optString("message") }
                                } catch (_: Exception) { null }
                                cont.resumeWith(
                                    Result.failure(
                                        GeminiApiException(
                                            it.code,
                                            serverMsg ?: it.message,
                                            errBody
                                        )
                                    )
                                )
                                return
                            }
                            val bodyStr = it.body?.string()
                            if (bodyStr.isNullOrEmpty()) {
                                cont.resumeWith(Result.failure(IllegalStateException("Empty response body")))
                            } else {
                                cont.resumeWith(Result.success(bodyStr))
                            }
                        }
                    } catch (e: Exception) {
                        if (!cont.isCompleted) cont.resumeWith(Result.failure(e))
                    }
                }
            })
        }

        val jsonResponse = JSONObject(responseBodyString)
        val candidates = jsonResponse.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val errMsg = jsonResponse.optJSONObject("error")?.optString("message")
                ?: jsonResponse.optJSONObject("promptFeedback")?.optString("blockReason")
                ?: "No candidates (blocked or error): $responseBodyString".take(500)
            throw GeminiApiException(-1, errMsg, responseBodyString.take(2000))
        }
        val firstCandidate = candidates.optJSONObject(0)
            ?: throw GeminiApiException(-1, "Malformed candidates array", responseBodyString.take(2000))
        val content = firstCandidate.optJSONObject("content")
            ?: throw GeminiApiException(-1, "Missing content in candidate", responseBodyString.take(2000))
        val parts = content.optJSONArray("parts")
        if (parts == null || parts.length() == 0) {
            throw GeminiApiException(-1, "Missing parts in candidate", responseBodyString.take(2000))
        }
        return parts.optJSONObject(0)?.optString("text")
            ?: throw GeminiApiException(-1, "Missing text in part", responseBodyString.take(2000))
    }

    private fun guessAudioMime(file: File): String {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".3gp") -> "audio/3gpp"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".ogg") || name.endsWith(".oga") -> "audio/ogg"
            name.endsWith(".m4a") || name.endsWith(".mp4") -> "audio/mp4"
            else -> "audio/mp4"
        }
    }

    private fun sanitizeForPrompt(s: String, maxChars: Int): String {
        var out = s.replace("\"\"\"", "\"\"'") // break triple-quote injection
        if (out.length > maxChars) out = out.take(maxChars)
        return out
    }

    fun isMockText(text: String?): Boolean = text?.startsWith(MOCK_PREFIX) == true

    class GeminiApiException(val code: Int, message: String?, val body: String?) :
        Exception("Gemini API error $code: $message")

    // Fallbacks for Offline or API key missing
    private fun getOfflineMockTranscript(
        callerName: String,
        source: String,
        durationSec: Int,
        userNotes: String?
    ): String {
        return "⚠️ يرجى إضافة مفتاح API الخاص بـ Gemini في الإعدادات لتفعيل خاصية التفريغ الذكي للمكالمة الحقيقية."
    }

    private fun getOfflineMockAnalysis(transcript: String): AnalysisResult {
        return AnalysisResult(
            summary = "⚠️ يرجى إضافة مفتاح API الخاص بـ Gemini في الإعدادات لتفعيل التلخيص الذكي.",
            sentiment = "غير متاح",
            importantPoints = "• يرجى إضافة مفتاح API الخاص بـ Gemini في الإعدادات."
        )
    }

    data class AnalysisResult(
        val summary: String,
        val sentiment: String,
        val importantPoints: String
    )
}
