package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun geminiMockPrefix_isDetected() {
    assertTrue(
      com.example.data.gemini.GeminiClient.isMockText(
        "⚠️ يرجى إضافة مفتاح API الخاص بـ Gemini"
      )
    )
    assertFalse(
      com.example.data.gemini.GeminiClient.isMockText("المتصل: مرحبا")
    )
    assertFalse(com.example.data.gemini.GeminiClient.isMockText(null))
  }
}
