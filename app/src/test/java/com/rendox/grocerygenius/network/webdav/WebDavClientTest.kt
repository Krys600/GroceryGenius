package com.rendox.grocerygenius.network.webdav

import com.google.common.truth.Truth.assertThat
import com.rendox.grocerygenius.model.WebDavSyncConfig
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncException
import com.rendox.grocerygenius.sync.webdav.RemoteSnapshotFile
import com.rendox.grocerygenius.sync.webdav.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WebDavClientTest {
    private lateinit var server: MockWebServer
    private lateinit var config: WebDavSyncConfig
    private val client = WebDavClient(
        httpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
        ioDispatcher = Dispatchers.IO
    )
    private val filePath = "/remote.php/dav/files/jane/GroceryGenius/grocery-genius-sync.json"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = WebDavSyncConfig(
            serverUrl = server.url("/remote.php/dav/files/jane").toString(),
            username = "jane",
            password = "app-password",
            isEnabled = true
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `builds the file url regardless of the trailing slash`() {
        val expected = "https://cloud.example.com/remote.php/dav/files/jane/GroceryGenius/grocery-genius-sync.json"
        val withoutSlash = WebDavSyncConfig(serverUrl = " https://cloud.example.com/remote.php/dav/files/jane ")
        val withSlash = WebDavSyncConfig(serverUrl = "https://cloud.example.com/remote.php/dav/files/jane/")

        assertThat(withoutSlash.fileUrl().toString()).isEqualTo(expected)
        assertThat(withSlash.fileUrl().toString()).isEqualTo(expected)
        assertThat(withSlash.folderUrl().toString())
            .isEqualTo("https://cloud.example.com/remote.php/dav/files/jane/GroceryGenius/")
    }

    @Test
    fun `rejects invalid urls`() {
        for (url in listOf("", "cloud.example.com", "ftp://cloud.example.com")) {
            val exception = assertThrows(WebDavSyncException::class.java) {
                WebDavSyncConfig(serverUrl = url).fileUrl()
            }
            assertThat(exception.error).isEqualTo(WebDavSyncError.INVALID_URL)
        }
    }

    @Test
    fun `download returns null when the file does not exist`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        assertThat(client.download(config)).isNull()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo(filePath)
        assertThat(request.getHeader("Authorization")).isEqualTo(Credentials.basic("jane", "app-password"))
    }

    @Test
    fun `download returns the content and the etag`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setHeader("ETag", "\"abc\""))

        assertThat(client.download(config)).isEqualTo(RemoteSnapshotFile(content = "{}", etag = "\"abc\""))
    }

    @Test
    fun `creating the file requires that it does not exist yet`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))

        assertThat(client.upload(config, "{}", replacing = null)).isEqualTo(UploadResult.UPLOADED)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.getHeader("If-None-Match")).isEqualTo("*")
        assertThat(request.getHeader("If-Match")).isNull()
        assertThat(request.body.readUtf8()).isEqualTo("{}")
    }

    @Test
    fun `replacing the file requires that it was not changed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(412))

        val result = client.upload(config, "{}", replacing = RemoteSnapshotFile("{}", etag = "\"abc\""))

        assertThat(result).isEqualTo(UploadResult.PRECONDITION_FAILED)
        assertThat(server.takeRequest().getHeader("If-Match")).isEqualTo("\"abc\"")
    }

    @Test
    fun `download prefers the original etag of Nextcloud`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("{}")
                .setHeader("ETag", "\"abc-gzip\"")
                .setHeader("OC-ETag", "\"abc\"")
        )

        assertThat(client.download(config)?.etag).isEqualTo("\"abc\"")
    }

    @Test
    fun `download removes the suffix that Apache adds to etags of compressed responses`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setHeader("ETag", "\"abc-gzip\""))

        assertThat(client.download(config)?.etag).isEqualTo("\"abc\"")
    }

    @Test
    fun `weak etags are not used as a precondition`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        client.upload(config, "{}", replacing = RemoteSnapshotFile("{}", etag = "W/\"abc\""))

        val request = server.takeRequest()
        assertThat(request.getHeader("If-Match")).isNull()
        assertThat(request.getHeader("If-None-Match")).isNull()
    }

    @Test
    fun `creates the folder when it does not exist`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))

        assertThat(client.upload(config, "{}", replacing = null)).isEqualTo(UploadResult.UPLOADED)

        assertThat(server.takeRequest().method).isEqualTo("PUT")
        val createFolder = server.takeRequest()
        assertThat(createFolder.method).isEqualTo("MKCOL")
        assertThat(createFolder.path).isEqualTo("/remote.php/dav/files/jane/GroceryGenius/")
        assertThat(server.takeRequest().method).isEqualTo("PUT")
    }

    @Test
    fun `check connection verifies the folder and creates the app folder`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207))
        server.enqueue(MockResponse().setResponseCode(405))

        client.checkConnection(config)

        val propfind = server.takeRequest()
        assertThat(propfind.method).isEqualTo("PROPFIND")
        assertThat(propfind.path).isEqualTo("/remote.php/dav/files/jane/")
        assertThat(propfind.getHeader("Depth")).isEqualTo("0")
        assertThat(server.takeRequest().method).isEqualTo("MKCOL")
    }

    @Test
    fun `maps error responses`() {
        val cases = mapOf(
            MockResponse().setResponseCode(401) to WebDavSyncError.AUTHENTICATION_FAILED,
            MockResponse().setResponseCode(404) to WebDavSyncError.NOT_FOUND,
            MockResponse().setResponseCode(405) to WebDavSyncError.NOT_A_WEBDAV_FOLDER,
            MockResponse().setResponseCode(200).setBody("<html></html>") to WebDavSyncError.NOT_A_WEBDAV_FOLDER,
            MockResponse().setResponseCode(301).setHeader("Location", "https://example.com/") to
                WebDavSyncError.REDIRECT,
            MockResponse().setResponseCode(503) to WebDavSyncError.SERVER_ERROR
        )
        for ((response, expectedError) in cases) {
            server.enqueue(response)
            val exception = assertThrows(WebDavSyncException::class.java) {
                runBlocking { client.checkConnection(config) }
            }
            assertThat(exception.error).isEqualTo(expectedError)
        }
    }

    @Test
    fun `maps connection failures to network errors`() {
        server.shutdown()

        val exception = assertThrows(WebDavSyncException::class.java) {
            runBlocking { client.download(config) }
        }

        assertThat(exception.error).isEqualTo(WebDavSyncError.NETWORK_ERROR)
    }
}