package com.example.ottapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class Movie(val id: String, val title: String, val description: String,
                 val videoUrl: String, val thumbUrl: String)

object Api {
    const val BASE_URL = "https://shiny-memory-jwq6jpjr9pfpx46-3000.app.github.dev"

    suspend fun movies(): List<Movie> = withContext(Dispatchers.IO) {
        val c = URL("$BASE_URL/api/movies").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val thumb = o.optString("thumbPath")
            Movie(o.getString("id"), o.getString("title"), o.optString("description"),
                BASE_URL + o.getString("videoPath"), if (thumb.isEmpty()) "" else BASE_URL + thumb)
        }
    }
}
