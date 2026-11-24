package com.example.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface GroqApiService {
    @GET("openai/v1/models")
    suspend fun listModels(
        @Header("Authorization") bearerAuth: String
    ): Response<GroqModelsListResponse>

    @POST("openai/v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") bearerAuth: String,
        @Body request: GroqChatRequest
    ): Response<GroqChatResponse>

    @Multipart
    @POST("openai/v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") bearerAuth: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("response_format") responseFormat: RequestBody
    ): Response<GroqTranscriptionResponse>
}
