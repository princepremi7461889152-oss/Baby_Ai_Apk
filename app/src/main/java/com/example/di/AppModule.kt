package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.BuildConfig
import com.example.data.local.ActionLogDao
import com.example.data.local.AllowedAppDao
import com.example.data.local.AutoReplyRuleDao
import com.example.data.local.BabyDatabase
import com.example.data.local.ChatDao
import com.example.data.local.EmergencyContactDao
import com.example.data.local.MIGRATION_1_2
import com.example.data.local.MIGRATION_2_3
import com.example.data.local.MIGRATION_3_4
import com.example.data.local.MacroDao
import com.example.data.local.MemoryDao
import com.example.data.local.QueuedTaskDao
import com.example.data.local.ReminderDao
import com.example.data.local.RoutineDao
import com.example.data.local.SavedPlaceDao
import com.example.data.local.TaskRunDao
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GroqApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
            redactHeader("x-goog-api-key")
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("gemini")
    fun provideGeminiRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("groq")
    fun provideGroqRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideGeminiApiService(@Named("gemini") retrofit: Retrofit): GeminiApiService =
        retrofit.create(GeminiApiService::class.java)

    @Provides
    @Singleton
    fun provideGroqApiService(@Named("groq") retrofit: Retrofit): GroqApiService =
        retrofit.create(GroqApiService::class.java)

    @Provides
    @Singleton
    fun provideBabyDatabase(@ApplicationContext context: Context): BabyDatabase =
        Room.databaseBuilder(
            context,
            BabyDatabase::class.java,
            "baby_assistant_db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration(true).build()

    @Provides
    fun provideChatDao(db: BabyDatabase): ChatDao = db.chatDao()

    @Provides
    fun provideTaskRunDao(db: BabyDatabase): TaskRunDao = db.taskRunDao()

    @Provides
    fun provideQueuedTaskDao(db: BabyDatabase): QueuedTaskDao = db.queuedTaskDao()

    @Provides
    fun provideRoutineDao(db: BabyDatabase): RoutineDao = db.routineDao()

    @Provides
    fun provideAutoReplyRuleDao(db: BabyDatabase): AutoReplyRuleDao = db.autoReplyRuleDao()

    @Provides
    fun provideMemoryDao(db: BabyDatabase): MemoryDao = db.memoryDao()

    @Provides
    fun provideActionLogDao(db: BabyDatabase): ActionLogDao = db.actionLogDao()

    @Provides
    fun provideReminderDao(db: BabyDatabase): ReminderDao = db.reminderDao()

    @Provides
    fun provideMacroDao(db: BabyDatabase): MacroDao = db.macroDao()

    @Provides
    fun provideAllowedAppDao(db: BabyDatabase): AllowedAppDao = db.allowedAppDao()

    @Provides
    fun provideEmergencyContactDao(db: BabyDatabase): EmergencyContactDao = db.emergencyContactDao()

    @Provides
    fun provideSavedPlaceDao(db: BabyDatabase): SavedPlaceDao = db.savedPlaceDao()
}
