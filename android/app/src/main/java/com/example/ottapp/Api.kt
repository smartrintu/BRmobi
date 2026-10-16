package com.example.ottapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Movie(
    val id: String, val title: String, val description: String,
    val videoUrl: String, val thumbUrl: String,
    val category: String, val languages: String
)

object Api {
    const val BASE_URL = "https://shiny-memory-jwq6jpjr9pfpx46-3000.app.github.dev"
    // Subscription ke liye user ko jo contact info dikhana hai, yahan badal do:
    const val CONTACT_TEXT = "Subscription lene ke liye contact karo: WhatsApp +91XXXXXXXXXX"

    private fun open(path: String, method: String = "GET", body: String? = null): HttpURLConnection {
        val c = URL(BASE_URL + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000; c.readTimeout = 15000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c
    }

    suspend fun movies(): List<Movie> = withContext(Dispatchers.IO) {
        val arr = JSONArray(open("/api/movies").inputStream.bufferedReader().readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val thumb = o.optString("thumbPath")
            Movie(
                o.getString("id"), o.getString("title"), o.optString("description"),
                BASE_URL + o.getString("videoPath"),
                if (thumb.isEmpty()) "" else BASE_URL + thumb,
                o.optString("category").ifBlank { "General" },
                o.optString("languages")
            )
        }
    }

    /** Subscription ki expiry (millis). 0 = active nahi. */
    suspend fun subscription(device: String): Long = withContext(Dispatchers.IO) {
        val c = open("/api/sub?device=" + URLEncoder.encode(device, "UTF-8"))
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        if (o.optBoolean("active")) o.optLong("expiresAt") else 0L
    }

    suspend fun redeem(code: String, device: String): Long = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code.trim()).put("device", device).toString()
        val c = open("/api/redeem", "POST", body)
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
        val o = JSONObject(txt)
        if (!ok) throw Exception(o.optString("error", "Code galat hai"))
        o.getLong("expiresAt")
    }
}
