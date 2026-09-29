package org.itantra.app.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import java.util.UUID

object BluetoothAccess {
    val RFCOMM_UUID: UUID = UUID.fromString("86b64920-28b8-4db5-8c15-2c93d279bc61")
    val BLE_UUID: UUID = UUID.fromString("d691c0b2-18d4-42db-93e8-a287ac6aa6f4")
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @android.annotation.SuppressLint("MissingPermission")
    fun requireAdapter(context: Context, discovery: Boolean = true): BluetoothAdapter {
        val required = if (Build.VERSION.SDK_INT >= 31) permissions().toList()
            else if (discovery) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyList()
        check(required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            "Grant Nearby devices permission (Precise location on Android 11 and earlier)."
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        check(adapter != null) { "Bluetooth is unavailable on this device" }
        check(adapter.isEnabled) { "Enable Bluetooth in Android settings, then try again." }
        if (discovery && Build.VERSION.SDK_INT <= 30) {
            val location = if (Build.VERSION.SDK_INT >= 28) context.getSystemService(LocationManager::class.java).isLocationEnabled
                else Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
            check(location) { "Android requires Location mode for Bluetooth discovery. No coordinates are collected." }
        }
        return adapter
    }
}
