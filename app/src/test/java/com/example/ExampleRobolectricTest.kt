package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.SecureKeyStore
import com.example.data.local.ActionLogEntity
import com.example.data.local.AllowedAppEntity
import com.example.data.local.BabyDatabase
import com.example.data.local.EmergencyContactEntity
import com.example.data.local.MacroEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.ReminderEntity
import com.example.data.local.RoutineEntity
import com.example.data.local.SavedPlaceEntity
import com.example.features.macros.MacroManager
import com.example.features.macros.MacroStep
import com.example.features.routines.RoutineManager
import com.example.features.routines.RoutineStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read app name string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Baby", appName)
  }

  @Test
  fun `routine steps round-trip JSON encoding cleanly`() {
    val steps = listOf(
      RoutineStep("toggle_dnd", mapOf("enable" to true)),
      RoutineStep("set_brightness", mapOf("level_percent" to 25))
    )
    val encoded = RoutineManager.encodeSteps(steps)
    val decoded = RoutineManager.decodeSteps(encoded)
    assertEquals(2, decoded.size)
    assertEquals("toggle_dnd", decoded[0].tool)
    assertEquals(true, decoded[0].args["enable"])
    assertEquals("set_brightness", decoded[1].tool)
  }

  @Test
  fun `macro element-identifier JSON round-trip preserves viewId, text, desc, and package`() {
    val steps = listOf(
      MacroStep(
        tool = "tap",
        args = mapOf("x" to 240, "y" to 620, "description" to "Play"),
        label = "Tap Play",
        viewId = "com.spotify.music:id/play_button",
        targetText = "Play",
        contentDesc = "Play music",
        packageName = "com.spotify.music"
      ),
      MacroStep(
        tool = "type_text",
        args = mapOf("text" to "Arijit Singh"),
        label = "Type search",
        viewId = "com.spotify.music:id/search_input",
        packageName = "com.spotify.music"
      )
    )
    val encoded = MacroManager.encodeSteps(steps)
    val decoded = MacroManager.decodeSteps(encoded)
    assertEquals(2, decoded.size)
    assertEquals("com.spotify.music:id/play_button", decoded[0].viewId)
    assertEquals("Play", decoded[0].targetText)
    assertEquals("Play music", decoded[0].contentDesc)
    assertEquals("com.spotify.music", decoded[0].packageName)
    assertTrue(decoded[0].hasElementIdentifier())
    assertEquals("Arijit Singh", decoded[1].args["text"])
  }

  @Test
  fun `secure keystore hashes voice pin with salt and verifies cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val keyStore = SecureKeyStore(context)
    assertFalse(keyStore.settings.value.hasVoicePin)

    keyStore.setVoicePinCode("2580")
    assertTrue(keyStore.settings.value.hasVoicePin)
    assertTrue(keyStore.verifyVoicePin("2580"))
    assertFalse(keyStore.verifyVoicePin("1234"))

    keyStore.setVoicePinCode("")
    assertFalse(keyStore.settings.value.hasVoicePin)
    assertTrue(keyStore.verifyVoicePin(""))
  }

  @Test
  fun `room v4 phase 1-3 entities persist and query cleanly`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, BabyDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val now = System.currentTimeMillis()
      val oldTs = now - (10L * 24L * 60L * 60L * 1000L)
      db.actionLogDao().insert(
        ActionLogEntity(
          timestamp = oldTs,
          commandText = "Old voice cmd",
          toolName = "toggle_flashlight",
          argsSummary = "{}",
          resultText = "OK",
          source = "voice"
        )
      )
      db.actionLogDao().insert(
        ActionLogEntity(
          timestamp = now,
          commandText = "Morning routine",
          toolName = "stop_alarm",
          argsSummary = "{}",
          resultText = "OK",
          source = "routine"
        )
      )
      db.actionLogDao().deleteOlderThan(now - (7L * 24L * 60L * 60L * 1000L))
      val remainingLogs = db.actionLogDao().getAllForExport()
      assertEquals(1, remainingLogs.size)
      assertEquals("routine", remainingLogs.first().source)

      db.memoryDao().upsert(
        MemoryEntity(
          category = "NICKNAME",
          memoryKey = "Mummy",
          memoryValue = "Sunita Sharma"
        )
      )
      assertEquals("Sunita Sharma", db.memoryDao().findByKey("mummy")?.memoryValue)
      db.memoryDao().deleteByKey("mummy")
      assertNull(db.memoryDao().findByKey("Mummy"))

      // Phase 2 Room v4: SavedPlaceEntity & ReminderDao reschedule/snapshot
      val placeId = db.savedPlaceDao().upsert(
        SavedPlaceEntity(
          placeKey = "home",
          label = "Home",
          latitude = 28.6139,
          longitude = 77.2090,
          radiusMeters = 250f,
          addressHint = "New Delhi"
        )
      )
      assertEquals("Home", db.savedPlaceDao().findByKeyOrLabel("home")?.label)
      assertEquals(1, db.savedPlaceDao().getAllSnapshot().size)
      db.savedPlaceDao().deleteById(placeId)

      val remId = db.reminderDao().upsert(
        ReminderEntity(
          title = "Doodh lena",
          triggerType = "TIME",
          triggerAtMs = now + 60_000L
        )
      )
      db.reminderDao().markCompleted(remId)
      assertEquals(0, db.reminderDao().getAllActiveSnapshot().size)
      assertEquals(1, db.reminderDao().getAllSnapshot().size)
      db.reminderDao().reschedule(remId, now + 120_000L)
      assertEquals(1, db.reminderDao().getAllActiveSnapshot().size)

      // Phase 3: Custom command reused via RoutineEntity(scheduleType = COMMAND), Macros, AllowedApps, SOS contacts
      db.routineDao().upsert(
        RoutineEntity(
          name = "Movie Mode",
          triggerPhrase = "movie mode",
          stepsJson = RoutineManager.encodeSteps(
            listOf(
              RoutineStep("toggle_dnd", mapOf("enable" to true)),
              RoutineStep("open_app", mapOf("app_name" to "Netflix"))
            )
          ),
          scheduleType = RoutineManager.SCHEDULE_TYPE_COMMAND
        )
      )
      val commands = db.routineDao().getEnabledRoutines().filter { it.scheduleType == RoutineManager.SCHEDULE_TYPE_COMMAND }
      assertEquals(1, commands.size)
      assertEquals("Movie Mode", commands.first().name)

      db.macroDao().upsert(
        MacroEntity(
          name = "Play Liked Songs",
          triggerPhrase = "liked songs",
          stepsJson = MacroManager.encodeSteps(
            listOf(MacroStep("tap", mapOf("description" to "Liked Songs"), "Liked", viewId = "com.spotify:id/liked"))
          )
        )
      )
      assertEquals(1, db.macroDao().getEnabled().size)

      db.allowedAppDao().upsert(AllowedAppEntity(packageName = "com.whatsapp", appLabel = "WhatsApp"))
      assertEquals(1, db.allowedAppDao().getAllowed().size)

      db.emergencyContactDao().upsert(EmergencyContactEntity(name = "Mummy", phoneNumber = "+919876543210"))
      assertEquals(1, db.emergencyContactDao().getAll().size)
    } finally {
      db.close()
    }
  }
}
