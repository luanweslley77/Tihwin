package com.yourcompany.tihwinnew

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory

object FileHelper {
    private const val TAG = "FileHelper"
    private const val CFG_FILE_NAME = "ul.cfg"

    suspend fun readUlCfg(rootDir: UsbFile, fileSystem: FileSystem): List<GameEntry> {
        return withContext(Dispatchers.IO) {
            try {
                val cfgFile = rootDir.search(CFG_FILE_NAME)
                if (cfgFile != null && !cfgFile.isDirectory) {
                    val inputStream = UsbFileStreamFactory.createBufferedInputStream(cfgFile, fileSystem)
                    UlCfgManager.read(inputStream)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao ler $CFG_FILE_NAME", e)
                emptyList()
            }
        }
    }

    suspend fun writeUlCfg(rootDir: UsbFile, fileSystem: FileSystem, gameList: List<GameEntry>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cfgFile = rootDir.search(CFG_FILE_NAME) ?: rootDir.createFile(CFG_FILE_NAME)
                val outputStream = UsbFileStreamFactory.createBufferedOutputStream(cfgFile, fileSystem)
                UlCfgManager.write(outputStream, gameList)
                outputStream.flush()
                outputStream.close()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao escrever $CFG_FILE_NAME", e)
                false
            }
        }
    }

    suspend fun deleteGameFiles(rootDir: UsbFile, game: GameEntry): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val filesToDelete = findGameFiles(rootDir, game)
                if (filesToDelete.isEmpty()) {
                    Log.w(TAG, "Nenhum arquivo encontrado para o jogo ${game.id} para deletar.")
                    return@withContext true // Nada a deletar, considera sucesso.
                }

                var allFilesDeleted = true
                for (file in filesToDelete) {
                    try {
                        file.delete()
                        Log.d(TAG, "Arquivo deletado: ${file.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Falha ao deletar o arquivo: ${file.name}", e)
                        allFilesDeleted = false
                    }
                }
                allFilesDeleted
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao deletar arquivos do jogo para o ID: ${game.id}", e)
                false
            }
        }
    }

    private suspend fun findGameFiles(rootDir: UsbFile, game: GameEntry): List<UsbFile> {
        val filesToFind = mutableListOf<UsbFile>()
        if (game.parts > 1) {
            // Jogo com chunks (formato ul.XXX)
            val allFiles = rootDir.listFiles()
            val gameIdPattern = ".${game.id}."
            for (i in 0 until game.parts) {
                val partSuffix = String.format("%02d", i)
                val chunkFile = allFiles.firstOrNull { it.name.contains(gameIdPattern) && it.name.endsWith(partSuffix) }
                chunkFile?.let { filesToFind.add(it) }
            }
        } else {
            // Jogo em formato ISO único
            val searchDirs = listOfNotNull(rootDir.search("CD"), rootDir.search("DVD"))
            for (dir in searchDirs) {
                val isoFile = dir.listFiles().firstOrNull { it.name == "${game.id}.${game.name}.iso" }
                if (isoFile != null) {
                    filesToFind.add(isoFile)
                    break // This break is now outside the lambda
                }
            }
        }
        return filesToFind
    }

    suspend fun cleanupTemporaryFiles(directory: UsbFile) {
        withContext(Dispatchers.IO) {
            try {
                val files = directory.listFiles()
                for (file in files) {
                    if (file.isDirectory) {
                        cleanupTemporaryFiles(file) // Chamada recursiva
                    } else if (file.name.endsWith(".tmp")) {
                        try {
                            file.delete()
                            Log.d(TAG, "Arquivo temporário removido: ${file.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha ao remover arquivo temporário: ${file.name}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao listar arquivos para limpeza: ${directory.name}", e)
            }
        }
    }
}
