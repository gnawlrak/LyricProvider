/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.smprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
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

/**
 * 锤子音乐（SmartisanOS Music）LSP 歌词提供者。
 *
 * 钩子策略：
 * 1. MediaSession.setMetadata() — 获取歌曲基本信息（标题、歌手、时长）
 * 2. NowPlayingLyricsRepository.peek() — 拦截已解析的 EmbeddedLyrics 对象
 * 3. MediaMetadata.extras — 读取自定义歌词键（在线歌词）
 */
object SmartisanMusic : YukiBaseHooker() {
    private const val TAG = "SmartisanMusicProvider"

    // 锤子音乐自定义歌词键（存储在 MediaMetadata.extras 中）
    private const val ONLINE_LYRICS_KEY = "com.smartisanos.music.extra.ONLINE_LYRICS"
    private const val ONLINE_TRANSLATED_LYRICS_KEY = "com.smartisanos.music.extra.ONLINE_TRANSLATED_LYRICS"
    private const val ONLINE_WORD_LYRICS_KEY = "com.smartisanos.music.extra.ONLINE_WORD_LYRICS"
    private const val ONLINE_TRANSLATED_WORD_LYRICS_KEY = "com.smartisanos.music.extra.ONLINE_TRANSLATED_WORD_LYRICS"

    private val providerManager by lazy { LyricProviderManager() }

    override fun onHook() {
        when (processName) {
            packageName -> {
                YLog.info(tag = TAG, msg = "Hooking $processName")
                providerManager.onHook()
            }
        }
    }

    private class LyricProviderManager {
        private var lyricProvider: LyriconProvider? = null
        private var lastSong: Song? = null
        private var currentMediaId: Long = 0
        private var currentSongTitle: String? = null
        private var currentSongArtist: String? = null
        private var currentSongDuration: Long = 0

        fun onHook() {
            onAppLifecycle {
                onCreate { setupProvider() }
            }
            hookMediaSession()
            hookNowPlayingLyricsRepository()
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

            YLog.info(tag = TAG, msg = "Lyricon provider registered")
        }

        // ---------------------------------- MediaSession 钩子 ----------------------------------

        private fun hookMediaSession() {
            "android.media.session.MediaSession".toClass()
                .apply {
                    firstMethod {
                        name = "setMetadata"
                        parameters(MediaMetadata::class.java)
                    }.hook {
                        after {
                            val metadata = args[0] as? MediaMetadata ?: return@after
                            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return@after
                            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                            val id = title.hashCode().toLong()

                            if (currentMediaId == id && currentSongTitle == title) return@after

                            currentMediaId = id
                            currentSongTitle = title
                            currentSongArtist = artist
                            currentSongDuration = duration

                            YLog.debug(tag = TAG, msg = "Song changed: $title - $artist")

                            // 尝试从 extras 中读取在线歌词
                            val lyricsFromExtras = extractLyricsFromExtras(metadata)
                            if (lyricsFromExtras != null) {
                                YLog.debug(tag = TAG, msg = "Got lyrics from MediaMetadata.extras")
                                setSongWithLyrics(title, artist, duration, lyricsFromExtras)
                                return@after
                            }

                            // 尚无歌词，只设置基本信息
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
        }

        // ---------------------------------- NowPlayingLyricsRepository 钩子 ----------------------------------

        /**
         * Hook NowPlayingLyricsRepository 的方法，拦截已解析的 EmbeddedLyrics。
         * 这是最可靠的歌词来源，因为锤子音乐已经完成了所有解析工作。
         *
         * 类路径: com.smartisanos.music.playback.NowPlayingLyricsRepository
         * 关键方法:
         *   - peek(MediaItem): EmbeddedLyrics?  — 同步缓存查询
         *   - load(Context, MediaItem, Boolean): EmbeddedLyrics? — 异步加载
         */
        private fun hookNowPlayingLyricsRepository() {
            try {
                val repoClass = "com.smartisanos.music.playback.NowPlayingLyricsRepository".toClass()

                // Hook peek() — 缓存查询，每次歌词变更都会触发
                repoClass.method {
                    name = "peek"
                }.forEach { method ->
                    method.hook {
                        after {
                            val result = this.result ?: return@after
                            processEmbeddedLyrics(result)
                        }
                    }
                }

                // Hook load() — 异步加载完成后的回调
                repoClass.method {
                    name = "load"
                }.forEach { method ->
                    method.hook {
                        after {
                            val result = this.result ?: return@after
                            processEmbeddedLyrics(result)
                        }
                    }
                }

                YLog.info(tag = TAG, msg = "NowPlayingLyricsRepository hooks installed")
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "NowPlayingLyricsRepository not found: ${e.message}")
            }
        }

        /**
         * 通过反射处理 EmbeddedLyrics 对象，转换为 RichLyricLine 列表。
         */
        private fun processEmbeddedLyrics(lyricsObj: Any) {
            try {
                val objClass = lyricsObj.javaClass
                val linesField = getField(objClass, "lines") ?: return
                val lines = linesField.get(lyricsObj) as? List<*> ?: return
                if (lines.isEmpty()) return

                val title = currentSongTitle ?: return
                val artist = currentSongArtist
                val duration = currentSongDuration

                val richLines = convertEmbeddedLines(lines)
                if (richLines.isEmpty()) return

                YLog.debug(tag = TAG, msg = "Processed ${richLines.size} lyrics lines for: $title")

                setSong(
                    Song(
                        id = title.hashCode().toString(),
                        name = title,
                        artist = artist,
                        duration = duration
                    ).apply {
                        lyrics = richLines
                    }
                )
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "Failed to process EmbeddedLyrics: ${e.message}")
            }
        }

        /**
         * 将 EmbeddedLyricsLine 列表转换为 RichLyricLine 列表。
         *
         * EmbeddedLyricsLine 字段:
         *   - text: String
         *   - timestampMs: Long?
         *   - translation: String?
         *   - tokens: List<EmbeddedLyricsToken>
         *
         * EmbeddedLyricsToken 字段:
         *   - text: String
         *   - timestampMs: Long
         *   - endTimestampMs: Long?
         */
        private fun convertEmbeddedLines(lines: List<*>): List<RichLyricLine> {
            return lines.mapNotNull { lineObj ->
                try {
                    val lineClass = lineObj?.javaClass ?: return@mapNotNull null
                    val textField = getField(lineClass, "text")
                    val timestampField = getField(lineClass, "timestampMs")
                    val translationField = getField(lineClass, "translation")
                    val tokensField = getField(lineClass, "tokens")

                    val text = textField?.get(lineObj) as? String ?: ""
                    val timestampMs = (timestampField?.get(lineObj) as? Long) ?: 0L
                    val translation = translationField?.get(lineObj) as? String

                    val begin = timestampMs
                    val end = begin + 5000 // 默认 5 秒，实际会被下一行覆盖

                    // 解析逐字时间轴
                    val tokens = tokensField?.get(lineObj) as? List<*>
                    val words = if (tokens != null && tokens.isNotEmpty()) {
                        tokens.mapNotNull { tokenObj ->
                            try {
                                val tokenClass = tokenObj?.javaClass ?: return@mapNotNull null
                                val tokenTextField = getField(tokenClass, "text")
                                val tokenTimeField = getField(tokenClass, "timestampMs")
                                val tokenWord = tokenTextField?.get(tokenObj) as? String ?: ""
                                val tokenBegin = (tokenTimeField?.get(tokenObj) as? Long) ?: 0L
                                LyricWord(tokenWord, tokenBegin)
                            } catch (_: Exception) {
                                null
                            }
                        }
                    } else {
                        emptyList()
                    }

                    RichLyricLine(
                        begin = begin,
                        end = end,
                        duration = end - begin,
                        text = text,
                        translation = translation,
                        words = words
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }

        /**
         * 通过反射获取字段（包括父类和私有字段）。
         */
        private fun getField(clazz: Class<*>, name: String): Field? {
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                try {
                    val field = current.getDeclaredField(name)
                    field.isAccessible = true
                    return field
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                }
            }
            return null
        }

        // ---------------------------------- Extras 歌词提取 ----------------------------------

        /**
         * 从 MediaMetadata.extras 中提取锤子音乐的自定义歌词。
         */
        private fun extractLyricsFromExtras(metadata: MediaMetadata): String? {
            return try {
                val extrasField = MediaMetadata::class.java.getDeclaredField("mBundle")
                extrasField.isAccessible = true
                val bundle = extrasField.get(metadata) as? Bundle ?: return null

                // 优先使用逐字歌词（YRC），其次是标准歌词（LRC）
                bundle.getString(ONLINE_WORD_LYRICS_KEY)
                    ?: bundle.getString(ONLINE_LYRICS_KEY)
            } catch (e: Exception) {
                YLog.debug(tag = TAG, msg = "Failed to read extras: ${e.message}")
                null
            }
        }

        // ---------------------------------- Song 设置 ----------------------------------

        private fun setSongBasic(title: String, artist: String?, duration: Long) {
            val song = Song(
                id = title.hashCode().toString(),
                name = title,
                artist = artist,
                duration = duration
            )
            setSong(song)
        }

        private fun setSongWithLyrics(title: String, artist: String?, duration: Long, lyricsText: String) {
            val lines = parseLyricsToRichLines(lyricsText)
            val song = Song(
                id = title.hashCode().toString(),
                name = title,
                artist = artist,
                duration = duration
            ).apply {
                lyrics = lines
            }
            setSong(song)
        }

        private fun setSong(song: Song) {
            if (lastSong == song) return
            lastSong = song
            lyricProvider?.player?.setSong(song)
        }

        /**
         * 将 LRC 文本解析为 RichLyricLine 列表。
         */
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