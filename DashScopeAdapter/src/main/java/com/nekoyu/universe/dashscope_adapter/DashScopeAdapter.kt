package com.nekoyu.universe.dashscope_adapter

import com.google.gson.Gson
import com.nekoyu.Universe.LawsLoader.Law
import com.nekoyu.Universe.Universe
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.util.*

class DashScopeAdapter : Law() {
    var gson: Gson = Gson()

    override fun prepare(): Boolean {
        for (file in Objects.requireNonNull<Array<File>?>(getConfigDir().listFiles())) {
            try {
                FileReader(file).use { fr ->
                    val cfg = gson.fromJson<Config>(fr, Config::class.java)
                    val channel = DashScopeChannel()
                    channel.apikey = cfg.APIKey
                    channel.defaultModel = cfg.DefaultModel
                    channel.baseurl = cfg.BaseUrl
                    channel.logger = LoggerFactory.getLogger("DashScope C - " + cfg.ProviderId)
                    Universe.Providers.put(cfg.ProviderId, channel)
                }
            } catch (e: FileNotFoundException) {
                logger.error("能触发这个报错这辈子有了👍", e)
            } catch (e: IOException) {
                logger.error(e.message, e)
            }
        }
        return true
    }

    override fun run() {
    }

    override fun stop() {
    }
}
