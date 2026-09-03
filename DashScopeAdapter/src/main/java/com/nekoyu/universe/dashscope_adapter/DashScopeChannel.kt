package com.nekoyu.universe.dashscope_adapter

import com.google.gson.Gson
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Context
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.T2IRequest
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.T2IResponse
import com.nekoyu.Universe.API.Providers.LLMProvider.TextToImage
import com.nekoyu.universe.dashscope_adapter.T2IReq.Input.Message
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.Logger
import java.io.IOException

class DashScopeChannel : LLMProvider(), TextToImage {
    @JvmField
    var apikey: String? = null
    @JvmField
    var defaultModel: String? = null
    @JvmField
    var baseurl: String? = null
    @JvmField
    var logger: Logger? = null
    val gson: Gson = Gson()

    @Throws(IOException::class)
    override fun completions(
        model: String?,
        context: Context?,
        llmTools: List<LLMFunction?>?,
        completionsRequest: CompletionsRequest?,
        bufferCallback: BufferCallback?
    ): CompletionsResponse? {
        return null
    }

    override fun t2i(request: T2IRequest): T2IResponse? {
        val url = "$baseurl/services/aigc/multimodal-generation/generation"

        val t2IReq = T2IReq()
        t2IReq.model = if (request.model != null) request.model.toString() else "qwen-image-2.0-pro"
        val message = Message()
        for (field in request.message) {
            if (field is ImageField) {
                message.content.add(Message.Image(field.url))
            } else {
                message.content.add(Message.Text(field.toString()))
            }
        }
        t2IReq.input.messages.add(message)

        val body = gson.toJson(request)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apikey")
            .post(body)
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }

            val body = response.body?.string()
            println(body)
            // warning : not finished
        }

        return null
    }

    companion object {
        var client: OkHttpClient = OkHttpClient()
    }
}
