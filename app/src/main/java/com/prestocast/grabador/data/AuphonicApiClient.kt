package com.prestocast.grabador.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ProductionStatus {
    data class Progress(val statusString: String, val percent: Int = 0) : ProductionStatus()
    data class Success(val downloadUrl: String, val uuid: String) : ProductionStatus()
    data class Error(val message: String) : ProductionStatus()
}

data class AuphonicPreset(
    val uuid: String,
    val name: String
)

class AuphonicApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun fetchPresets(apiKey: String): Result<List<AuphonicPreset>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.success(emptyList())

        try {
            val request = Request.Builder()
                .url("https://auphonic.com/api/presets.json")
                .addHeader("Authorization", getAuthHeader(apiKey))
                .get()
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(bodyStr)
                return@withContext Result.failure(Exception("Error HTTP ${response.code}: $errorMsg"))
            }

            val json = JSONObject(bodyStr)
            val dataArray = json.optJSONArray("data") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<AuphonicPreset>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.optJSONObject(i) ?: continue
                val uuid = obj.optString("uuid")
                val name = obj.optString("preset_name", obj.optString("name", "Preset $uuid"))
                if (uuid.isNotBlank()) {
                    list.add(AuphonicPreset(uuid = uuid, name = name))
                }
            }

            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startProductionAndPoll(
        apiKey: String,
        preset: String,
        audioFile: File,
        onStatusUpdate: (ProductionStatus) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onStatusUpdate(ProductionStatus.Progress("Subiendo archivo a Auphonic..."))

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("preset", preset)
                .addFormDataPart("title", audioFile.nameWithoutExtension)
                .addFormDataPart("action", "start")
                .addFormDataPart(
                    "input_file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/mpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("https://auphonic.com/api/simple/productions.json")
                .addHeader("Authorization", getAuthHeader(apiKey))
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(responseBodyStr)
                return@withContext Result.failure(Exception("Error HTTP ${response.code}: $errorMsg"))
            }

            val json = JSONObject(responseBodyStr)
            val data = json.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("Respuesta inválida de Auphonic"))

            val uuid = data.optString("uuid")
            var statusInt = data.optInt("status", -1)
            var statusString = data.optString("status_string", "Procesando")

            Log.d("AuphonicApiClient", "Producción creada. UUID: $uuid, Status initial: $statusInt ($statusString)")
            onStatusUpdate(ProductionStatus.Progress("Auphonic: $statusString"))

            var downloadUrl: String? = extractDownloadUrl(data)
            var consecutiveCheckFailures = 0

            // Polling hasta que esté completado (status 9 / "Done") o haya error (status 10 / 11)
            while (!isDone(statusInt, statusString) && !isError(statusInt, statusString)) {
                delay(3000)
                val pollResult = checkProductionStatus(apiKey, uuid)
                if (pollResult.isFailure) {
                    consecutiveCheckFailures++
                    Log.w("AuphonicApiClient", "Error temporal de verificación ($consecutiveCheckFailures/5)")
                    if (consecutiveCheckFailures >= 5) {
                        val exc = pollResult.exceptionOrNull() ?: Exception("Error en verificación de estado")
                        return@withContext Result.failure(exc)
                    }
                    continue
                }

                consecutiveCheckFailures = 0
                val statusData = pollResult.getOrNull() ?: JSONObject()
                statusInt = statusData.optInt("status", -1)
                statusString = statusData.optString("status_string", "Procesando")

                Log.d("AuphonicApiClient", "Poll status: $statusInt ($statusString)")
                onStatusUpdate(ProductionStatus.Progress("Auphonic: $statusString"))

                val newUrl = extractDownloadUrl(statusData)
                if (!newUrl.isNullOrEmpty()) {
                    downloadUrl = newUrl
                }
            }

            if (isError(statusInt, statusString)) {
                val errorMsg = data.optString("error_message", "Error durante el procesamiento en Auphonic ($statusString)")
                return@withContext Result.failure(Exception(errorMsg))
            }

            val finalUrl = downloadUrl
            if (finalUrl.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No se encontró el enlace de descarga en la producción"))
            }

            onStatusUpdate(ProductionStatus.Success(finalUrl, uuid))
            Result.success(finalUrl)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadProcessedAudio(
        apiKey: String,
        downloadUrl: String,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val absoluteUrl = formatAbsoluteUrl(downloadUrl)
            Log.d("AuphonicApiClient", "Descargando audio desde: $absoluteUrl")

            // Paso 1: Hacer petición a Auphonic sin seguir redirecciones automáticas
            val initialRequest = Request.Builder()
                .url(absoluteUrl)
                .addHeader("Authorization", getAuthHeader(apiKey))
                .get()
                .build()

            val initialResponse = noRedirectClient.newCall(initialRequest).execute()
            val responseCode = initialResponse.code

            val targetUrl: String
            val needsAuthHeader: Boolean

            if (responseCode in 300..399) {
                val redirectLocation = initialResponse.header("Location")
                initialResponse.close()

                if (redirectLocation.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Redirección $responseCode sin cabecera Location"))
                }

                targetUrl = formatAbsoluteUrl(redirectLocation)
                // Al redirigir a S3/CDN externo, NO enviamos cabecera Authorization (S3 la rechazaría con HTTP 400/403)
                needsAuthHeader = false
                Log.d("AuphonicApiClient", "Redirigido a S3/CDN: $targetUrl")
            } else if (initialResponse.isSuccessful) {
                // Si respondió 200 OK directamente, guardamos desde esta misma respuesta
                saveStreamToFile(initialResponse, outputFile, onProgress)
                return@withContext Result.success(outputFile)
            } else {
                val errBody = initialResponse.body?.string() ?: ""
                initialResponse.close()
                return@withContext Result.failure(Exception("Error al solicitar descarga (HTTP $responseCode): $errBody"))
            }

            // Paso 2: Descargar desde la URL final (S3 / CDN)
            val downloadRequestBuilder = Request.Builder().url(targetUrl).get()
            if (needsAuthHeader) {
                downloadRequestBuilder.addHeader("Authorization", getAuthHeader(apiKey))
            }

            val downloadResponse = client.newCall(downloadRequestBuilder.build()).execute()
            if (!downloadResponse.isSuccessful) {
                val errBody = downloadResponse.body?.string() ?: ""
                downloadResponse.close()
                return@withContext Result.failure(Exception("Error al descargar desde S3/CDN (HTTP ${downloadResponse.code}): $errBody"))
            }

            saveStreamToFile(downloadResponse, outputFile, onProgress)

            if (!outputFile.exists() || outputFile.length() == 0L) {
                return@withContext Result.failure(Exception("El archivo descargado está vacío"))
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun saveStreamToFile(
        response: okhttp3.Response,
        outputFile: File,
        onProgress: (Int) -> Unit
    ) {
        val body = response.body ?: throw IOException("Cuerpo de respuesta de descarga vacío")
        val contentLength = body.contentLength()

        body.byteStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (contentLength > 0) {
                        val percent = ((totalRead * 100) / contentLength).toInt()
                        onProgress(percent)
                    }
                }
                output.flush()
            }
        }
        response.close()
    }

    private fun checkProductionStatus(apiKey: String, uuid: String): Result<JSONObject> {
        val request = Request.Builder()
            .url("https://auphonic.com/api/production/$uuid.json")
            .addHeader("Authorization", getAuthHeader(apiKey))
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Result.failure(IOException("Error verificando estado: HTTP ${response.code}"))
            } else {
                val json = JSONObject(bodyStr)
                val data = json.optJSONObject("data") ?: JSONObject()
                Result.success(data)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getAuthHeader(apiKey: String): String {
        return if (apiKey.startsWith("Bearer ", ignoreCase = true)) {
            apiKey
        } else {
            "Bearer $apiKey"
        }
    }

    private fun formatAbsoluteUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("/") -> "https://auphonic.com$trimmed"
            else -> "https://auphonic.com/$trimmed"
        }
    }

    private fun extractDownloadUrl(data: JSONObject): String? {
        val outputFiles = data.optJSONArray("output_files") ?: return null
        for (i in 0 until outputFiles.length()) {
            val fileObj = outputFiles.optJSONObject(i) ?: continue
            val format = fileObj.optString("format", "")
            val ending = fileObj.optString("ending", "")
            if (format.contains("mp3", ignoreCase = true) || ending.equals("mp3", ignoreCase = true)) {
                val url = fileObj.optString("download_url", "")
                if (url.isNotEmpty()) return url
            }
        }
        // Fallback to first download_url if any
        if (outputFiles.length() > 0) {
            val firstObj = outputFiles.optJSONObject(0)
            val url = firstObj?.optString("download_url", null)
            if (!url.isNullOrEmpty()) return url
        }
        return null
    }

    private fun parseErrorMessage(jsonStr: String): String {
        return try {
            val json = JSONObject(jsonStr)
            json.optString("error_message", json.optString("error", jsonStr))
        } catch (e: Exception) {
            jsonStr
        }
    }

    private fun isDone(statusInt: Int, statusString: String): Boolean {
        return statusInt == STATUS_DONE || statusString.equals("Done", ignoreCase = true)
    }

    private fun isError(statusInt: Int, statusString: String): Boolean {
        return statusInt == STATUS_ERROR ||
                statusInt == 11 ||
                statusString.contains("Error", ignoreCase = true) ||
                statusString.contains("Failed", ignoreCase = true)
    }

    companion object {
        private const val STATUS_DONE = 9
        private const val STATUS_ERROR = 10
    }
}
