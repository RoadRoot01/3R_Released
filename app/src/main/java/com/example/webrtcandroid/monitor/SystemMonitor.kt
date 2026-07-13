package com.example.webrtcandroid.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

class SystemMonitor(private val context: Context) {

    private val TAG = "SystemMonitor"
    
    private var isMonitoring = false
    private var initialBatteryLevel: Int = -1
    private var initialBatteryCapacity: Int = -1
    private var startTimeMs: Long = 0

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0f
                
                val currentBatteryPct = level * 100 / scale.toFloat()
                
                if (initialBatteryLevel == -1) {
                    initialBatteryLevel = level
                }
                
                Log.d(TAG, "Battery Level: $currentBatteryPct%, Temperature: ${temp}°C")
            }
        }
    }

    fun startMonitoring() {
        if (isMonitoring) return
        
        Log.d(TAG, "Starting System Monitoring (Battery & Temperature)...")
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(batteryReceiver, filter)
        
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        // Battery capacity in mAh, if available
        initialBatteryCapacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        
        startTimeMs = System.currentTimeMillis()
        isMonitoring = true
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        
        context.unregisterReceiver(batteryReceiver)
        
        val endTimeMs = System.currentTimeMillis()
        val durationMs = endTimeMs - startTimeMs
        
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val finalBatteryCapacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        
        val durationHours = durationMs / (1000.0 * 60.0 * 60.0)
        
        if (initialBatteryCapacity != -1 && finalBatteryCapacity != -1) {
            // capacity is usually in micro-ampere-hours, need to divide by 1000 for mAh
            val consumedUah = initialBatteryCapacity - finalBatteryCapacity
            val consumedMah = consumedUah / 1000.0
            
            val consumptionRate = if (durationHours > 0) consumedMah / durationHours else 0.0
            Log.d(TAG, "Battery Consumed: $consumedMah mAh over ${durationHours} hours.")
            Log.d(TAG, "Battery Consumption Rate: $consumptionRate mAh/hour")
        } else {
            Log.w(TAG, "BATTERY_PROPERTY_CHARGE_COUNTER is not supported on this device.")
        }
        
        Log.d(TAG, "Stopped System Monitoring.")
        isMonitoring = false
    }
}
