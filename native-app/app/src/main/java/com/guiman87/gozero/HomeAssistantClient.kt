package com.guiman87.gozero

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class ConversationRequest(
    val text: String,
    val language: String = "en"
)

data class ConversationResponse(
    val response: ConversationResponseData?
)

data class ConversationResponseData(
    val speech: ConversationSpeech?,
    val response_type: String?
)

data class ConversationSpeech(
    val plain: ConversationSpeechPlain?
)

data class ConversationSpeechPlain(
    val speech: String?
)

interface HomeAssistantApi {
    @POST("api/conversation/process")
    suspend fun processConversation(
        @Header("Authorization") token: String,
        @Body request: ConversationRequest
    ): ConversationResponse
}

object HomeAssistantClient {
    private var retrofit: Retrofit? = null
    private var api: HomeAssistantApi? = null

    fun getApi(baseUrl: String): HomeAssistantApi {
        // Ensure trailing slash
        val formattedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        
        if (retrofit?.baseUrl()?.toString() != formattedUrl) {
            retrofit = Retrofit.Builder()
                .baseUrl(formattedUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            api = retrofit!!.create(HomeAssistantApi::class.java)
        }
        return api!!
    }
}
