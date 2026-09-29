package com.kieronquinn.app.ambientmusicmod

import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kieronquinn.app.ambientmusicmod.repositories.ApiRepository
import com.kieronquinn.app.ambientmusicmod.repositories.AmbientServiceRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingConnectionTest {
    @Test fun installedNowPlayingAnswersSettingsQuery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getApplicationInfo(
            "com.kieronquinn.app.pixelambientmusic", PackageManager.GET_META_DATA
        )
        assertTrue(ApiRepository.COMPATIBLE_APIS.contains(
            info.metaData.getInt(ApiRepository.API_VERSION_TAG)
        ))
        assertTrue(PackageManager.PERMISSION_GRANTED == context.checkSelfPermission(
            "com.kieronquinn.app.pixelambientmusic.ACCESS_SERVICE"
        ))
        val uri = Uri.parse("content://com.google.android.as.pam.ambientmusic.settings")
        context.contentResolver.query(uri, null, null, null, null).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
            assertTrue(cursor.getString(0).isNotBlank())
        }
    }

    @Test fun installedNowPlayingAcceptsServiceBinding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = AmbientServiceRepositoryImpl(context).getService()
        assertNotNull(service)
        assertTrue(service!!.ping())
    }
}
