package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppAliasEntity
import com.example.domain.tools.AppResolution
import com.example.domain.tools.AppResolver
import com.example.domain.tools.IndexedApp
import com.example.features.offline.FastPathIntentMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FastPathAndAppResolverTest {

    private lateinit var resolver: AppResolver

    private val sampleInstalledApps = listOf(
        IndexedApp("com.google.android.youtube", "YouTube", "youtube"),
        IndexedApp("com.google.android.apps.youtube.music", "YouTube Music", "youtubemusic"),
        IndexedApp("com.instagram.android", "Instagram", "instagram"),
        IndexedApp("com.whatsapp", "WhatsApp", "whatsapp"),
        IndexedApp("com.google.android.apps.nbu.paisa.user", "Google Pay", "googlepay"),
        IndexedApp("com.phonepe.app", "PhonePe", "phonepe"),
        IndexedApp("net.one97.paytm", "Paytm", "paytm"),
        IndexedApp("com.android.chrome", "Chrome", "chrome"),
        IndexedApp("com.google.android.apps.maps", "Maps", "maps"),
        IndexedApp("com.spotify.music", "Spotify", "spotify"),
        IndexedApp("com.facebook.katana", "Facebook", "facebook"),
        IndexedApp("in.amazon.mShop.android.shopping", "Amazon", "amazon"),
        IndexedApp("com.flipkart.android", "Flipkart", "flipkart"),
        IndexedApp("com.android.vending", "Play Store", "playstore"),
        IndexedApp("com.google.android.gm", "Gmail", "gmail"),
        IndexedApp("org.telegram.messenger", "Telegram", "telegram"),
        IndexedApp("com.android.camera", "Camera", "camera"),
        IndexedApp("com.android.settings", "Settings", "settings"),
        IndexedApp("com.google.android.calculator", "Calculator", "calculator"),
        IndexedApp("com.google.android.calendar", "Calendar", "calendar"),
        IndexedApp("com.example.notes.alpha", "Quick Notes", "quicknotes"),
        IndexedApp("com.example.notes.beta", "Quick Notes Pro", "quicknotespro")
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        resolver = AppResolver(ctx)
    }

    @Test
    fun `suite 1 fast-path matches 50+ Hindi, Hinglish, and English phrases accurately under 300ms`() {
        val cases = listOf(
            // Flashlight / Torch (7)
            "Flashlight on karo" to ("toggle_flashlight" to mapOf("enable" to true)),
            "Hey Baby torch jala do" to ("toggle_flashlight" to mapOf("enable" to true)),
            "flashlite on" to ("toggle_flashlight" to mapOf("enable" to true)),
            "flashlight band karo" to ("toggle_flashlight" to mapOf("enable" to false)),
            "torch bujhao" to ("toggle_flashlight" to mapOf("enable" to false)),
            "turn off flashlight" to ("toggle_flashlight" to mapOf("enable" to false)),
            "टॉर्च चालू करो" to ("toggle_flashlight" to mapOf("enable" to true)),

            // Wi-Fi & Bluetooth & DND (9)
            "wifi on karo" to ("toggle_wifi" to mapOf("enable" to true)),
            "wi-fi band kar do" to ("toggle_wifi" to mapOf("enable" to false)),
            "turn off wifi" to ("toggle_wifi" to mapOf("enable" to false)),
            "bluetooth chalu karo" to ("toggle_bluetooth" to mapOf("enable" to true)),
            "blutooth on" to ("toggle_bluetooth" to mapOf("enable" to true)),
            "bt off karo" to ("toggle_bluetooth" to mapOf("enable" to false)),
            "dnd on karo" to ("toggle_dnd" to mapOf("enable" to true)),
            "do not disturb band karo" to ("toggle_dnd" to mapOf("enable" to false)),
            "silent mode disable karo" to ("toggle_dnd" to mapOf("enable" to false)),

            // Volume (8)
            "volume 70% karo" to ("set_volume" to mapOf("stream" to "media", "level_percent" to 70)),
            "awaz full karo" to ("set_volume" to mapOf("stream" to "media", "level_percent" to 100)),
            "awaz kam karo" to ("set_volume" to mapOf("stream" to "media", "level_percent" to 30)),
            "volume badhao" to ("set_volume" to mapOf("stream" to "media", "level_percent" to 70)),
            "mute sound" to ("set_volume" to mapOf("stream" to "media", "level_percent" to 0)),
            "unmute audio" to ("set_volume" to mapOf("stream" to "media", "level_percent" to 60)),
            "ring volume 80" to ("set_volume" to mapOf("stream" to "ring", "level_percent" to 80)),
            "वॉल्यूम 40" to ("set_volume" to mapOf("stream" to "media", "level_percent" to 40)),

            // Brightness (5)
            "brightness 25% set karo" to ("set_brightness" to mapOf("level_percent" to 25)),
            "brightness low karo" to ("set_brightness" to mapOf("level_percent" to 20)),
            "screen light full karo" to ("set_brightness" to mapOf("level_percent" to 95)),
            "roshni kam karo" to ("set_brightness" to mapOf("level_percent" to 20)),
            "brigtness 85" to ("set_brightness" to mapOf("level_percent" to 85)),

            // Timer & Alarm (7)
            "5 minute ka timer lagao" to ("set_timer" to mapOf("seconds" to 300)),
            "30 second timer" to ("set_timer" to mapOf("seconds" to 30)),
            "1 hour countdown" to ("set_timer" to mapOf("seconds" to 3600)),
            "subah 6 baje alarm lagao" to ("set_alarm" to mapOf("hour" to 6, "minute" to 0)),
            "7:30 pm alarm lagao" to ("set_alarm" to mapOf("hour" to 19, "minute" to 30)),
            "8 baje utha dena" to ("set_alarm" to mapOf("hour" to 8, "minute" to 0)),
            "अलार्म 9 baje" to ("set_alarm" to mapOf("hour" to 9, "minute" to 0)),

            // Back / Home / Media (9)
            "go back" to ("go_back" to emptyMap()),
            "peeche jao" to ("go_back" to emptyMap()),
            "go home" to ("go_home" to emptyMap()),
            "home screen jao" to ("go_home" to emptyMap()),
            "gaana pause karo" to ("media_control" to mapOf("action" to "pause")),
            "next song" to ("media_control" to mapOf("action" to "next")),
            "pichla gaana" to ("media_control" to mapOf("action" to "previous")),
            "play music" to ("media_control" to mapOf("action" to "play")),
            "gaana band karo" to ("media_control" to mapOf("action" to "stop")),

            // Open App & Call (6)
            "open youtube" to ("open_app" to mapOf("app_name" to "youtube")),
            "yutub kholo" to ("open_app" to mapOf("app_name" to "yutub")),
            "insta chala do" to ("open_app" to mapOf("app_name" to "insta")),
            "call Mummy" to ("call_contact" to mapOf("target" to "mummy")),
            "Papa ko call lagao" to ("call_contact" to mapOf("target" to "papa")),
            "dial 9876543210" to ("call_contact" to mapOf("target" to "9876543210"))
        )

        assertTrue("Expected at least 50 test phrases", cases.size >= 50)

        val startNs = System.nanoTime()
        for ((phrase, expected) in cases) {
            val matched = FastPathIntentMatcher.match(
                rawCommand = phrase,
                currentMediaVolumePercent = { 50 }
            )
            assertNotNull("Fast-path missed phrase: $phrase", matched)
            assertEquals("Wrong tool for '$phrase'", expected.first, matched!!.toolName)
            for ((argKey, argVal) in expected.second) {
                assertEquals("Wrong arg '$argKey' for '$phrase'", argVal, matched.args[argKey])
            }
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("51 fast-path matches took too long: ${elapsedMs}ms", elapsedMs < 300L)
    }

    @Test
    fun `suite 1 fast-path rejects non-trivial and Search-and-Play queries cleanly`() {
        val shouldNotMatch = listOf(
            "YouTube par Arijit Singh ka gaana bajao",
            "Spotify pe Kesariya chalao",
            "Aaj mausam kaisa hai Delhi mein?",
            "WhatsApp par Mummy ko message bhejo ki main ghar aa raha hoon",
            "Ye screen par kya likha hai translate karo",
            "Baby stop",
            "Mera naam yaad rakhna Rahul hai"
        )
        for (query in shouldNotMatch) {
            val matched = FastPathIntentMatcher.match(query)
            assertNull("Fast-path falsely matched complex query '$query' -> $matched", matched)
        }
    }

    @Test
    fun `suite 2 app resolver fuzzy and Hinglish aliases resolve 30+ variants`() {
        val aliasExpectations = mapOf(
            "yutub" to "com.google.android.youtube",
            "utube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "YouTube" to "com.google.android.youtube",
            "yt music" to "com.google.android.apps.youtube.music",
            "ytmusic" to "com.google.android.apps.youtube.music",
            "insta" to "com.instagram.android",
            "ig" to "com.instagram.android",
            "instgram" to "com.instagram.android",
            "whatsap" to "com.whatsapp",
            "watsap" to "com.whatsapp",
            "whtsapp" to "com.whatsapp",
            "wa" to "com.whatsapp",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "phonepe" to "com.phonepe.app",
            "phone pe" to "com.phonepe.app",
            "fonpe" to "com.phonepe.app",
            "paytm" to "net.one97.paytm",
            "chrome" to "com.android.chrome",
            "krome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "spoitfy" to "com.spotify.music",
            "fb" to "com.facebook.katana",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "play store" to "com.android.vending",
            "gmail" to "com.google.android.gm",
            "telegram" to "org.telegram.messenger",
            "telegrame" to "org.telegram.messenger",
            "camra" to "com.android.camera",
            "settings" to "com.android.settings",
            "com.whatsapp" to "com.whatsapp"
        )

        assertTrue(aliasExpectations.size >= 30)

        for ((query, expectedPkg) in aliasExpectations) {
            val res = resolver.resolveFromIndex(query, sampleInstalledApps)
            assertTrue(
                "Expected Resolved for '$query', got $res",
                res is AppResolution.Resolved
            )
            assertEquals(
                "Wrong package resolved for '$query'",
                expectedPkg,
                (res as AppResolution.Resolved).app.packageName
            )
        }
    }

    @Test
    fun `suite 2 app resolver prioritizes user aliases, handles ambiguity and missing apps`() {
        val customAlias = AppAliasEntity(
            alias = "gaana",
            packageName = "com.spotify.music",
            appLabel = "Spotify",
            isUserEdited = true
        )
        val userResolved = resolver.resolveFromIndex("gaana", sampleInstalledApps, listOf(customAlias))
        assertTrue(userResolved is AppResolution.Resolved)
        assertEquals("com.spotify.music", (userResolved as AppResolution.Resolved).app.packageName)
        assertEquals("user_alias", userResolved.matchReason)

        val ambiguous = resolver.resolveFromIndex("Quick Notes App", sampleInstalledApps)
        assertTrue("Expected Ambiguous for 'Quick Notes App', got $ambiguous", ambiguous is AppResolution.Ambiguous)
        assertTrue((ambiguous as AppResolution.Ambiguous).candidates.size >= 2)

        val notFound = resolver.resolveFromIndex("ZylophoneNonExistentApp", sampleInstalledApps)
        assertTrue(notFound is AppResolution.NotFound)
    }
}
