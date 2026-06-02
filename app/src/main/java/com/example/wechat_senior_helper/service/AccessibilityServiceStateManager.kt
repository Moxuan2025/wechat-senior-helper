package com.example.wechat_senior_helper.service

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Accessibility Service State Manager
 * Provides a single source of truth for accessibility service status
 * Separates system-level enablement from process-level instance existence
 * 
 * @author moxuan
 */
object AccessibilityServiceStateManager {

    private const val TAG = "AccessibilityStateManager"

    /**
     * Service connection status enum
     */
    enum class ServiceStatus {
        DISABLED,      // System accessibility not enabled
        ENABLED,       // System enabled but not connected
        CONNECTED,     // Service is running and connected
        DISCONNECTED   // Was connected but got interrupted
    }

    /**
     * State flow holding the current service status
     * UI should observe this instead of checking instance directly
     */
    private val _serviceStatus = MutableStateFlow(ServiceStatus.DISABLED)
    val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus.asStateFlow()

    /**
     * Legacy instance reference (for backward compatibility during migration)
     * Should NOT be used as primary state source
     */
    @Deprecated("Use serviceStatus StateFlow instead")
    var instance: WeChatAssistAccessibilityService? = null
        private set

    /**
     * Update service status - called by service lifecycle methods
     */
    fun updateStatus(status: ServiceStatus) {
        Log.d(TAG, "Service status changed: ${status.name}")
        _serviceStatus.value = status
    }

    /**
     * Check if accessibility service is enabled in system settings
     * This is the authoritative check for system-level enablement
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val accessibilityEnabled = try {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read accessibility enabled setting", e)
            0
        }

        if (accessibilityEnabled == 1) {
            val serviceNames = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            
            val isServiceEnabled = serviceNames?.contains(
                "${context.packageName}/com.example.wechat_senior_helper.service.WeChatAssistAccessibilityService"
            ) ?: false
            
            Log.d(TAG, "System accessibility check: enabled=$accessibilityEnabled, serviceEnabled=$isServiceEnabled")
            return isServiceEnabled
        }

        Log.d(TAG, "System accessibility check: disabled")
        return false
    }

    /**
     * Set instance reference (called by service on connect)
     */
    internal fun setInstance(instance: WeChatAssistAccessibilityService?) {
        this.instance = instance
    }

    /**
     * Refresh status based on system settings and instance state
     * Should be called when returning from settings or on app resume
     */
    fun refreshStatus(context: Context) {
        val systemEnabled = isAccessibilityEnabled(context)
        val instanceExists = instance != null
        
        val newStatus = when {
            !systemEnabled -> ServiceStatus.DISABLED
            systemEnabled && instanceExists -> ServiceStatus.CONNECTED
            systemEnabled && !instanceExists -> ServiceStatus.ENABLED
            else -> ServiceStatus.DISABLED
        }
        
        updateStatus(newStatus)
    }
}
