package com.ai.agent.tools.categories

import android.content.Context
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.withContext

/**
 * Location tools category — extracted from ToolExecutor in v3.3.12.
 *
 * Tools: getCurrentLocation, openMaps, getWeather.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object LocationTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "getCurrentLocation" -> {
                try {
                    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    val providers = lm.getProviders(true)
                    var location: android.location.Location? = null
                    for (provider in providers) {
                        try {
                            @Suppress("MissingPermission")
                            val loc = lm.getLastKnownLocation(provider)
                            if (loc != null && (location == null || loc.accuracy < location!!.accuracy)) {
                                location = loc
                            }
                        } catch (_: Exception) {}
                    }
                    if (location != null) {
                        ToolExecutor.ToolResult(true, "Location: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")
                    } else {
                        ToolExecutor.ToolResult(false, "Location not available. Enable GPS.")
                    }
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Location error: ${e.message}")
                }
            }
            "openMaps" -> {
                val query = call.args["query"] as? String ?: return ToolExecutor.ToolResult(false, "missing query")
                try {
                    // Force Google Maps — no app picker
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("google.maps:q=$query")).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        ToolExecutor.ToolResult(true, "Google Maps opened for: $query")
                    } else {
                        // Fallback: open in browser
                        val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://maps.google.com/maps?q=$query")).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(browserIntent)
                        ToolExecutor.ToolResult(true, "Maps opened in browser for: $query (Google Maps app not installed)")
                    }
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Maps error: ${e.message}")
                }
            }
            // v2.3.3: getWeather — uses GPS location + free Open-Meteo API (no key needed, works in India)
            "getWeather" -> {
                try {
                    // Step 1: Get GPS location
                    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    val providers = lm.getProviders(true)
                    var location: android.location.Location? = null
                    for (provider in providers) {
                        try {
                            @Suppress("MissingPermission")
                            val loc = lm.getLastKnownLocation(provider)
                            if (loc != null && (location == null || loc.accuracy < location!!.accuracy)) {
                                location = loc
                            }
                        } catch (_: Exception) {}
                    }
                    if (location == null) {
                        return ToolExecutor.ToolResult(false, "GPS location not available. Enable GPS and try again, or specify a city: getWeather(city=\"Bangalore\")")
                    }
                    val lat = location.latitude
                    val lon = location.longitude

                    // Step 2: Call free Open-Meteo API (no API key, works globally including India)
                    val (weatherBody, weatherCode) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                            "weather_code,wind_speed_10m,precipitation" +
                            "&daily=temperature_2m_max,temperature_2m_min,weather_code" +
                            "&timezone=auto&forecast_days=3"
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val request = okhttp3.Request.Builder().url(url).build()
                        val response = client.newCall(request).execute()
                        (response.body?.string() ?: "") to response.code
                    }

                    if (weatherCode != 200 || weatherBody.isEmpty()) {
                        return ToolExecutor.ToolResult(false, "Weather API error: HTTP $weatherCode")
                    }

                    // Step 3: Parse JSON response
                    val json = org.json.JSONObject(weatherBody)
                    val current = json.optJSONObject("current")
                    val daily = json.optJSONObject("daily")

                    val temp = current?.optDouble("temperature_2m", 0.0) ?: 0.0
                    val humidity = current?.optInt("relative_humidity_2m", 0) ?: 0
                    val feelsLike = current?.optDouble("apparent_temperature", temp) ?: temp
                    val windSpeed = current?.optDouble("wind_speed_10m", 0.0) ?: 0.0
                    val precip = current?.optDouble("precipitation", 0.0) ?: 0.0
                    val wmoCode = current?.optInt("weather_code", -1) ?: -1
                    val timezone = json.optString("timezone", "unknown")

                    // WMO weather description
                    val weatherDesc = when (wmoCode) {
                        0 -> "Clear sky"
                        1 -> "Mainly clear"
                        2 -> "Partly cloudy"
                        3 -> "Overcast"
                        45 -> "Foggy"
                        48 -> "Rime fog"
                        51 -> "Light drizzle"
                        53 -> "Moderate drizzle"
                        55 -> "Dense drizzle"
                        61 -> "Slight rain"
                        63 -> "Moderate rain"
                        65 -> "Heavy rain"
                        71 -> "Slight snow"
                        73 -> "Moderate snow"
                        75 -> "Heavy snow"
                        80 -> "Rain showers"
                        81 -> "Heavy rain showers"
                        82 -> "Violent rain showers"
                        95 -> "Thunderstorm"
                        96 -> "Thunderstorm with hail"
                        else -> "Unknown ($wmoCode)"
                    }

                    // Build 3-day forecast
                    val forecast = StringBuilder()
                    if (daily != null) {
                        val maxTemps = daily.optJSONArray("temperature_2m_max")
                        val minTemps = daily.optJSONArray("temperature_2m_min")
                        val dates = daily.optJSONArray("time")
                        val codes = daily.optJSONArray("weather_code")
                        val days = minOf(3, maxTemps?.length() ?: 0)
                        for (i in 0 until days) {
                            val date = dates?.optString(i, "") ?: ""
                            val max = maxTemps?.optDouble(i, 0.0) ?: 0.0
                            val min = minTemps?.optDouble(i, 0.0) ?: 0.0
                            val dc = codes?.optInt(i, -1) ?: -1
                            val desc = when (dc) {
                                0 -> "Clear"
                                1 -> "Mainly clear"
                                2 -> "Partly cloudy"
                                3 -> "Cloudy"
                                51 -> "Drizzle"
                                61 -> "Rain"
                                63 -> "Rain"
                                80 -> "Showers"
                                95 -> "Thunderstorm"
                                else -> "—"
                            }
                            forecast.append("  $date: $min°C - $max°C ($desc)\n")
                        }
                    }

                    val result = buildString {
                        append("Current Weather ($timezone)\n")
                        // v2.3.3: Use Locale.US to force dot decimal separator (some Indian locales use comma → parse errors)
                        append("Temperature: ${String.format(java.util.Locale.US, "%.1f", temp)}°C (feels like ${String.format(java.util.Locale.US, "%.1f", feelsLike)}°C)\n")
                        append("Condition: $weatherDesc\n")
                        append("Humidity: $humidity%\n")
                        append("Wind: ${String.format(java.util.Locale.US, "%.1f", windSpeed)} km/h\n")
                        append("Precipitation: ${String.format(java.util.Locale.US, "%.1f", precip)} mm\n")
                        append("Location: ${String.format(java.util.Locale.US, "%.4f", lat)}, ${String.format(java.util.Locale.US, "%.4f", lon)}\n")
                        if (forecast.isNotEmpty()) {
                            append("\n3-day forecast:\n")
                            append(forecast.toString().trimEnd())
                        }
                    }

                    ToolExecutor.ToolResult(true, result)
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Weather error: ${e.message}")
                }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown location tool: ${call.name}")
        }
    }
}
