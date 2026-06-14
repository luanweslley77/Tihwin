package com.yourcompany.tihwinnew

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice

object UsbDeviceManager {
    private const val TAG = "UsbDeviceManager"
    private var device: UsbMassStorageDevice? = null

    fun getDevice(): UsbMassStorageDevice? {
        return device
    }

    suspend fun connect(context: Context, usbDevice: UsbDevice): UsbMassStorageDevice? {
        return withContext(Dispatchers.IO) {
            try {
                // Só inicializa se for um novo dispositivo ou se a conexão foi perdida
                if (device == null || device?.usbDevice != usbDevice) {
                    device?.close()
                    val massDevices = UsbMassStorageDevice.getMassStorageDevices(context)
                    val newDevice = massDevices.find { it.usbDevice == usbDevice }
                    newDevice?.init()
                    device = newDevice
                    Log.d(TAG, "Novo dispositivo USB conectado e inicializado.")
                }
                device
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao conectar o dispositivo USB", e)
                device = null
                null
            }
        }
    }

    fun disconnect() {
        try {
            device?.close()
            Log.d(TAG, "Dispositivo USB desconectado.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar o dispositivo USB", e)
        }
        device = null
    }
}
