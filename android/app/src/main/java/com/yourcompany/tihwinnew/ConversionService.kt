package com.yourcompany.tihwinnew

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import java.io.InputStream

class ConversionService : Service() {

    companion object {
        @Volatile
        var isConversionRunning = false
        const val ACTION_CANCEL_CONVERSION = "com.yourcompany.tihwinnew.ACTION_CANCEL_CONVERSION"
    }

    @Volatile
    private var isCancelled = false

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val CHANNEL_ID = "ConversionChannel"
    private val NOTIFICATION_ID = 1

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_CONVERSION) {
                Log.d("ConversionService", "Cancel action received.")
                isCancelled = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL_CONVERSION), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cancelReceiver, IntentFilter(ACTION_CANCEL_CONVERSION))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(cancelReceiver)
        isConversionRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isCancelled = false
        isConversionRunning = true
        createNotificationChannel()
        val notification = createNotification("Iniciando conversão...", 0)
        startForeground(NOTIFICATION_ID, notification)

        coroutineScope.launch {
            val isoUriString = intent?.getStringExtra("isoUri")
            val gameId = intent?.getStringExtra("gameId")
            val gameTitle = intent?.getStringExtra("gameTitle")
            val discType = intent?.getStringExtra("discType")

            if (isoUriString == null || gameId == null || gameTitle == null || discType == null) {
                stopSelf()
                return@launch
            }

            val isoUri = Uri.parse(isoUriString)
            val ulCfgWasUpdated = convert(isoUri, gameId, gameTitle, discType) { progress ->
                updateNotification("Convertendo...", progress)
            }

            if (ulCfgWasUpdated) {
                LocalBroadcastManager.getInstance(this@ConversionService).sendBroadcast(Intent(MainActivity.ACTION_UL_CFG_UPDATED))
            }

            isConversionRunning = false
            stopForeground(true)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * Handles the conversion logic.
     * @return true if ul.cfg was modified and the UI needs a refresh, false otherwise.
     */
    private suspend fun convert(isoUri: Uri, gameId: String, gameTitle: String, discType: String, updateProgress: (Int) -> Unit): Boolean {
        val massDevice = UsbDeviceManager.getDevice()
        if (massDevice == null) {
            Log.e("ConversionService", "USB device not available for conversion.")
            return false
        }
        val fileSystem = massDevice.partitions.first().fileSystem
        val rootDir = fileSystem.rootDirectory
        val tempFiles = mutableListOf<UsbFile>()

        try {
            val totalSize = contentResolver.openFileDescriptor(isoUri, "r")?.use { it.statSize } ?: 0
            if (totalSize == 0L) {
                Log.e("ConversionService", "Could not determine ISO size.")
                return false
            }

            val fat32MaxFileSize = 4_294_967_295L
            var bytesCopiedSoFar = 0L

            contentResolver.openInputStream(isoUri)?.use { inputStream ->
                if (totalSize <= fat32MaxFileSize) {
                    // --- PATH A: Simple copy for ISOs < 4GB ---
                    Log.d("ConversionService", "Starting simple copy for ISO < 4GB.")
                    val targetDir = rootDir.search(discType) ?: rootDir.createDirectory(discType)
                    val finalFileName = "$gameId.$gameTitle.iso"
                    val tempFile = targetDir.createFile("$finalFileName.tmp")
                    tempFiles.add(tempFile)

                    val outputStream = UsbFileStreamFactory.createBufferedOutputStream(tempFile, fileSystem)
                    outputStream.use { out ->
                        streamCopy(inputStream, out, totalSize, totalSize, 0L, updateProgress)
                    }

                } else {
                    // --- PATH B: Chunk conversion for ISOs > 4GB ---
                    Log.d("ConversionService", "Starting chunk conversion for ISO > 4GB.")
                    val crc32 = IsoUtil.oplCrc32(gameTitle)
                    Log.d("ConversionService", "Calculated OPL CRC32 for title '$gameTitle': ${String.format("%08X", crc32)}")

                    val partSize = 1024L * 1024L * 1024L // 1 GB
                    val numParts = ((totalSize + partSize - 1) / partSize).toInt()
                    val baseFileName = "ul.${String.format("%08X", crc32)}.$gameId"

                    for (i in 0 until numParts) {
                        if (isCancelled) break
                        val tempPartFile = rootDir.createFile("$baseFileName.${String.format("%02d", i)}.tmp")
                        tempFiles.add(tempPartFile)

                        val bytesToCopyForThisPart = if (i == numParts - 1) {
                            totalSize - bytesCopiedSoFar
                        } else {
                            partSize
                        }

                        val outputStream = UsbFileStreamFactory.createBufferedOutputStream(tempPartFile, fileSystem)
                        outputStream.use { out ->
                           val copied = streamCopy(inputStream, out, bytesToCopyForThisPart, totalSize, bytesCopiedSoFar, updateProgress)
                           bytesCopiedSoFar += copied
                        }
                    }

                    if (!isCancelled) {
                        updateUlCfg(gameTitle, gameId, discType, numParts)
                        return@use true // Signal that ul.cfg was updated
                    }
                }
            } ?: return false // End of openInputStream.use

            if (isCancelled) {
                throw InterruptedException("Conversion was cancelled by the user.")
            }

            // Commit the changes by renaming the temporary files
            for (tempFile in tempFiles) {
                val finalName = tempFile.name.removeSuffix(".tmp")
                tempFile.name = finalName
            }
            tempFiles.clear()

        } catch (e: Exception) {
            Log.e("ConversionService", "Conversion failed, rolling back.", e)
            tempFiles.forEach { try { it.delete() } catch (ex: Exception) { /* ignore */ } }
            return false
        }

        return false // Default case: ul.cfg was not updated
    }

    private fun streamCopy(
        inputStream: InputStream,
        outputStream: java.io.OutputStream,
        bytesToCopy: Long,
        totalIsoSize: Long,
        bytesCopiedSoFar: Long,
        updateProgress: (Int) -> Unit
    ): Long {
        var bytesCopiedForThisPart = 0L
        try {
            val buffer = ByteArray(16 * 1024) // 16KB buffer
            var bytesRead: Int

            while (bytesCopiedForThisPart < bytesToCopy) {
                if (isCancelled) {
                    Log.d("ConversionService", "Stream copy cancelled.")
                    break
                }

                val remaining = bytesToCopy - bytesCopiedForThisPart
                val toRead = if (buffer.size.toLong() > remaining) remaining.toInt() else buffer.size
                
                bytesRead = inputStream.read(buffer, 0, toRead)
                if (bytesRead == -1) break // End of source stream

                outputStream.write(buffer, 0, bytesRead)
                bytesCopiedForThisPart += bytesRead

                val currentTotalBytesCopied = bytesCopiedSoFar + bytesCopiedForThisPart
                val overallProgress = (currentTotalBytesCopied * 100 / totalIsoSize).toInt()
                updateProgress(overallProgress)
            }
        } finally {
            // We don't close the outputStream here, it's managed by the caller
        }
        return bytesCopiedForThisPart
    }

    private suspend fun updateUlCfg(gameTitle: String, gameId: String, discType: String, partsCount: Int) {
        val massDevice = UsbDeviceManager.getDevice() ?: return
        val fileSystem = massDevice.partitions.first().fileSystem
        val rootDir = fileSystem.rootDirectory

        try {
            val entries = FileHelper.readUlCfg(rootDir, fileSystem).toMutableList()

            val newGame = GameEntry(name = gameTitle, id = gameId, mediaType = discType, size = 0, parts = partsCount)
            val existingEntryIndex = entries.indexOfFirst { it.id == newGame.id }
            if (existingEntryIndex != -1) {
                entries[existingEntryIndex] = newGame
            } else {
                entries.add(newGame)
            }

            FileHelper.writeUlCfg(rootDir, fileSystem, entries)
            Log.d("ConversionService", "ul.cfg updated successfully for $gameTitle")
        } catch (e: Exception) {
            Log.e("ConversionService", "Failed to update ul.cfg", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Conversão",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String, progress: Int): Notification {
        val cancelIntent = Intent(this, ConversionService::class.java).apply {
            action = ACTION_CANCEL_CONVERSION
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_CANCEL_CONVERSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Conversão em Andamento")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Substitua pelo seu ícone
            .setProgress(100, progress, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelPendingIntent)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val notification = createNotification(text, progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
