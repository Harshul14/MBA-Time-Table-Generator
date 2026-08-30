package com.example.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object RemoteScheduleFetcher {

    private const val TAG = "RemoteScheduleFetcher"

    const val DEFAULT_SHAREPOINT_URL =
        "https://svkmmumbai-my.sharepoint.com/:x:/g/personal/bakalavati_b_nmims_edu/IQDqK9gnw74qT40cAnuEIUJeAYOr5FyBF5jQLu2W7IkKfBM?e=iSC3jd"

    // Simple in-memory cookie store for managing session cookies across redirects
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val inMemoryCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val existing = cookieStore.getOrPut(url.host) { mutableListOf() }
            synchronized(existing) {
                // Replace or append
                for (newCookie in cookies) {
                    existing.removeAll { it.name == newCookie.name }
                    existing.add(newCookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostCookies = cookieStore[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            synchronized(hostCookies) {
                return hostCookies.filter { it.expiresAt > now }
            }
        }
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(inMemoryCookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Normalizes and converts various sharing links (SharePoint, OneDrive, Google Drive) into direct download links.
     */
    fun convertToDirectDownloadUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return DEFAULT_SHAREPOINT_URL

        // SharePoint or OneDrive business/personal links
        if (trimmed.contains("sharepoint.com", ignoreCase = true) ||
            trimmed.contains("1drv.ms", ignoreCase = true) ||
            trimmed.contains("onedrive.live.com", ignoreCase = true)
        ) {
            if (trimmed.contains("download=1", ignoreCase = true)) {
                return trimmed
            }
            return if (trimmed.contains("?")) {
                "$trimmed&download=1"
            } else {
                "$trimmed?download=1"
            }
        }

        // Google Drive sharing links
        if (trimmed.contains("drive.google.com", ignoreCase = true)) {
            val fileIdMatch = Regex("/d/([a-zA-Z0-9_-]+)").find(trimmed)
            if (fileIdMatch != null) {
                val fileId = fileIdMatch.groupValues[1]
                return "https://drive.google.com/uc?export=download&id=$fileId"
            }
        }

        return trimmed
    }

    /**
     * Validates whether binary header matches OOXML (.xlsx, ZIP) or OLE2 (.xls).
     */
    fun isValidExcelHeader(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < 4) return false
        // ZIP / XLSX magic: 0x50, 0x4B, 0x03, 0x04 ("PK..")
        val isZip = headerBytes[0] == 0x50.toByte() &&
                headerBytes[1] == 0x4B.toByte() &&
                headerBytes[2] == 0x03.toByte() &&
                headerBytes[3] == 0x04.toByte()

        // OLE2 / XLS magic: 0xD0, 0xCF, 0x11, 0xE0
        val isOle2 = headerBytes[0] == 0xD0.toByte() &&
                headerBytes[1] == 0xCF.toByte() &&
                headerBytes[2] == 0x11.toByte() &&
                headerBytes[3] == 0xE0.toByte()

        return isZip || isOle2
    }

    /**
     * Downloads the remote Excel workbook and stores it in internal app storage.
     */
    suspend fun downloadTimetableFile(context: Context, sourceUrl: String = DEFAULT_SHAREPOINT_URL): File =
        withContext(Dispatchers.IO) {
            val downloadUrl = convertToDirectDownloadUrl(sourceUrl)
            Log.d(TAG, "Initiating download from: $downloadUrl")

            val request = Request.Builder()
                .url(downloadUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header(
                    "Accept",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, application/vnd.ms-excel, application/octet-stream, */*"
                )
                .build()

            val response = try {
                okHttpClient.newCall(request).execute()
            } catch (e: Exception) {
                Log.e(TAG, "Network call failed", e)
                throw IllegalStateException("Failed to connect to timetable link. Please check your internet connection: ${e.localizedMessage}")
            }

            if (!response.isSuccessful) {
                response.close()
                throw IllegalStateException("SharePoint server returned status ${response.code}: ${response.message}")
            }

            val body = response.body
                ?: throw IllegalStateException("Received empty response body from SharePoint server")

            // Read the first bytes to verify if it's an actual Excel file or HTML/Login page
            val rawStream = body.byteStream()
            val headerBuffer = ByteArray(8)
            var bytesRead = 0
            while (bytesRead < 8) {
                val r = rawStream.read(headerBuffer, bytesRead, 8 - bytesRead)
                if (r == -1) break
                bytesRead += r
            }

            if (!isValidExcelHeader(headerBuffer)) {
                // Read remaining as text sample for diagnostic
                val restOfContent = ByteArray(1024)
                val restRead = rawStream.read(restOfContent)
                val sampleText = String(headerBuffer, 0, bytesRead) +
                        if (restRead > 0) String(restOfContent, 0, restRead) else ""

                Log.w(TAG, "Non-Excel response received: $sampleText")

                if (sampleText.contains("login.microsoftonline.com", ignoreCase = true) ||
                    sampleText.contains("Sign in to your account", ignoreCase = true) ||
                    sampleText.contains("auth", ignoreCase = true) ||
                    sampleText.contains("svkm", ignoreCase = true)
                ) {
                    throw IllegalStateException(
                        "The SharePoint link requires institutional Microsoft sign-in or permissions.\n\n" +
                                "Please upload the timetable Excel file (.xlsx) directly using 'Link / Upload' -> 'Upload Local Excel', or configure a public sharing link."
                    )
                } else {
                    throw IllegalStateException(
                        "The link did not return a valid Excel file (.xlsx / .xls). It returned an HTML/web preview page.\n\n" +
                                "Please download the .xlsx file on your device and tap 'Upload Local Excel (.xlsx)'."
                    )
                }
            }

            val localDir = File(context.filesDir, "excel_sheets")
            if (!localDir.exists()) {
                localDir.mkdirs()
            }

            val targetFile = File(localDir, "${System.currentTimeMillis()}_MBA_Batch_17_Trim_I_Live.xlsx")

            try {
                FileOutputStream(targetFile).use { output ->
                    // Write back the header bytes first
                    output.write(headerBuffer, 0, bytesRead)
                    // Stream the remainder
                    rawStream.copyTo(output)
                }
            } catch (e: Exception) {
                targetFile.delete()
                throw IllegalStateException("Error saving downloaded Excel sheet: ${e.localizedMessage}")
            }

            Log.d(TAG, "Downloaded file saved successfully at ${targetFile.absolutePath} (size: ${targetFile.length()} bytes)")
            return@withContext targetFile
        }
}
