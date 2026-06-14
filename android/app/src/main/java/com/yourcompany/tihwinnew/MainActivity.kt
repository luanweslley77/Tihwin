package com.yourcompany.tihwinnew

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import me.jahnen.libaums.core.fs.FileSystem

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: GameAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var buttonPickISO: Button
    private lateinit var buttonConvert: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var editTextGameTitle: EditText
    private lateinit var radioGroupDiscType: RadioGroup
    private lateinit var textViewStatus: TextView

    private var selectedIsoUri: Uri? = null
    private var extractedGameId: String? = null
    private var massStorageDevice: UsbMassStorageDevice? = null
    private var rootDir: UsbFile? = null
    private var currentFileSystem: FileSystem? = null
    private val gameList = mutableListOf<GameEntry>()

    companion object {
        private const val ACTION_USB_PERMISSION = "com.yourcompany.tihwinnew.USB_PERMISSION"
        const val ACTION_UL_CFG_UPDATED = "com.yourcompany.tihwinnew.UL_CFG_UPDATED"
        private const val TAG = "MainActivityUsb"
    }

    private val ulCfgUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UL_CFG_UPDATED) {
                Log.d(TAG, "UL_CFG_UPDATED broadcast received. Reloading ul.cfg.")
                loadCfgFromUsb()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupViews()
        setupRecyclerView()
        setupClickListeners()
        setupUsbReceiver()

        // Register the receiver for UL_CFG_UPDATED
        LocalBroadcastManager.getInstance(this).registerReceiver(ulCfgUpdatedReceiver, IntentFilter(ACTION_UL_CFG_UPDATED))

        initiateUsbDeviceSetup(null)

    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(ulCfgUpdatedReceiver)
        massStorageDevice?.close()
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.recyclerViewGames)
        buttonPickISO = findViewById(R.id.buttonPickISO)
        buttonConvert = findViewById(R.id.buttonConvert)
        editTextGameTitle = findViewById(R.id.editTextGameTitle)
        radioGroupDiscType = findViewById(R.id.radioGroupDiscType)
        textViewStatus = findViewById(R.id.textViewStatus)
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = GameAdapter(gameList) { gameEntry ->
            removeGame(gameEntry)
        }
        recyclerView.adapter = adapter
    }

    private val isoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        Log.d(TAG, "isoPickerLauncher: Received URI: $uri")
        uri?.let { handleIsoFileSelected(it) }
    }

    private fun setupClickListeners() {
        buttonPickISO.setOnClickListener { isoPickerLauncher.launch("*/*") }
        buttonConvert.setOnClickListener { startIsoConversion() }
    }

    private fun setupUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun initiateUsbDeviceSetup(deviceFromIntent: UsbDevice?) {
        lifecycleScope.launch {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val potentialDevice = deviceFromIntent ?: UsbMassStorageDevice.getMassStorageDevices(this@MainActivity).firstOrNull()?.usbDevice

            if (potentialDevice == null) {
                clearUsbDeviceReferences()
                textViewStatus.text = "Nenhum dispositivo USB encontrado."
                return@launch
            }

            if (usbManager.hasPermission(potentialDevice)) {
                setupDevice(potentialDevice)
            } else {
                val intentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
                val permissionIntent = PendingIntent.getBroadcast(this@MainActivity, 0, Intent(ACTION_USB_PERMISSION), intentFlags)
                usbManager.requestPermission(potentialDevice, permissionIntent)
            }
        }
    }

    private suspend fun setupDevice(device: UsbDevice) {
        val massDevice = UsbDeviceManager.connect(this, device)
        if (massDevice != null) {
            try {
                val partition = massDevice.partitions.first()
                rootDir = partition.fileSystem.rootDirectory
                currentFileSystem = partition.fileSystem
                massStorageDevice = massDevice

                // Limpa arquivos temporários antes de carregar o resto, mas apenas se nenhuma conversão estiver em andamento
                if (!ConversionService.isConversionRunning) {
                    cleanupTemporaryFiles(rootDir!!)
                }

                withContext(Dispatchers.Main) {
                    textViewStatus.text = "USB: ${partition.fileSystem.volumeLabel ?: "Pronto"}"
                    updateButtonStates()
                    loadCfgFromUsb()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao configurar partição USB", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erro ao ler partição USB: ${e.message}", Toast.LENGTH_LONG).show()
                    clearUsbDeviceReferences()
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Falha ao inicializar o dispositivo USB.", Toast.LENGTH_LONG).show()
                clearUsbDeviceReferences()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { lifecycleScope.launch { setupDevice(it) } }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> device?.let { initiateUsbDeviceSetup(it) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device == massStorageDevice?.usbDevice) {
                        clearUsbDeviceReferences()
                    }
                }
            }
        }
    }

    private fun clearUsbDeviceReferences() {
        massStorageDevice?.close()
        massStorageDevice = null
        rootDir = null
        gameList.clear()
        adapter.notifyDataSetChanged()
        updateButtonStates()
        textViewStatus.text = "Dispositivo USB desconectado."
    }

    private fun handleIsoFileSelected(uri: Uri) {
        Log.d(TAG, "handleIsoFileSelected: URI received: $uri")
        selectedIsoUri = uri

        // Get all info in one go
        val isoInfo = IsoUtil.getIsoInfo(this, uri)
        extractedGameId = isoInfo.gameId
        Log.d(TAG, "handleIsoFileSelected: Extracted Game ID: $extractedGameId")

        // Use the display name, fallback to game ID if the name is null/blank
        val titleToDisplay = if (isoInfo.displayName.isNullOrBlank()) extractedGameId else isoInfo.displayName
        editTextGameTitle.setText(titleToDisplay ?: "")

        updateButtonStates()
    }

    private fun loadCfgFromUsb() {
        Log.d(TAG, "loadCfgFromUsb() called")
        if (rootDir == null || currentFileSystem == null) {
            Log.d(TAG, "loadCfgFromUsb(): rootDir or currentFileSystem is null. Returning.")
            return
        }
        lifecycleScope.launch {
            val entries = FileHelper.readUlCfg(rootDir!!, currentFileSystem!!)
            withContext(Dispatchers.Main) {
                adapter.updateData(entries)
                Log.d(TAG, "loadCfgFromUsb(): ul.cfg loaded successfully. Entries: ${entries.size}")
            }
        }
    }

    private fun removeGame(game: GameEntry) {
        AlertDialog.Builder(this)
            .setTitle("Remover Jogo")
            .setMessage("Tem certeza que deseja remover ${game.name} e seus arquivos associados?")
            .setPositiveButton("Sim") { _, _ ->
                lifecycleScope.launch {
                    val currentList = gameList.toMutableList()
                    val originalIndex = currentList.indexOf(game)
                    if (originalIndex == -1) return@launch

                    currentList.removeAt(originalIndex)

                    val saveSuccess = FileHelper.writeUlCfg(rootDir!!, currentFileSystem!!, currentList)

                    if (saveSuccess) {
                        val deleteSuccess = FileHelper.deleteGameFiles(rootDir!!, game)
                        withContext(Dispatchers.Main) {
                            adapter.updateData(currentList)
                            if (deleteSuccess) {
                                Toast.makeText(this@MainActivity, "${game.name} removido com sucesso.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "ul.cfg atualizado, mas falha ao remover alguns arquivos do jogo.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            // Se a escrita falhou, não precisamos re-adicionar, pois a UI não foi alterada
                            Toast.makeText(this@MainActivity, "Falha ao salvar ul.cfg. A remoção foi cancelada.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun startIsoConversion() {
        if (selectedIsoUri == null || rootDir == null || extractedGameId == null || currentFileSystem == null) return

        val gameTitle = editTextGameTitle.text.toString().trim()
        if (gameTitle.isEmpty()) {
            Toast.makeText(this, "Insira um título para o jogo.", Toast.LENGTH_SHORT).show()
            return
        }

        val discType = when (radioGroupDiscType.checkedRadioButtonId) {
            R.id.radioButtonCD -> "CD"
            R.id.radioButtonDVD -> "DVD"
            else -> { Toast.makeText(this, "Selecione o tipo de disco.", Toast.LENGTH_SHORT).show(); return }
        }

        AlertDialog.Builder(this)
            .setTitle("Confirmar Conversão")
            .setMessage("Converter e copiar $gameTitle?")
            .setPositiveButton("Sim") { _, _ ->
                startConversionService(selectedIsoUri!!, extractedGameId!!, gameTitle, discType)
            }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun startConversionService(isoUri: Uri, gameId: String, gameTitle: String, discType: String) {
        val intent = Intent(this, ConversionService::class.java).apply {
            putExtra("isoUri", isoUri.toString())
            putExtra("gameId", gameId)
            putExtra("discType", discType)
            putExtra("gameTitle", gameTitle)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "A conversão foi iniciada em segundo plano.", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonStates() {
        val usbReady = rootDir != null
        buttonConvert.isEnabled = usbReady && selectedIsoUri != null && extractedGameId != null
    }

    private suspend fun cleanupTemporaryFiles(directory: UsbFile) {
        FileHelper.cleanupTemporaryFiles(directory)
    }
}