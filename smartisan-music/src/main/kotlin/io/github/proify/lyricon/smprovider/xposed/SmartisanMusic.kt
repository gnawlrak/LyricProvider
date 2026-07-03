/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.smprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.ConnectionStatus
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.smprovider.xposed.Constants.ICON
import io.github.proify.lyricon.smprovider.xposed.Constants.PROVIDER_PACKAGE_NAME
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * 锤子音乐（Smartisan Music Revived）LSP 歌词提供者。
 *
 * 钩子策略：
 * 1. MediaSession.setMetadata() — 获取歌曲信息，从 extras 拿在线来源
 * 2. MediaSession.setPlaybackState() — 同步播放状态
 * 3. 在线歌曲通过网易云音乐 API 主动拉取歌词
 */
object SmartisanMusic : YukiBaseHooker() {
    private const val TAG = "SmartisanMusicProvider"

    private val providerManager by lazy { LyricProviderManager() }

    override fun onHook() {
        YLog.info(tag = TAG, msg = "onHook called, processName=$processName, packageName=$packageName")
        providerManager.onHook()
    }

    private class LyricProviderManager {
        private var lyricProvider: LyriconProvider? = null
        private var currentSongTitle: String? = null
        private var currentSongArtist: String? = null
        private var currentSongDuration: Long = 0

        // 等待异步歌词结果时临时存储当前歌曲
        @Volatile private var pendingTitle: String? = null
        @Volatile private var pendingArtist: String? = null
        @Volatile private var pendingDuration: Long = 0

        // 重试机制
        private val mainHandler = Handler(Looper.getMainLooper())
        private var retryRunnable: Runnable? = null
        private var retryCount = 0
        private val isConnected = AtomicBoolean(false)
        private val maxRetries = 10

        // 缓存的待推送数据（延迟重试时使用）
        private var cachedSongTitle: String? = null
        private var cachedSongArtist: String? = null
        private var cachedSongDuration: Long = 0
        private var cachedLyrics: List<RichLyricLine>? = null

        fun onHook() {
            hookMediaSession()
            onAppLifecycle {
                onCreate {
                    setupProvider()
                }
            }
        }

        // ---------------------------------- Provider 初始化 ----------------------------------

        private fun setupProvider() {
            val application = appContext ?: return
            lyricProvider?.destroy()

            lyricProvider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromSvg(ICON)
            ).apply {
                val ok = register()
                YLog.info(tag = TAG, msg = "register() returned: $ok")

                // 添加连接状态监听器
                service.addConnectionListener(object : ConnectionListener {
                    override fun onConnected(provider: LyriconProvider) {
                        YLog.info(tag = TAG, msg = "CONNECTION ESTABLISHED!")
                        isConnected.set(true)
                        retryCount = 0
                        // 连接建立后，CachedRemotePlayer 会自动 sync 缓存数据
                        // 但为了保险，如果有缓存数据且 setSong 之前失败了，再推一次
                        flushCachedSong()
                    }

                    override fun onReconnected(provider: LyriconProvider) {
                        YLog.info(tag = TAG, msg = "CONNECTION RE-ESTABLISHED!")
                        isConnected.set(true)
                        flushCachedSong()
                    }

                    override fun onDisconnected(provider: LyriconProvider) {
                        YLog.warn(tag = TAG, msg = "Connection disconnected")
                        isConnected.set(false)
                    }

                    override fun onConnectTimeout(provider: LyriconProvider) {
                        YLog.warn(tag = TAG, msg = "Connection timeout! Current status=${service.connectionStatus}")
                        isConnected.set(false)
                        // 超时后自动重试
                        scheduleRetry()
                    }
                })

                val status = service.connectionStatus
                YLog.info(tag = TAG, msg = "Provider created, connectionStatus=$status, app=${application.packageName}")
            }

            YLog.info(tag = TAG, msg = "Lyricon provider setup complete, app=${application.packageName}")
        }

        private fun scheduleRetry() {
            if (retryCount >= maxRetries) {
                YLog.warn(tag = TAG, msg = "Max retries ($maxRetries) reached, giving up")
                return
            }
            retryCount++
            YLog.info(tag = TAG, msg = "Scheduling retry #$retryCount in 5s")

            retryRunnable?.let { mainHandler.removeCallbacks(it) }
            retryRunnable = Runnable {
                val provider = lyricProvider ?: return@Runnable
                YLog.info(tag = TAG, msg = "Retry #$retryCount: unregister + register")
                provider.unregister()
                try {
                    Thread.sleep(500) // 短暂等待
                } catch (_: InterruptedException) {}
                val ok = provider.register()
                YLog.info(tag = TAG, msg = "Retry #$retryCount: register() returned: $ok, status=${provider.service.connectionStatus}")
            }
            mainHandler.postDelayed(retryRunnable!!, 5000)
        }

        private fun flushCachedSong() {
            val title = cachedSongTitle ?: return
            val artist = cachedSongArtist
            val dur = cachedSongDuration
            val lyrics = cachedLyrics
            cachedSongTitle = null
            cachedSongArtist = null
            cachedSongDuration = 0
            cachedLyrics = null
            YLog.info(tag = TAG, msg = "Flushing cached song: $title, lyrics=${lyrics?.size ?: 0} lines")
            pushSongWithLyrics(title, artist, dur, lyrics ?: emptyList())
        }

        // ---------------------------------- MediaSession 钩子 ----------------------------------

        private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SmartisanLyricIO").apply { isDaemon = true } }

        private fun hookMediaSession() {
            try {
                "android.media.session.MediaSession".toClass()
                    .resolve()
                    .apply {
                        firstMethod {
                            name = "setMetadata"
                            parameters(MediaMetadata::class.java)
                        }.hook {
                            after {
                                val metadata = args[0] as? MediaMetadata
                                if (metadata == null) {
                                    YLog.debug(tag = TAG, msg = "setMetadata called but metadata is null")
                                    return@after
                                }

                                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                                val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

                                YLog.info(tag = TAG, msg = "setMetadata: title=$title, artist=$artist, duration=$duration")

                                if (title == null) return@after

                                if (currentSongTitle == title) {
                                    YLog.debug(tag = TAG, msg = "Same song, skip")
                                    return@after
                                }

                                currentSongTitle = title
                                currentSongArtist = artist
                                currentSongDuration = duration

                                // 1. 尝试从 extras 中扫描本地歌词
                                val lyricsFromExtras = extractLyricsFromExtras(metadata)
                                if (lyricsFromExtras != null) {
                                    YLog.info(tag = TAG, msg = "Got lyrics from extras (${lyricsFromExtras.length} chars)")
                                    val lines = parseLyricsToRichLines(lyricsFromExtras)
                                    if (lines.isNotEmpty()) {
                                        pushSongWithLyrics(title, artist, duration, lines)
                                        return@after
                                    }
                                }

                                // 2. 没有本地歌词，尝试从 extras 拿 online 标识，调用网易 API 拉歌词
                                val onlineInfo = extractOnlineInfo(metadata)
                                if (onlineInfo != null) {
                                    YLog.info(tag = TAG, msg = "Online song detected: source=${onlineInfo.source}, trackId=${onlineInfo.trackId}")
                                    fetchOnlineLyrics(onlineInfo.source, onlineInfo.trackId, title, artist, duration)
                                    return@after
                                }

                                // 3. 都没有歌词，发送占位（hyperlyric 才能看到当前在播什么）
                                pushSongWithoutLyrics(title, artist, duration)
                            }
                        }

                        firstMethod {
                            name = "setPlaybackState"
                            parameters(PlaybackState::class.java)
                        }.hook {
                            after {
                                val state = args[0] as? PlaybackState
                                val ok = lyricProvider?.player?.setPlaybackState(state) == true
                                YLog.debug(tag = TAG, msg = "setPlaybackState forwarded: ok=$ok")
                            }
                        }
                    }

                YLog.info(tag = TAG, msg = "MediaSession hooks installed")
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "Failed to hook MediaSession: ${e.message}")
            }
        }

        private data class OnlineInfo(val source: String, val trackId: String)

        /**
         * 从 extras 中提取在线歌曲来源信息。
         */
        private fun extractOnlineInfo(metadata: MediaMetadata): OnlineInfo? {
            return try {
                val extrasField = MediaMetadata::class.java.getDeclaredField("mBundle")
                extrasField.isAccessible = true
                val bundle = extrasField.get(metadata) as? Bundle ?: return null

                val source = bundle.getString("com.smartisanos.music.extra.ONLINE_SOURCE")
                val trackId = bundle.getString("com.smartisanos.music.extra.ONLINE_TRACK_ID")
                if (source != null && trackId != null) OnlineInfo(source, trackId) else null
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "Failed to extract online info: ${e.message}")
                null
            }
        }

        /**
         * 主动从网易云拉歌词。
         */
        private fun fetchOnlineLyrics(source: String, trackId: String, title: String, artist: String?, duration: Long) {
            // 缓存当前歌曲元数据，等异步回来后用
            pendingTitle = title
            pendingArtist = artist
            pendingDuration = duration

            ioExecutor.execute {
                try {
                    val lrc = when (source) {
                        "netease", "Netease", "NETEASE" -> fetchNeteaseLyric(trackId)
                        else -> {
                            YLog.info(tag = TAG, msg = "Unsupported online source: $source")
                            null
                        }
                    }

                    // 如果 pending 已经被新歌曲覆盖，则丢弃本次结果
                    if (pendingTitle != title) {
                        YLog.info(tag = TAG, msg = "Pending song changed, discard lyrics for $title")
                        return@execute
                    }

                    if (lrc.isNullOrBlank()) {
                        YLog.warn(tag = TAG, msg = "No lyrics from online API for $title (trackId=$trackId)")
                        pushSongWithoutLyrics(title, artist, duration)
                        return@execute
                    }

                    YLog.info(tag = TAG, msg = "Got online lyrics (${lrc.length} chars) for $title")
                    val lines = parseLyricsToRichLines(lrc)
                    if (lines.isNotEmpty()) {
                        pushSongWithLyrics(title, artist, duration, lines)
                    } else {
                        YLog.warn(tag = TAG, msg = "Failed to parse lyrics for $title")
                        pushSongWithoutLyrics(title, artist, duration)
                    }
                } catch (e: Exception) {
                    YLog.warn(tag = TAG, msg = "fetchOnlineLyrics failed: ${e.message}")
                    pushSongWithoutLyrics(title, artist, duration)
                }
            }
        }

        private fun fetchNeteaseLyric(trackId: String): String? {
            val url = URL("https://music.163.com/api/song/lyric?id=$trackId&lv=1&kv=1&tv=-1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            conn.setRequestProperty("Referer", "https://music.163.com/")
            return try {
                val code = conn.responseCode
                if (code != 200) {
                    YLog.warn(tag = TAG, msg = "Netease API HTTP $code for trackId=$trackId")
                    return null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                if (json.optInt("code") != 200) {
                    YLog.warn(tag = TAG, msg = "Netease API code=${json.optInt("code")} for trackId=$trackId")
                    return null
                }
                val lrcObj = json.optJSONObject("lrc") ?: return null
                val lrc = lrcObj.optString("lyric", "")
                if (lrc.isBlank()) return null
                val tlyric = json.optJSONObject("tlyric")?.optString("lyric", "").orEmpty()
                lrc + if (tlyric.isNotBlank()) "\n$tlyric" else ""
            } finally {
                conn.disconnect()
            }
        }

        // ---------------------------------- Extras 歌词提取 ----------------------------------

        private fun extractLyricsFromExtras(metadata: MediaMetadata): String? {
            return try {
                val extrasField = MediaMetadata::class.java.getDeclaredField("mBundle")
                extrasField.isAccessible = true
                val bundle = extrasField.get(metadata) as? Bundle ?: return null

                val keys = bundle.keySet()
                YLog.debug(tag = TAG, msg = "extras keys: $keys")

                val knownKeys = listOf(
                    "com.smartisanos.music.extra.ONLINE_WORD_LYRICS",
                    "com.smartisanos.music.extra.ONLINE_LYRICS",
                    "com.smartisanos.music.extra.ONLINE_TRANSLATED_LYRICS",
                    "com.smartisanos.music.extra.ONLINE_TRANSLATED_WORD_LYRICS",
                    "OnlineLyricsExtraKey",
                    "OnlineWordLyricsExtraKey",
                    "ONLINE_LYRICS",
                    "ONLINE_WORD_LYRICS",
                )

                for (key in knownKeys) {
                    if (key in keys) {
                        val lyrics = bundle.getString(key)
                        if (lyrics != null && lyrics.contains("[")) {
                            YLog.info(tag = TAG, msg = "Found lyrics with known key: $key, length=${lyrics.length}")
                            return lyrics
                        }
                    }
                }

                // 扫描所有键，找包含 LRC 时间标签的内容
                for (key in keys) {
                    val value = bundle.getString(key) ?: continue
                    if (value.length > 20 && value.contains("[")) {
                        val lrcPattern = Regex("\\[\\d{2}:\\d{2}[.\\d]*\\]")
                        if (lrcPattern.containsMatchIn(value)) {
                            YLog.info(tag = TAG, msg = "Found lyrics by scanning: key=$key, length=${value.length}")
                            return value
                        }
                    }
                }

                null
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "Failed to read extras: ${e.message}")
                null
            }
        }

        // ---------------------------------- Song 设置 ----------------------------------

        /**
         * 推送带歌词的 Song。参照 163-music 的模式：直接用 setSong 推送，不调 setPosition/sendText。
         * 如果 setSong 返回 false（订阅端未连接），缓存数据并等待连接建立后重发。
         */
        private fun pushSongWithLyrics(title: String, artist: String?, duration: Long, lines: List<RichLyricLine>) {
            val id = title.hashCode().toString()
            val song = Song(
                id = id,
                name = title,
                artist = artist,
                duration = duration,
                lyrics = lines
            )
            YLog.info(
                tag = TAG,
                msg = "Pushing song: id=$id, title=$title, lyrics=${lines.size} lines, " +
                        "first='${lines.firstOrNull()?.text?.take(30)}'"
            )

            // 诊断：序列化 JSON 快照，验证 lyrics 数据是否完整
            try {
                val diagJson = Json {
                    coerceInputValues = true
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                    encodeDefaults = false
                }
                val jsonStr = diagJson.encodeToString(Song.serializer(), song)
                val snippet = jsonStr.take(600)
                YLog.info(tag = TAG, msg = "Song JSON: $snippet")
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "Song JSON serialization failed: ${e.message}")
            }

            val player = lyricProvider?.player
            if (player == null) {
                YLog.warn(tag = TAG, msg = "lyricProvider is null, cannot setSong")
                return
            }
            val ok = player.setSong(song)
            YLog.info(tag = TAG, msg = "setSong returned: $ok, connected=${isConnected.get()}, status=${lyricProvider?.service?.connectionStatus}")

            if (!ok) {
                // 连接未建立，缓存数据等待重连
                cachedSongTitle = title
                cachedSongArtist = artist
                cachedSongDuration = duration
                cachedLyrics = lines
                YLog.info(tag = TAG, msg = "Song cached for retry, scheduling re-registration")
                scheduleRetry()
            }
        }

        /**
         * 推送不带歌词的 Song（占位），让 hyperlyric 端知道当前在播什么。
         */
        private fun pushSongWithoutLyrics(title: String, artist: String?, duration: Long) {
            val id = title.hashCode().toString()
            val song = Song(
                id = id,
                name = title,
                artist = artist,
                duration = duration
            )
            YLog.info(tag = TAG, msg = "Pushing song (no lyrics): id=$id, title=$title")
            val player = lyricProvider?.player
            if (player == null) {
                YLog.warn(tag = TAG, msg = "lyricProvider is null, cannot setSong")
                return
            }
            val ok = player.setSong(song)
            YLog.info(tag = TAG, msg = "setSong (no lyrics) returned: $ok, connected=${isConnected.get()}, status=${lyricProvider?.service?.connectionStatus}")

            if (!ok) {
                cachedSongTitle = title
                cachedSongArtist = artist
                cachedSongDuration = duration
                cachedLyrics = null
                YLog.info(tag = TAG, msg = "Song (no lyrics) cached for retry, scheduling re-registration")
                scheduleRetry()
            }
        }

        /**
         * 解析歌词文本为 RichLyricLine 列表。参照 163-music 的 toRichLines() 模式：
         * 用 LrcParser 解析成 LyricLine，再手动构造 RichLyricLine。
         */
        private fun parseLyricsToRichLines(text: String): List<RichLyricLine> {
            return try {
                val lrcDoc = LrcParser.parse(text.trim())
                lrcDoc.lines.map { line ->
                    RichLyricLine(
                        begin = line.begin,
                        end = line.end,
                        duration = line.duration,
                        text = line.text,
                        words = line.words
                    )
                }
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "parseLyricsToRichLines failed: ${e.message}")
                emptyList()
            }
        }
    }
}
