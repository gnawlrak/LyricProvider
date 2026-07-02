/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.smprovider.xposed

import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

/**
 * 将 LRC 原始文本解析为 [Song]。
 * 优先使用 EnhanceLrcParser 获取逐字时间轴，失败时回退到标准 LrcParser。
 */
fun String.toSong(metadata: Metadata): Song {
    val lines = toRichLines()

    return Song(
        id = metadata.id.toString(),
        name = metadata.title,
        artist = metadata.artist,
        duration = metadata.duration
    ).apply {
        lyrics = lines
    }
}

/**
 * 将 LRC 原始文本解析为 [RichLyricLine] 列表。
 * 优先 EnhanceLrcParser（支持逐字时间轴），失败时回退到 LrcParser。
 */
private fun String.toRichLines(): List<RichLyricLine> {
    val trimmed = this.trim()
    if (trimmed.isEmpty()) return emptyList()

    // 优先尝试增强解析（逐字时间轴）
    try {
        val doc = EnhanceLrcParser.parse(trimmed)
        if (doc.lines.isNotEmpty()) return doc.lines
    } catch (_: Exception) {
        // 回退到标准解析
    }

    // 回退到标准 LRC 解析
    return try {
        val doc = LrcParser.parse(trimmed)
        doc.lines.map { line ->
            RichLyricLine(
                begin = line.begin,
                end = line.end,
                duration = line.duration,
                text = line.text
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}