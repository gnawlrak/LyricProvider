/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.smprovider.xposed

import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.session.PlaybackState
import android.net.Uri
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.smprovider.xposed.Constants.ICON
import io.github.proify.lyricon.smprovider.xposed.Constants.PROVIDER_PACKAGE_NAME
import java.io.File

/**
 * 锤子音乐（SmartisanOS Music）LSP 歌词提供者。
 *
 * 通过 Hook MediaSession 获取歌曲元数据，并从以下来源提取歌词：
 * 1. MediaMetadata 中的 METADATA_KEY_LYRICS（Android 标准键）
 * 2. 音频文件内嵌的 LRC 标签（通过 MediaMetadataRetriever）
 * 3. 锤子音乐内部 LyricsTextParser 的解析结果（通过 Hook 拦截）
 */
object SmartisanMusic : YukiBaseHooker() {
    private const val TAG = "SmartisanMusicProvider"

    private val providerManager by lazy { LyricProviderManager() }

    override fun onHook() {
        when (processName) {
            packageName -> {
                YLog.debug(tag = TAG, msg = "Hooking $processName")
                providerManager.onHook()
            }
        }
    }

    private class LyricProviderManager {
        private var lyricProvider: LyriconProvider? = null
        private var lastSong: Song? = null
        private var currentMediaId: Long = 0
        private var currentFilePath: String? = null

        fun onHook() {
            onAppLifecycle {
                onCreate { setupProvider() }
            }
            hookMediaSession()
            hookLyricsTextParser()
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

            YLog.info(tag = TAG, msg = "Provider registered")
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
                            val data = MetadataCache.save(metadata) ?: return@after
                            if (currentMediaId == data.id) return@after

                            currentMediaId = data.id
                            currentFilePath = null
                            onSongChanged(data, metadata)
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

        // ---------------------------------- 内部 LyricsTextParser 钩子 ----------------------------------

        /**
         * 尝试 Hook 锤子音乐内部的 LyricsTextParser，拦截其解析结果。
         * 类路径: com.smartisanos.music.playback.LyricsTextParser
         *
         * 如果该类不存在（版本不匹配），则回退到从音频文件提取歌词。
         */
        private fun hookLyricsTextParser() {
            try {
                "com.smartisanos.music.playback.LyricsTextParser".toClass()
                    .apply {
                        // 尝试 Hook 所有返回歌词数据的方法
                        method {
                            returnType = List::class.java
                        }.forEach { method ->
                            method.hook {
                                after {
                                    val result = this.result as? List<*> ?: return@after
                                    if (result.isEmpty()) return@after

                                    YLog.debug(
                                        tag = TAG,
                                        msg = "Intercepted LyricsTextParser result: ${result.size} lines"
                                    )

                                    // 尝试将解析结果转换为歌词文本
                                    val lyricText = convertLyricListToText(result)
                                    if (lyricText.isNotEmpty()) {
                                        val metadata = MetadataCache.get(currentMediaId) ?: return@after
                                        setSong(lyricText.toSong(metadata))
                                    }
                                }
                            }
                        }
                    }
                YLog.info(tag = TAG, msg = "LyricsTextParser hook installed")
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "LyricsTextParser not found, using fallback: ${e.message}")
            }
        }

        /**
         * 尝试将 SmartisanMusic 的歌词对象列表转换为 LRC 文本。
         * EmbeddedLyricsLine 包含 begin、end、text、tokens 等字段。
         */
        private fun convertLyricListToText(list: List<*>): String {
            return try {
                list.joinToString("\n") { item ->
                    val itemClass = item?.javaClass ?: return@joinToString ""
                    try {
                        // 尝试获取 begin 和 text 字段
                        val beginField = itemClass.getDeclaredField("begin").apply { isAccessible = true }
                        val textField = itemClass.getDeclaredField("text").apply { isAccessible = true }
                        val begin = beginField.get(item) as? Long ?: 0L
                        val text = textField.get(item) as? String ?: ""
                        formatLrcLine(begin, text)
                    } catch (_: Exception) {
                        item.toString()
                    }
                }
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "Failed to convert lyric list: ${e.message}")
                ""
            }
        }

        private fun formatLrcLine(timeMs: Long, text: String): String {
            val min = timeMs / 60000
            val sec = (timeMs % 60000) / 1000
            val ms = (timeMs % 1000) / 10
            return "[%02d:%02d.%02d]%s".format(min, sec, ms, text)
        }

        // ---------------------------------- 歌曲变更处理 ----------------------------------

        private fun onSongChanged(metadata: Metadata, mediaMetadata: MediaMetadata) {
            // 1. 优先尝试从 MediaMetadata 获取歌词（Android 标准键）
            val lyricsFromMetadata = getLyricsFromMediaMetadata(mediaMetadata)
            if (!lyricsFromMetadata.isNullOrBlank()) {
                YLog.debug(tag = TAG, msg = "Got lyrics from MediaMetadata")
                setSong(lyricsFromMetadata.toSong(metadata))
                return
            }

            // 2. 尝试从音频文件提取内嵌歌词
            val filePath = getFilePathFromMetadata(mediaMetadata)
            if (filePath != null) {
                currentFilePath = filePath
                val lyricsFromFile = extractLyricsFromFile(filePath)
                if (!lyricsFromFile.isNullOrBlank()) {
                    YLog.debug(tag = TAG, msg = "Got lyrics from audio file")
                    setSong(lyricsFromFile.toSong(metadata))
                    return
                }
            }

            // 3. 无歌词可用时，仅设置歌曲基本信息
            YLog.debug(tag = TAG, msg = "No lyrics available, setting basic info")
            setSong(
                Song(
                    id = metadata.id.toString(),
                    name = metadata.title,
                    artist = metadata.artist,
                    duration = metadata.duration
                )
            )
        }

        /**
         * 从 MediaMetadata 中提取歌词文本。
         * 尝试 Android 标准 METADATA_KEY_LYRICS 键。
         */
        private fun getLyricsFromMediaMetadata(metadata: MediaMetadata): String? {
            // Android 标准歌词键（API 34+）
            return metadata.getString("android.media.metadata.LYRICS")
                ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.let {
                    // 尝试通过反射获取可能的自定义键
                    null
                }
        }

        /**
         * 从 MediaMetadata 中获取音频文件路径。
         */
        private fun getFilePathFromMetadata(metadata: MediaMetadata): String? {
            return metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
                ?: metadata.getString("android.media.metadata.MEDIA_URI")
        }

        /**
         * 从音频文件中提取内嵌歌词。
         * 使用 MediaMetadataRetriever 读取 LRC 标签。
         */
        private fun extractLyricsFromFile(filePath: String): String? {
            return try {
                val uri = if (filePath.startsWith("/")) {
                    Uri.fromFile(File(filePath))
                } else {
                    Uri.parse(filePath)
                }

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(appContext, uri)
                    // 尝试读取内嵌歌词
                    val lyrics = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_LYRICS
                    )
                    lyrics?.takeIf { it.isNotBlank() }
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                YLog.warn(tag = TAG, msg = "Failed to extract lyrics from file: ${e.message}")
                null
            }
        }

        private fun setSong(song: Song) {
            if (lastSong == song) return
            lastSong = song
            lyricProvider?.player?.setSong(song)
        }
    }
}