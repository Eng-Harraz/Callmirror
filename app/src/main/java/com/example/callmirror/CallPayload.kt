package com.example.callmirror

import org.json.JSONObject

data class CallPayload(
    val status: String, // "ringing", "ended", "ping", "reject"
    val number: String? = null,
    val name: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("status", status)
        if (number != null) json.put("number", number)
        if (name != null) json.put("name", name)
        json.put("timestamp", timestamp)
        return json.toString()
    }

    companion object {
        const val STATUS_RINGING = "ringing"
        const val STATUS_ENDED = "ended"
        const val STATUS_PING = "ping"
        const val STATUS_REJECT = "reject"

        fun fromJson(jsonStr: String): CallPayload? {
            return try {
                val json = JSONObject(jsonStr)
                CallPayload(
                    status = json.optString("status", STATUS_ENDED),
                    number = if (json.has("number")) json.getString("number") else null,
                    name = if (json.has("name")) json.getString("name") else null,
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
