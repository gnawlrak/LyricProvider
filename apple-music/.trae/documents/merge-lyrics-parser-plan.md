# HyperLyric 歌词解析器增强计划

## 摘要

参照 [LyricProvider](https://github.com/tomakino/LyricProvider) 的 `lrckit` 共享库模式，将 [SmartisanMusic-Revived](https://github.com/Mangi-11/SmartisanMusic-Revived) 中强大的歌词解析能力（LRC / 增强 LRC / YRC 逐字歌词 / 网易云 API）移植到 HyperLyric 中，使其超级岛歌词显示从仅支持基础 LRC 升级为支持逐字同步、翻译歌词等丰富效果。

## 当前状态分析

### HyperLyric 现有歌词系统（弱点）

| 模块 | 文件 | 问题 |
|------|------|------|
| LRC 解析器 | `common/lyric/LrcParser.kt` | 仅支持 `[mm:ss.xx]` 基础格式，无法解析增强 LRC 和 YRC |
| 歌词数据模型 | `lyric/LyricModels.kt` | `LrcLine(startTimeMs, content)` 无词级时间戳，无翻译字段 |
| 在线歌词获取 | `online/OnlineLyricTargeter.kt` | 通过 QQ音乐/网易云搜索 API 获取歌词，但返回的是 `LrcLine` 简单列表 |
| 歌词源 | `service/source/OnlineLyricSource.kt` | 返回 `List<LrcLine>?`，丢弃了翻译和逐字信息 |
| 歌词源 | `service/source/MetadataLrcLyricSource.kt` | 仅调 `LrcParser.parse()` 解析 raw LRC 文本 |
| 歌词源 | `service/source/LyricInfoLyricSource.kt` | 通过 LyricInfo 格式获取，但同样只返回 `LrcLine` |
| 调度器 | `service/scheduler/LyricScheduler.kt` | 基于 `List<LrcLine>` 调度歌词显示 |

### HyperLyric 已有的富歌词渲染能力（可复用）

HyperLyric 已从 lyricon 继承了完整的富歌词渲染系统：
- `RichLyricLine` — 支持原文 + 翻译 + 罗马音 + 副轨道
- `LyricWord` — 逐字时间戳
- `SpaceGateRichLyricLineView` — 逐字同步渲染引擎
- `RichLyricLineModel` / `LyricLineAssembler` — 歌词组装

**但目前这些组件没有被充分利用，因为上游解析器只能产出简单的 `LrcLine`。**

### SmartisanMusic-Revived 可移植的资产

| 来源文件 | 能力 | 移植目标 |
|----------|------|----------|
| `LyricsTextParser.kt` | LRC/增强LRC/YRC 三种格式解析，翻译合并，质量评分 | HyperLyric 新 `lrckit` 模块 |
| `EmbeddedLyrics.kt` | 数据模型 `EmbeddedLyrics`/`EmbeddedLyricsLine`/`EmbeddedLyricsToken` | 转换为 HyperLyric 的 `RichLyricLine`/`LyricWord` |
| `NeteaseOnlineMusicRepository.kt` 的 `getLyrics()` 及 `parseLyricsResponse()` | 网易云 eAPI `/song/lyric/v1` 获取原始+翻译+逐字歌词 | HyperLyric 的 `OnlineLyricSource` 或 `LyricProviderImpl` |
| `OnlineLyricsDiskCache.kt` | 歌词磁盘缓存（JSON, SHA-256 key, 7天TTL） | 替换 HyperLyric 的简单文件缓存 `LrcCacheManager` |

### LyricProvider 的参考架构

```
share/lrckit/
  ├── LrcParser.kt          → 输出 LrcDocument (List<LyricLine>)
  ├── LrcDocument.kt
  ├── EnhanceLrcParser.kt   → 输出 EnhanceLrcDocument (List<RichLyricLine>)
  └── EnhanceLrcDocument.kt

share/cloudlyric/
  ├── LyricsProvider.kt     → 在线歌词搜索接口
  ├── LyricsResult.kt       → 返回 RichLyricLine 列表
  └── ProviderLyrics.kt

share/extensions-kt/
  └── LyricKt.kt            → 歌词行转换工具
```

## 变更方案

### 变更 1：创建 `lrckit` 歌词解析模块

**新增文件：** `app/src/main/java/com/lidesheng/hyperlyric/lrckit/LrcParser.kt`

**内容：** 基于 SmartisanMusic 的 `LyricsTextParser.kt` 重写，输出 HyperLyric 原生数据模型。

```kotlin
// 核心 API 设计
object LrcParser {
    fun parse(raw: String?, duration: Long = 0): LrcDocument
    // LrcDocument 包含 metadata + List<LyricLine>
}

object EnhanceLrcParser {
    fun parse(raw: String?, duration: Long = 0): EnhanceLrcDocument
    // EnhanceLrcDocument 包含 metadata + List<RichLyricLine>
    // RichLyricLine 中 words 字段包含逐字时间戳 LyricWord 列表
}

object YrcParser {
    fun parse(raw: String?, duration: Long = 0): EnhanceLrcDocument
    // 解析网易云 YRC 格式: [startMs,durationMs](tokenStart,tokenDuration)text
}
```

**新增文件：** `app/src/main/java/com/lidesheng/hyperlyric/lrckit/LrcDocument.kt`

```kotlin
data class LrcDocument(
    val metadata: Map<String, String> = emptyMap(),
    val lines: List<LyricLine> = emptyList()
)
```

**新增文件：** `app/src/main/java/com/lidesheng/hyperlyric/lrckit/EnhanceLrcDocument.kt`

```kotlin
data class EnhanceLrcDocument(
    val metadata: Map<String, String> = emptyMap(),
    val lines: List<RichLyricLine> = emptyList()
)
```

**新增文件：** `app/src/main/java/com/lidesheng/hyperlyric/lrckit/OnlineLyricsMerger.kt`

```kotlin
object OnlineLyricsMerger {
    // 从在线歌词 API 返回的原始文本解析并合并为 RichLyricLine
    fun parseOnlineLyrics(
        lyric: String?,           // 原词 LRC
        translatedLyric: String?, // 翻译 LRC
        wordLyric: String?,       // 逐字 YRC
        translatedWordLyric: String?, // 逐字翻译 YRC
        duration: Long = 0
    ): EnhanceLrcDocument
}
```

**改造文件：** `app/src/main/java/com/lidesheng/hyperlyric/common/lyric/LrcParser.kt`

- 替换为调用新的 `com.lidesheng.hyperlyric.lrckit.LrcParser`
- 保持向后兼容：原 `LrcParser.parse()` 返回 `List<LrcLine>` 的签名不变，内部委托给新解析器

### 变更 2：扩展 ServiceLyricSource 接口，支持富歌词

**改造文件：** `app/src/main/java/com/lidesheng/hyperlyric/service/source/ServiceLyricSource.kt`

```kotlin
interface ServiceLyricSource {
    val id: String
    val displayName: String
    
    // 原有方法（保持兼容）
    suspend fun getLyrics(data: SyncData): List<LrcLine>?
    
    // 新增：支持富歌词
    suspend fun getRichLyrics(data: SyncData): EnhanceLrcDocument? {
        return null // 默认实现
    }
}
```

### 变更 3：改造 OnlineLyricSource，输出富歌词

**改造文件：** `app/src/main/java/com/lidesheng/hyperlyric/service/source/OnlineLyricSource.kt`

- 新增 `getRichLyrics()` 实现：调用在线 API 获取原始歌词文本后，使用 `OnlineLyricsMerger` 解析为 `EnhanceLrcDocument`
- 保留原有 `getLyrics()` 作为降级路径

### 变更 4：改造 MetadataLrcLyricSource，使用增强解析器

**改造文件：** `app/src/main/java/com/lidesheng/hyperlyric/service/source/MetadataLrcLyricSource.kt`

- `getRichLyrics()` 使用 `EnhanceLrcParser` 解析，产出带逐字时间戳的 `RichLyricLine`

### 变更 5：改造 LyricInfoLyricSource，输出富歌词

**改造文件：** `app/src/main/java/com/lidesheng/hyperlyric/service/source/LyricInfoLyricSource.kt`

- `getRichLyrics()` 使用 `EnhanceLrcParser` 解析 LyricInfo 数据

### 变更 6：改造 AppLyricSink，消费富歌词

**改造文件：** `app/src/main/java/com/lidesheng/hyperlyric/service/source/AppLyricSink.kt`

- 当 `getRichLyrics()` 返回非空时，将 `RichLyricLine` 列表传递给 `LyricScheduler` 和 `NotificationPresenter`
- 通知渲染器使用 `RichLyricLine` 的 `translation` 字段显示翻译
- 通知渲染器使用 `words` 字段实现逐字高亮

### 变更 7：升级在线歌词获取，支持网易云逐字歌词 API

**改造文件：** `app/src/online/java/com/lidesheng/hyperlyric/online/OnlineLyricTargeter.kt`

- 在获取到歌词后，额外调用网易云 `/song/lyric/v1` 接口获取 YRC 逐字歌词
- 使用 `OnlineLyricsMerger` 合并原始歌词、翻译、逐字歌词

**改造文件：** `app/src/online/java/com/lidesheng/hyperlyric/lyric/LyricProviderImpl.kt`

- 新增 `fetchRichLyrics()` 方法，返回 `EnhanceLrcDocument`
- 磁盘缓存改为存储 JSON 格式（类似 SmartisanMusic 的 `OnlineLyricsDiskCache`），包含四类歌词文本

## 假设与决策

1. **数据模型兼容性**：HyperLyric 已从 lyricon 继承了 `RichLyricLine`、`LyricWord` 等模型，新解析器直接输出这些类型，无需修改渲染层
2. **向后兼容**：保留 `ServiceLyricSource.getLyrics()` 返回 `List<LrcLine>?` 的接口，新增 `getRichLyrics()` 作为可选扩展
3. **渐进式升级**：先在 `MetadataLrcLyricSource` 和 `OnlineLyricSource` 实现富歌词，`LyricScheduler` 可逐步迁移
4. **不改变 Xposed Hook 层**：`root/` 目录下的 Hook 代码不受影响，仅增强 `service/` 层的歌词解析

## 验证步骤

1. 新 `LrcParser` 单元测试：验证标准 LRC、增强 LRC、YRC 格式解析正确性
2. `OnlineLyricsMerger` 单元测试：验证翻译合并、时间戳对齐
3. 集成测试：用 HyperLyric 实际播放一首网易云歌曲，验证超级岛显示逐字同步歌词
4. 回归测试：验证旧的 `LrcLine` 模式仍正常工作