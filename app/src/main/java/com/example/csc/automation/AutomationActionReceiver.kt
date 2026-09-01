package com.example.csc.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.csc.capture.MediaProjectionCaptureService

class AutomationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ScreenAutomationService.ACTION_STOP) {
            AutomationConfig.setEnabled(context, false)
            MediaProjectionCaptureService.stop(context)
            ScreenAutomationService.requestImmediateRefresh()
        }
    }
}
