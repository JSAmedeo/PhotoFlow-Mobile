package com.photoflowmobile.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudApiClientTest {

    @Test fun `jpg maps to image jpeg`() {
        assertEquals("image/jpeg", CloudApiClient.mimeTypeFor("photo.jpg"))
    }

    @Test fun `jpeg maps to image jpeg`() {
        assertEquals("image/jpeg", CloudApiClient.mimeTypeFor("photo.jpeg"))
    }

    @Test fun `png maps to image png`() {
        assertEquals("image/png", CloudApiClient.mimeTypeFor("image.png"))
    }

    @Test fun `heic maps to image heic`() {
        assertEquals("image/heic", CloudApiClient.mimeTypeFor("photo.heic"))
    }

    @Test fun `heif maps to image heic`() {
        assertEquals("image/heic", CloudApiClient.mimeTypeFor("photo.heif"))
    }

    @Test fun `cr2 maps to octet-stream`() {
        assertEquals("application/octet-stream", CloudApiClient.mimeTypeFor("raw.cr2"))
    }

    @Test fun `cr3 maps to octet-stream`() {
        assertEquals("application/octet-stream", CloudApiClient.mimeTypeFor("raw.cr3"))
    }

    @Test fun `nef maps to octet-stream`() {
        assertEquals("application/octet-stream", CloudApiClient.mimeTypeFor("raw.nef"))
    }

    @Test fun `arw maps to octet-stream`() {
        assertEquals("application/octet-stream", CloudApiClient.mimeTypeFor("raw.arw"))
    }

    @Test fun `unknown extension maps to octet-stream`() {
        assertEquals("application/octet-stream", CloudApiClient.mimeTypeFor("file.xyz"))
    }

    @Test fun `no extension maps to octet-stream`() {
        assertEquals("application/octet-stream", CloudApiClient.mimeTypeFor("noextension"))
    }

    @Test fun `extension matching is case-insensitive`() {
        assertEquals("image/jpeg", CloudApiClient.mimeTypeFor("PHOTO.JPG"))
    }
}
