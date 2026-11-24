package com.example.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

interface MercuryApiService {
    @GET
    suspend fun listModels(
        @Url url: String,
        @Header("Authorization") bearerAuth: String
    ): Response<MercuryModelsListResponse>

    @POST
    suspend fun chatCompletions(
        @Url url: String,
        @Header("Authorization") bearerAuth: String,
        @Body request: MercuryChatRequest
    ): Response<GroqChatResponse>

    @Streaming
    @POST
    suspend fun chatCompletionsStream(
        @Url url: String,
        @Header("Authorization") bearerAuth: String,
        @Body request: MercuryChatRequest
    ): Response<ResponseBody>
}
