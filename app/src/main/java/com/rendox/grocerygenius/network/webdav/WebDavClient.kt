package com.rendox.grocerygenius.network.webdav

import com.rendox.grocerygenius.model.WebDavSyncConfig
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncException
import com.rendox.grocerygenius.network.di.Dispatcher
import com.rendox.grocerygenius.network.di.GroceryGeniusDispatchers
import com.rendox.grocerygenius.sync.webdav.RemoteSnapshotFile
import com.rendox.grocerygenius.sync.webdav.RemoteSnapshotStore
import com.rendox.grocerygenius.sync.webdav.UploadResult
import java.io.IOException
import java.net.UnknownServiceException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class WebDavHttpClient

/**
 * A minimal WebDAV client that stores the sync snapshot in the
 * [REMOTE_FOLDER_NAME]/[REMOTE_FILE_NAME] file inside the configured folder.
 * Works with Nextcloud, ownCloud and other WebDAV servers.
 *
 * The provided [OkHttpClient] must not follow redirects, because OkHttp turns redirected
 * PUT requests into GET requests, which would silently drop the upload.
 */
class WebDavClient @Inject constructor(
    @WebDavHttpClient private val httpClient: OkHttpClient,
    @Dispatcher(GroceryGeniusDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    /**
     * Checks that the folder exists and that the credentials are valid, and creates the app's folder.
     * @throws WebDavSyncException if the server can't be used for sync
     */
    suspend fun checkConnection(config: WebDavSyncConfig) {
        val request = Request.Builder()
            .url(config.rootUrl())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
        execute(config, request) { response ->
            when (response.code) {
                207 -> Unit
                200, 405 -> throw WebDavSyncException(WebDavSyncError.NOT_A_WEBDAV_FOLDER)
                else -> throw response.toException()
            }
        }
        // also checks that the user has write access
        createFolder(config)
    }

    suspend fun download(config: WebDavSyncConfig): RemoteSnapshotFile? {
        val request = Request.Builder().url(config.fileUrl()).get()
        return execute(config, request) { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> RemoteSnapshotFile(
                    content = response.body?.string().orEmpty(),
                    etag = response.etag()
                )

                else -> throw response.toException()
            }
        }
    }

    suspend fun upload(
        config: WebDavSyncConfig,
        content: String,
        replacing: RemoteSnapshotFile?
    ): UploadResult {
        put(config, content, replacing)?.let { return it }
        // the app's folder doesn't exist yet
        createFolder(config)
        return put(config, content, replacing) ?: throw WebDavSyncException(WebDavSyncError.NOT_FOUND)
    }

    fun remoteSnapshotStore(config: WebDavSyncConfig): RemoteSnapshotStore = object : RemoteSnapshotStore {
        override suspend fun download() = this@WebDavClient.download(config)

        override suspend fun upload(
            content: String,
            replacing: RemoteSnapshotFile?
        ) = this@WebDavClient.upload(config, content, replacing)
    }

    /**
     * Returns null if the parent folder doesn't exist.
     */
    private suspend fun put(
        config: WebDavSyncConfig,
        content: String,
        replacing: RemoteSnapshotFile?
    ): UploadResult? {
        val request = Request.Builder()
            .url(config.fileUrl())
            .put(content.toRequestBody(JSON_MEDIA_TYPE))
        val etag = replacing?.etag
        when {
            replacing == null -> request.header("If-None-Match", "*")
            // weak ETags can't be used for conditional uploads
            etag != null && !etag.startsWith("W/") -> request.header("If-Match", etag)
        }
        return execute(config, request) { response ->
            when (response.code) {
                in 200..299 -> UploadResult.UPLOADED
                412 -> UploadResult.PRECONDITION_FAILED
                404, 409 -> null
                else -> throw response.toException()
            }
        }
    }

    private suspend fun createFolder(config: WebDavSyncConfig) {
        val request = Request.Builder()
            .url(config.folderUrl())
            .method("MKCOL", null)
        execute(config, request) { response ->
            when (response.code) {
                // 405 means that the folder already exists
                201, 405 -> Unit
                // the configured folder doesn't exist
                409 -> throw WebDavSyncException(WebDavSyncError.NOT_FOUND)
                else -> throw response.toException()
            }
        }
    }

    private suspend fun <T> execute(
        config: WebDavSyncConfig,
        requestBuilder: Request.Builder,
        handleResponse: (Response) -> T
    ): T {
        val request = requestBuilder
            .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))
            .build()
        return try {
            runInterruptible(ioDispatcher) {
                httpClient.newCall(request).execute().use(handleResponse)
            }
        } catch (exception: IOException) {
            currentCoroutineContext().ensureActive()
            throw exception.toWebDavSyncException()
        }
    }

    companion object {
        const val REMOTE_FOLDER_NAME = "GroceryGenius"
        const val REMOTE_FILE_NAME = "grocery-genius-sync.json"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
    }
}

/**
 * The folder that contains the app's folder, always with a trailing slash.
 * @throws WebDavSyncException if the server URL is not a valid http(s) URL
 */
fun WebDavSyncConfig.rootUrl(): HttpUrl {
    val url = serverUrl.trim().toHttpUrlOrNull()
        ?: throw WebDavSyncException(WebDavSyncError.INVALID_URL)
    return if (url.pathSegments.last().isEmpty()) url else url.newBuilder().addPathSegment("").build()
}

fun WebDavSyncConfig.folderUrl(): HttpUrl = rootUrl().newBuilder()
    .addPathSegment(WebDavClient.REMOTE_FOLDER_NAME)
    .addPathSegment("")
    .build()

fun WebDavSyncConfig.fileUrl(): HttpUrl = rootUrl().newBuilder()
    .addPathSegment(WebDavClient.REMOTE_FOLDER_NAME)
    .addPathSegment(WebDavClient.REMOTE_FILE_NAME)
    .build()

/**
 * Apache's mod_deflate appends a suffix such as -gzip to the ETag of compressed responses, but
 * compares If-Match with the original ETag, so uploads with it would always fail with 412.
 * Nextcloud and ownCloud send the original ETag in OC-ETag, other servers need the suffix removed.
 */
private fun Response.etag(): String? = header("OC-ETag")
    ?: header("ETag")?.replace(COMPRESSION_ETAG_SUFFIX, "\"")

private val COMPRESSION_ETAG_SUFFIX = Regex("""-(gzip|br|zstd)"$""")

private fun Response.toException() = WebDavSyncException(
    error = when (code) {
        401, 403 -> WebDavSyncError.AUTHENTICATION_FAILED
        404 -> WebDavSyncError.NOT_FOUND
        in 300..399 -> WebDavSyncError.REDIRECT
        else -> WebDavSyncError.SERVER_ERROR
    },
    message = "HTTP $code ${request.method} ${request.url.redact()}"
)

private fun IOException.toWebDavSyncException() = WebDavSyncException(
    error = when (this) {
        // thrown when cleartext traffic is not permitted
        is UnknownServiceException -> WebDavSyncError.INSECURE_CONNECTION
        is SSLHandshakeException, is SSLPeerUnverifiedException -> WebDavSyncError.UNTRUSTED_CERTIFICATE
        else -> WebDavSyncError.NETWORK_ERROR
    },
    cause = this
)