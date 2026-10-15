package com.ai.agent.tools.categories

import android.content.Context
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.delay

/**
 * Contact tools category — extracted from ToolExecutor in v3.3.12.
 *
 * Tools: readContacts, searchContacts, getCalendarEvents, createCalendarEvent,
 * setAlarm, setTimer, sendSMS, callContact, getCallLog, sendEmail,
 * composeEmail, makePhoneCall, openDialer.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object ContactTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "readContacts" -> {
                try {
                    val cursor = context.contentResolver.query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                               android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                        null, null, "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                    )
                    val contacts = mutableListOf<String>()
                    cursor?.use {
                        while (it.moveToNext() && contacts.size < 50) {
                            val name = it.getString(0) ?: ""
                            val number = it.getString(1) ?: ""
                            contacts.add("$name: $number")
                        }
                    }
                    ToolExecutor.ToolResult(true, if (contacts.isEmpty()) "No contacts found" else "Contacts (${contacts.size}):\n${contacts.joinToString("\n")}")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Contacts error: ${e.message}")
                }
            }
            "searchContacts" -> {
                val query = call.args["name"] as? String ?: return ToolExecutor.ToolResult(false, "missing name")
                try {
                    val cursor = context.contentResolver.query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                               android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$query%"), null
                    )
                    val results = mutableListOf<String>()
                    cursor?.use {
                        while (it.moveToNext()) {
                            results.add("${it.getString(0)}: ${it.getString(1)}")
                        }
                    }
                    ToolExecutor.ToolResult(true, if (results.isEmpty()) "No contacts found for '$query'" else results.joinToString("\n"))
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Search error: ${e.message}")
                }
            }
            "getCalendarEvents" -> {
                try {
                    val now = System.currentTimeMillis()
                    val endOfDay = now + 86400000 * 7 // next 7 days
                    val cursor = context.contentResolver.query(
                        android.provider.CalendarContract.Events.CONTENT_URI,
                        arrayOf(android.provider.CalendarContract.Events.TITLE,
                               android.provider.CalendarContract.Events.DTSTART,
                               android.provider.CalendarContract.Events.DTEND,
                               android.provider.CalendarContract.Events.EVENT_LOCATION),
                        "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?",
                        arrayOf(now.toString(), endOfDay.toString()),
                        "${android.provider.CalendarContract.Events.DTSTART} ASC"
                    )
                    val events = mutableListOf<String>()
                    val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                    cursor?.use {
                        while (it.moveToNext() && events.size < 20) {
                            val title = it.getString(0) ?: ""
                            val start = it.getLong(1)
                            val end = it.getLong(2)
                            val location = it.getString(3) ?: ""
                            events.add("${sdf.format(java.util.Date(start))} - ${sdf.format(java.util.Date(end))}: $title${if (location.isNotEmpty()) " @ $location" else ""}")
                        }
                    }
                    ToolExecutor.ToolResult(true, if (events.isEmpty()) "No upcoming events" else "Upcoming events:\n${events.joinToString("\n")}")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Calendar error: ${e.message}")
                }
            }
            "createCalendarEvent" -> {
                val title = call.args["title"] as? String ?: return ToolExecutor.ToolResult(false, "missing title")
                val startTime = call.args["startTime"] as? String ?: return ToolExecutor.ToolResult(false, "missing startTime (epoch ms or yyyy-MM-dd HH:mm)")
                try {
                    val startMs = if (startTime.matches(Regex("\\d+"))) startTime.toLong()
                    else {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        sdf.parse(startTime)?.time ?: return ToolExecutor.ToolResult(false, "Invalid time format")
                    }
                    val durationMin = (call.args["durationMinutes"] as? Number)?.toInt() ?: 60
                    val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                        data = android.provider.CalendarContract.Events.CONTENT_URI
                        putExtra(android.provider.CalendarContract.Events.TITLE, title)
                        putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                        putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, startMs + durationMin * 60000)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutor.ToolResult(true, "Calendar event '$title' created")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Calendar error: ${e.message}")
                }
            }
            "setAlarm" -> {
                val hour = (call.args["hour"] as? Number)?.toInt() ?: return ToolExecutor.ToolResult(false, "missing hour")
                val minute = (call.args["minute"] as? Number)?.toInt() ?: return ToolExecutor.ToolResult(false, "missing minute")
                try {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutor.ToolResult(true, "Alarm set for $hour:$minute")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Alarm error: ${e.message}") }
            }
            "setTimer" -> {
                val seconds = (call.args["seconds"] as? Number)?.toInt() ?: return ToolExecutor.ToolResult(false, "missing seconds")
                try {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, call.args["message"] as? String ?: "Timer")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutor.ToolResult(true, "Timer set for ${seconds}s")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Timer error: ${e.message}") }
            }
            "sendSMS" -> {
                val number = call.args["number"] as? String ?: return ToolExecutor.ToolResult(false, "missing number")
                val message = call.args["message"] as? String ?: return ToolExecutor.ToolResult(false, "missing message")
                try {
                    val smsManager = android.telephony.SmsManager.getDefault()
                    smsManager.sendTextMessage(number, null, message, null, null)
                    ToolExecutor.ToolResult(true, "SMS sent to $number: $message")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "SMS error: ${e.message}")
                }
            }
            "callContact" -> {
                val number = call.args["number"] as? String ?: return ToolExecutor.ToolResult(false, "missing number")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                        data = android.net.Uri.parse("tel:$number")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutor.ToolResult(true, "Calling $number...")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Call error: ${e.message}")
                }
            }
            "getCallLog" -> {
                try {
                    val cursor = context.contentResolver.query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.CACHED_NAME,
                               android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.DATE,
                               android.provider.CallLog.Calls.DURATION),
                        null, null, "${android.provider.CallLog.Calls.DATE} DESC LIMIT 20"
                    )
                    val calls = mutableListOf<String>()
                    val sdf = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
                    cursor?.use {
                        while (it.moveToNext() && calls.size < 20) {
                            val number = it.getString(0) ?: ""
                            val name = it.getString(1) ?: "Unknown"
                            val type = when (it.getInt(2)) {
                                android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming"
                                android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                                android.provider.CallLog.Calls.MISSED_TYPE -> "Missed"
                                else -> "Unknown"
                            }
                            val date = sdf.format(java.util.Date(it.getLong(3)))
                            val duration = it.getString(4) ?: "0"
                            calls.add("$date $type: $name ($number) ${duration}s")
                        }
                    }
                    ToolExecutor.ToolResult(true, if (calls.isEmpty()) "No call history" else "Recent calls:\n${calls.joinToString("\n")}")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Call log error: ${e.message}")
                }
            }
            "sendEmail" -> {
                val to = call.args["to"] as? String ?: return ToolExecutor.ToolResult(false, "missing 'to' email")
                val subject = call.args["subject"] as? String ?: ""
                val body = call.args["body"] as? String ?: ""
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(to))
                        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                        putExtra(android.content.Intent.EXTRA_TEXT, body)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutor.ToolResult(true, "Email draft created for $to")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Email error: ${e.message}") }
            }
            "composeEmail" -> {
                val to = call.args["to"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing to (email address)")
                val subject = call.args["subject"] as? String ?: ""
                val body = call.args["body"] as? String ?: ""
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO)
                    intent.data = android.net.Uri.parse("mailto:")
                    intent.putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(to))
                    intent.putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                    intent.putExtra(android.content.Intent.EXTRA_TEXT, body)
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    intent.setPackage("com.google.android.gm")
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        intent.setPackage(null)
                        context.startActivity(intent)
                    }
                    delay(1500)
                    ToolExecutor.ToolResult(true, "Opened email composer: to=$to, subject=$subject")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "composeEmail($to) failed: ${e.message}")
                }
            }
            "makePhoneCall" -> {
                val number = call.args["number"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing number")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_CALL,
                        android.net.Uri.parse("tel:$number"))
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(1000)
                    ToolExecutor.ToolResult(true, "Calling $number...")
                } catch (e: SecurityException) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_DIAL,
                        android.net.Uri.parse("tel:$number"))
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(1000)
                    ToolExecutor.ToolResult(true, "Opened dialer with $number (CALL_PHONE permission not granted — tap call button)")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "makePhoneCall($number) failed: ${e.message}")
                }
            }
            "openDialer" -> {
                val number = call.args["number"] as? String ?: return ToolExecutor.ToolResult(false, "missing number")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                        data = android.net.Uri.parse("tel:$number")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutor.ToolResult(true, "Opened dialer with $number")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Dialer error: ${e.message}") }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown contact tool: ${call.name}")
        }
    }
}
