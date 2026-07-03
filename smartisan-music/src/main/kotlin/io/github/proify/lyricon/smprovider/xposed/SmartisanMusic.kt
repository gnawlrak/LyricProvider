/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.smprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.smprovider.xposed.Constants.ICON
import io.github.proify.lyricon.smprovider.xposed.Constants.PROVIDER_PACKAGE_NAME
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
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
                register()
            }

            YLog.info(tag = TAG, msg = "Lyricon provider registered, app=${application.packageName}")
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
         * 推送带歌词的 Song。优先让 hyperlyric 端能立刻看到完整数据。
         */
        private fun pushSongWithLyrics(title: String, artist: String?, duration: Long, lines: List<RichLyricLine>) {
            val id = title.hashCode().toString()
            // 过滤掉没有文本的行（HyperLyric 的 normalize() 会把空文本的行删除）
            // 保留 [interlude] 形式的空行（用作间奏标记）
            val validLines = lines.filter { line ->
                val t = line.text
                !t.isNullOrBlank()
            }
            if (validLines.isEmpty()) {
                YLog.warn(tag = TAG, msg = "All lines have empty text after filter, fallback to no-lyrics push")
                pushSongWithoutLyrics(title, artist, duration)
                return
            }
            val song = Song(
                id = id,
                name = title,
                artist = artist,
                duration = duration
            ).apply {
                lyrics = validLines
            }
            val firstText = validLines.firstOrNull()?.text ?: ""
            val lastText = validLines.lastOrNull()?.text ?: ""
            YLog.info(
                tag = TAG,
                msg = "Pushing song: id=$id, title=$title, lyrics=${validLines.size} lines, " +
                        "first='$firstText', last='$lastText', " +
                        "allHaveText=${validLines.count { !it.text.isNullOrBlank() }}/${validLines.size}"
            )
            // 打印每行前几行 text，确认序列化前的数据
            validLines.take(3).forEachIndexed { i, line ->
                YLog.info(
                    tag = TAG,
                    msg = "  line[$i] text='${line.text}' begin=${line.begin} end=${line.end} " +
                            "words=${line.words?.size ?: 0}"
                )
            }
            val player = lyricProvider?.player
            if (player == null) {
                YLog.warn(tag = TAG, msg = "lyricProvider is null, cannot setSong")
                return
            }
            val ok = player.setSong(song)
            YLog.info(tag = TAG, msg = "setSong returned: $ok")

            // 强制 setPosition(0) 触发位置更新，确保 HyperLyric 端能找到第一行歌词
            try {
                val posOk = player.setPosition(0L)
                YLog.info(tag = TAG, msg = "setPosition(0) returned: $posOk")
            } catch (e: Throwable) {
                YLog.warn(tag = TAG, msg = "setPosition(0) failed: ${e.message}")
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
            YLog.info(tag = TAG, msg = "setSong (no lyrics) returned: $ok")
        }

        private fun parseLyricsToRichLines(text: String): List<RichLyricLine> {
            return try {
                val doc = io.github.proify.lrckit.EnhanceLrcParser.parse(text.trim())
                if (doc.lines.isNotEmpty()) {
                    doc.lines
                } else {
                    val lrcDoc = io.github.proify.lrckit.LrcParser.parse(text.trim())
                    lrcDoc.lines.map { line ->
                        RichLyricLine(
                            begin = line.begin,
                            end = line.end,
                            duration = line.duration,
                            text = line.text
                        )
                    }
                }
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "parseLyricsToRichLines failed: ${e.message}")
                emptyList()
            }
        }
    }
}
