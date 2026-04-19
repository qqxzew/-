package com.meemaw.assist.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.meemaw.assist.data.api.AgentAction
import com.meemaw.assist.data.api.ComposeAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentLoop {

    companion object {
        private const val TAG = "AgentLoop"
    }

    private val systemExecutor = SystemConfigExecutor()

    suspend fun execute(actions: List<AgentAction>, context: Context): String =
        withContext(Dispatchers.Main) {
            if (actions.isEmpty()) {
                Log.w(TAG, "AGENT mode returned with no actions")
                return@withContext "I couldn't carry out that request. Please try again."
            }

            var lastResult = "Done ✓"
            for (action in actions) {
                try {
                    lastResult = systemExecutor.execute(action, context)
                } catch (e: Exception) {
                    Log.e(TAG, "Action failed: ${action.type}", e)
                }
            }
            lastResult
        }

    fun openCompose(
        compose: ComposeAction,
        context: Context,
        availableContacts: List<PhoneContact> = emptyList()
    ): String {
        try {
            val appKey = compose.app.lowercase().trim()
            val resolvedContact = when (appKey) {
                "sms", "phone", "call" -> ContactResolver.resolveForCompose(
                    rawContact = compose.contact,
                    explicitBody = compose.body,
                    contacts = availableContacts,
                    hasPermission = ContactResolver.hasPermission(context),
                    policy = ComposeTargetPolicy.ANY_CONTACT_OR_NUMBER
                )
                else -> null
            }

            when (resolvedContact) {
                ContactResolution.MissingPermission -> {
                    return "Please allow contacts access so I can find people by name."
                }
                ContactResolution.NotFound -> {
                    return "I couldn't find that contact on your phone."
                }
                ContactResolution.Forbidden -> {
                    return if (appKey == "phone" || appKey == "call") {
                        "Calls are only enabled for the Test contact right now."
                    } else {
                        "I can't use that contact for this."
                    }
                }
                ContactResolution.MissingQuery -> {
                    return if (appKey == "sms") {
                        "Tell me who you want to text."
                    } else {
                        "Tell me who you want to call."
                    }
                }
                is ContactResolution.Success, null -> Unit
            }

            val intent = when (appKey) {
                "telegram" -> buildTelegramIntent(compose)
                "whatsapp" -> buildWhatsAppIntent(compose)
                "gmail", "email" -> buildEmailIntent(compose)
                "sms" -> {
                    val match = resolvedContact as ContactResolution.Success
                    buildSmsIntent(match.phoneNumber, compose.body ?: match.remainingText)
                }
                "phone", "call" -> {
                    val match = resolvedContact as ContactResolution.Success
                    buildCallIntent(match.phoneNumber, context).intent
                }
                else -> {
                    // Try to open any other app via SystemConfigExecutor's app resolver
                    return systemExecutor.openApp(context, compose.app, packageNameHint = null)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return when (appKey) {
                "sms" -> {
                    val match = resolvedContact as ContactResolution.Success
                    "Opening SMS for ${match.displayName} ✓"
                }
                "phone", "call" -> {
                    val match = resolvedContact as ContactResolution.Success
                    val callLaunch = buildCallIntent(match.phoneNumber, context)
                    if (callLaunch.isDirectCall) {
                        "Calling ${match.displayName} ✓"
                    } else {
                        "Opening the dialer for ${match.displayName} ✓"
                    }
                }
                "telegram" -> "Opening Telegram ✓"
                "whatsapp" -> "Opening WhatsApp ✓"
                "gmail", "email" -> "Opening email ✓"
                else -> "Opening ${compose.app} ✓"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open compose", e)
            return "I couldn't open that right now. Please try again."
        }
    }

    private fun buildTelegramIntent(c: ComposeAction): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("org.telegram.messenger")
            putExtra(Intent.EXTRA_TEXT, c.body ?: "")
        }
        return intent
    }

    private fun buildWhatsAppIntent(c: ComposeAction): Intent {
        val uri = Uri.parse("https://wa.me/?text=${Uri.encode(c.body ?: "")}")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
        }
    }

    private fun buildEmailIntent(c: ComposeAction): Intent {
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, "")
            putExtra(Intent.EXTRA_TEXT, c.body ?: "")
        }
    }

    private fun buildSmsIntent(phoneNumber: String, body: String?): Intent {
        val uri = Uri.parse("smsto:$phoneNumber")
        return Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body ?: "")
        }
    }

    private fun buildCallIntent(phoneNumber: String, context: Context): CallLaunch {
        val uri = Uri.parse("tel:$phoneNumber")
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return if (hasCallPermission) {
            CallLaunch(Intent(Intent.ACTION_CALL, uri), isDirectCall = true)
        } else {
            CallLaunch(Intent(Intent.ACTION_DIAL, uri), isDirectCall = false)
        }
    }

    private data class CallLaunch(
        val intent: Intent,
        val isDirectCall: Boolean
    )
}
