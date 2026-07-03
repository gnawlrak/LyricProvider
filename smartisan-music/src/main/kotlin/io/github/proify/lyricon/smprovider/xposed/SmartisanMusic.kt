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
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.smprovider.xposed.Constants.ICON
import io.github.proify.lyricon.smprovider.xposed.Constants.PROVIDER_PACKAGE_NAME
import java.lang.reflect.Field
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * 锤子音乐（Smartisan Music Revived）LSP 歌词提供者。
 *
 * 钩子策略：
 * 1. MediaSession.setMetadata() — 获取歌曲信息，扫描 extras 中所有键找歌词
 * 2. NowPlayingLyricsRepository — 拦截已解析的 EmbeddedLyrics 对象
 * 3. MediaSession.setPlaybackState() — 同步播放状态
 */
object SmartisanMusic : YukiBaseHooker() {
    private const val TAG = "SmartisanMusicProvider"

    private val providerManager by lazy { LyricProviderManager() }

    override fun onHook() {
        // 不限制 processName，确保在所有进程中都生效
        YLog.info(tag = TAG, msg = "onHook called, processName=$processName, packageName=$packageName")
        providerManager.onHook()
    }

    private class LyricProviderManager {
        private var lyricProvider: LyriconProvider? = null
        private var lastSong: Song? = null
        private var currentSongTitle: String? = null
        private var currentSongArtist: String? = null
        private var currentSongDuration: Long = 0

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

                                if (currentSongTitle == title) return@after

                                currentSongTitle = title
                                currentSongArtist = artist
                                currentSongDuration = duration

                                // 1. 尝试从 extras 中扫描歌词 (本地/缓存的歌词)
                                val lyricsFromExtras = extractLyricsFromExtras(metadata)
                                if (lyricsFromExtras != null) {
                                    YLog.info(tag = TAG, msg = "Got lyrics from extras (${lyricsFromExtras.take(80)}...)")
                                    setSongWithLyrics(title, artist, duration, lyricsFromExtras)
                                    return@after
                                }

                                // 2. 没有本地歌词，尝试从 extras 拿 online 标识，调用网易 API 拉歌词
                                val onlineInfo = extractOnlineInfo(metadata)
                                if (onlineInfo != null) {
                                    YLog.info(tag = TAG, msg = "Online song detected: source=${onlineInfo.source}, trackId=${onlineInfo.trackId}")
                                    fetchOnlineLyrics(onlineInfo.source, onlineInfo.trackId, title, artist, duration)
                                    return@after
                                }

                                // 3. 都没歌词，先设个空的占位
                                setSongBasic(title, artist, duration)
                            }
                        }

                        firstMethod {
                            name = "setPlaybackState"
                            parameters(PlaybackState::class.java)
                        }.hook {
                            after {
                                val state = args[0] as? PlaybackState
                                lyricProvider?.player?.setPlaybackState(state)
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
         * 在线歌曲 release 版被 R8 混淆了内部类，没法 hook 内部 lyric 仓库，所以直接走网络。
         */
        private fun fetchOnlineLyrics(source: String, trackId: String, title: String, artist: String?, duration: Long) {
            ioExecutor.execute {
                try {
                    val lrc = when (source) {
                        "netease", "Netease", "NETEASE" -> fetchNeteaseLyric(trackId)
                        else -> {
                            YLog.info(tag = TAG, msg = "Unsupported online source: $source, fallback to search")
                            // 其他源可以用 title+artist 搜索网易云（暂未实现）
                            null
                        }
                    }

                    if (lrc.isNullOrBlank()) {
                        YLog.warn(tag = TAG, msg = "No lyrics from online API for $title (trackId=$trackId)")
                        setSongBasic(title, artist, duration)
                        return@execute
                    }

                    YLog.info(tag = TAG, msg = "Got online lyrics (${lrc.length} chars) for $title")
                    setSongWithLyrics(title, artist, duration, lrc)
                } catch (e: Exception) {
                    YLog.warn(tag = TAG, msg = "fetchOnlineLyrics failed: ${e.message}")
                    setSongBasic(title, artist, duration)
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
                // 翻译歌词拼接在原文后
                val tlyric = json.optJSONObject("tlyric")?.optString("lyric", "").orEmpty()
                lrc + if (tlyric.isNotBlank()) "\n$tlyric" else ""
            } finally {
                conn.disconnect()
            }
        }

        // ---------------------------------- Extras 歌词提取 ----------------------------------

        /**
         * 从 MediaMetadata.extras 中扫描所有键，查找歌词内容。
         * 不依赖硬编码的 key 名，而是扫描所有 extras 键值。
         */
        private fun extractLyricsFromExtras(metadata: MediaMetadata): String? {
            return try {
                val extrasField = MediaMetadata::class.java.getDeclaredField("mBundle")
                extrasField.isAccessible = true
                val bundle = extrasField.get(metadata) as? Bundle ?: return null

                val keys = bundle.keySet()
                YLog.debug(tag = TAG, msg = "extras keys: $keys")

                // 先尝试已知的常见键名
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
                            YLog.info(tag = TAG, msg = "Found lyrics with known key: $key")
                            return lyrics
                        }
                    }
                }

                // 扫描所有键，找包含 LRC 时间标签的内容
                for (key in keys) {
                    val value = bundle.getString(key) ?: continue
                    // LRC 格式: 包含 [mm:ss.xx] 或 [mm:ss]
                    if (value.length > 20 && value.contains("[")) {
                        val lrcPattern = Regex("\\[\\d{2}:\\d{2}[.\\d]*\\]")
                        if (lrcPattern.containsMatchIn(value)) {
                            YLog.info(tag = TAG, msg = "Found lyrics by scanning: key=$key, preview=${value.take(80)}")
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

        @Volatile private var pendingSongId: String? = null
        @Volatile private var pendingSongName: String? = null
        @Volatile private var pendingSongArtist: String? = null
        @Volatile private var pendingSongDuration: Long = 0L
        @Volatile private var pendingLyrics: List<RichLyricLine>? = null

        /**
         * 当 setMetadata 触发时立刻发布歌曲元数据（无歌词），让 hyperlyric 知道当前在播什么。
         */
        private fun setSongBasic(title: String, artist: String?, duration: Long) {
            val id = title.hashCode().toString()
            pendingSongId = id
            pendingSongName = title
            pendingSongArtist = artist
            pendingSongDuration = duration
            pendingLyrics = null
            YLog.info(tag = TAG, msg = "Setting song: $title, lyrics=0 lines (pending fetch)")
            lyricProvider?.player?.setSong(
                Song(
                    id = id,
                    name = title,
                    artist = artist,
                    duration = duration
                )
            )
        }

        /**
         * 当歌词拉取成功后，发布带歌词的歌曲。
         */
        private fun setSongWithLyrics(title: String, artist: String?, duration: Long, lyricsText: String) {
            val lines = parseLyricsToRichLines(lyricsText)
            if (lines.isEmpty()) {
                YLog.warn(tag = TAG, msg = "Failed to parse lyrics for $title (no lines)")
                return
            }
            val id = title.hashCode().toString()
            pendingSongId = id
            pendingSongName = title
            pendingSongArtist = artist
            pendingSongDuration = duration
            pendingLyrics = lines
            YLog.info(tag = TAG, msg = "Setting song: $title, lyrics=${lines.size} lines")
            lyricProvider?.player?.setSong(
                Song(
                    id = id,
                    name = title,
                    artist = artist,
                    duration = duration
                ).apply {
                    lyrics = lines
                }
            )
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
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}