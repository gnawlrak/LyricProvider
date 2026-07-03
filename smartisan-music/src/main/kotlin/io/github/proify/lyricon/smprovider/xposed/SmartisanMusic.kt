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

            YLog.info(tag = TAG, msg = "Lyricon provider registered, app=${application.packageName}")
        }

        // ---------------------------------- MediaSession 钩子 ----------------------------------

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

                                // 尝试从 extras 中扫描歌词
                                val lyricsFromExtras = extractLyricsFromExtras(metadata)
                                if (lyricsFromExtras != null) {
                                    YLog.info(tag = TAG, msg = "Got lyrics from extras (${lyricsFromExtras.take(80)}...)")
                                    setSongWithLyrics(title, artist, duration, lyricsFromExtras)
                                    return@after
                                }

                                // 尚无歌词，后续由 NowPlayingLyricsRepository 钩子补充
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

        // ---------------------------------- NowPlayingLyricsRepository 钩子 ----------------------------------

        private fun hookNowPlayingLyricsRepository() {
            try {
                // 使用 appClassLoader 加载内部类
                val repoClass = "com.smartisanos.music.playback.NowPlayingLyricsRepository".toClass(appClassLoader)
                    .resolve()
                YLog.info(tag = TAG, msg = "NowPlayingLyricsRepository found: $repoClass")

                // Hook peek(MediaItem) — 缓存查询
                repoClass.method {
                    name = "peek"
                }.forEach { method ->
                    YLog.info(tag = TAG, msg = "Hooking peek method")
                    method.hook {
                        after {
                            val returnValue = result ?: return@after
                            YLog.info(tag = TAG, msg = "peek() returned: ${returnValue.javaClass.simpleName}")
                            processEmbeddedLyrics(returnValue)
                        }
                    }
                }

                // Hook load(Context, MediaItem, boolean) — 异步加载 (suspend 函数)
                repoClass.method {
                    name = "load"
                }.forEach { method ->
                    YLog.info(tag = TAG, msg = "Hooking load method")
                    method.hook {
                        after {
                            val returnValue = result ?: return@after
                            YLog.info(tag = TAG, msg = "load() returned: ${returnValue.javaClass.simpleName}")
                            processEmbeddedLyrics(returnValue)
                        }
                    }
                }

                YLog.info(tag = TAG, msg = "NowPlayingLyricsRepository hooks installed")
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "NowPlayingLyricsRepository not found: ${e.message}")
            }

            // 后备方案：直接 hook loadEmbeddedLyrics 函数
            try {
                val embeddedLyricsKt = "com.smartisanos.music.playback.EmbeddedLyricsKt".toClass(appClassLoader)
                    .resolve()
                YLog.info(tag = TAG, msg = "EmbeddedLyricsKt found: $embeddedLyricsKt")

                embeddedLyricsKt.method {
                    name = "loadEmbeddedLyrics"
                }.forEach { method ->
                    YLog.info(tag = TAG, msg = "Hooking loadEmbeddedLyrics method")
                    method.hook {
                        after {
                            val returnValue = result ?: return@after
                            YLog.info(tag = TAG, msg = "loadEmbeddedLyrics returned: ${returnValue.javaClass.simpleName}")
                            processEmbeddedLyrics(returnValue)
                        }
                    }
                }

                YLog.info(tag = TAG, msg = "EmbeddedLyricsKt hooks installed")
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "EmbeddedLyricsKt not found: ${e.message}")
            }
        }

        /**
         * 通过反射处理 EmbeddedLyrics 对象。
         */
        private fun processEmbeddedLyrics(lyricsObj: Any) {
            try {
                val objClass = lyricsObj.javaClass
                YLog.debug(tag = TAG, msg = "processEmbeddedLyrics: class=${objClass.name}, fields=${objClass.declaredFields.joinToString { it.name }}")

                val linesField = getField(objClass, "lines") ?: return
                val lines = linesField.get(lyricsObj) as? List<*> ?: return
                if (lines.isEmpty()) return

                val title = currentSongTitle ?: return
                val artist = currentSongArtist
                val duration = currentSongDuration

                val richLines = convertEmbeddedLines(lines)
                if (richLines.isEmpty()) return

                YLog.info(tag = TAG, msg = "Processed ${richLines.size} lyrics lines for: $title")

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
                    val end = begin + 5000

                    val tokens = tokensField?.get(lineObj) as? List<*>
                    val words = if (tokens != null && tokens.isNotEmpty()) {
                        tokens.mapNotNull { tokenObj ->
                            try {
                                val tokenClass = tokenObj?.javaClass ?: return@mapNotNull null
                                val tokenTextField = getField(tokenClass, "text")
                                val tokenTimeField = getField(tokenClass, "timestampMs")
                                val tokenWord = tokenTextField?.get(tokenObj) as? String ?: ""
                                val tokenBegin = (tokenTimeField?.get(tokenObj) as? Long) ?: 0L
                                LyricWord(begin = tokenBegin, text = tokenWord)
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
            YLog.info(tag = TAG, msg = "Setting song: ${song.name}, lyrics=${song.lyrics?.size ?: 0} lines")
            lyricProvider?.player?.setSong(song)
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