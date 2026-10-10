---
summary: IIDX集成Step6完成
created: 2026-10-10 16:59
updated: 2026-10-10 21:27
status: absorbed
---

# 交接快照:IIDX集成Step6完成

## Summary

lr2oraja-endlessdream(beatoraja fork)的 IIDX 集成已完成 Step 6:在 `MusicSelector.java` 里接入 iidx2bms 现场转换,谱面用虚拟伪路径 `iidx://<songId>/<difficulty>` 表示,选曲时启动后台 Python 转换线程、render() 轮询完成后在 UI 线程续跑 readChart。工作区干净,编译通过,已单独提交 `e0147274`(分支 LDJ)。下一步是 Step 7(IIDXConversionOverlay 进度浮层)与 Step 8(整包构建 + 真机联测)。

## Done

- 补齐 `setupIIDXFolder()`:由全局 Config 的 iidx2bmsPath 构造 IIDXSongProvider / IIDXTempFileManager / IIDXConversionService / IIDXFolderBar,注册 "iidx" 追加目录栏;环境不可用则只 warn 不建文件夹。
- `readChart()` 在 `resource.clear();` 之前拦截 `IIDXSongProvider.isIIDXPath(song.getPath())`,命中缓存直接重写路径,否则调 `startIIDXConversion()`。
- `startIIDXConversion()`:空依赖守卫、并发转换拒绝提示、先试 `readCachedResult()`,再建 session 目录、起守护线程 `iidx2bms-convert`。
- `runIIDXConversion()`:调用转换服务、`promoteToCache` + `enforceCacheLimit(cacheMaxSizeMB)`、把 Result 写进 volatile `iidxResult`,`iidxStage`/`iidxProgress` 更新进度。
- render() 轮询 `iidxThread != null && !iidxThread.isAlive()`,置空并调 `onIIDXConversionFinished()`(成功路径 `play = mode; readChart(song, bar); play = null;`)。
- 新增 `private Config iidxConfig()` 统一从 `main.getPlayerResource().getConfig()` 取 IIDX 设置;缓存上限在 UI 线程取值后作为参数传入 worker。
- 编译通过:`./gradlew -Dplatform=windows -I /d/jdkfx/fx-init.gradle core:compileJava` → BUILD SUCCESSFUL in 6s。
- 提交 `e0147274 feat(iidx): wire on-the-fly IIDX conversion into MusicSelector`(单文件 +233,8 个 hunk,全部 IIDX 相关)。

## Decisions

- IIDX 设置读全局 `Config`(`main.getPlayerResource().getConfig()`),不给 `PlayerConfig` 加访问器。
- 转换线程用 `Thread.isAlive()` 轮询判完成;禁用 `cancel()`,不用 `join()`(不阻塞渲染线程)。
- worker 写的 `iidxResult`/`iidxError` 用 volatile(`isAlive()==false` 无 happens-before)。
- 缓存上限在 UI 线程取值传参,避免 worker 读共享可变全局配置。
- 完成路径 `play = mode; readChart(...); play = null;`,并把 render() 轮询块放在 `if (play != null)` 之前,防同帧双消费。
- 一步一提交,提交前必须编译通过。

## Pitfalls

- 编译期坑:`MusicSelector` 的字段 `config` 是 `PlayerConfig`,没有 `getIidx2bmsPath()` 等访问器 —— 报 7 个"找不到符号";必须走全局 `Config`。
- 内存可见性坑:`isAlive()==false` **不**建立 happens-before,worker 写入结果字段必须 volatile,否则 UI 线程可能读到 null/旧值。
- 帧序坑:轮询块若放在 `if (play != null)` 之后,成功路径会被同帧的 play 块二次消费。
- 缓存路径坑:manifest 里的绝对 `file` 在缓存搬移后会失效,必须用 `Chart.resolve(Path baseDir)` 重算。
- 工具坑:编辑前必须 Read 文件,否则 Edit 报 "File has not been read yet";Bash 每次可能回到 `D:\lr2oraja k`,命令要显式 cd。
- 约束:只读参考 `D:\iidx2bms-src\iidx2bms` 禁止修改,调试不要用打包 EXE;单次 Read ≤400–500 行。

## Next

- Step 7:实现 `IIDXConversionOverlay` 进度浮层 —— 读取 `MusicSelector` 已写好但尚未消费的 `iidxProgress` / `iidxStage` 字段渲染转换进度;单独一次提交(`feat(iidx): ...`),提交前跑上面那条 compileJava 编译。
- Step 8:`./gradlew -Dplatform=windows` 整包构建 + 真机联测 IIDX 转换全流程(选曲 → 转换 → 播放 → 缓存命中)。
- 之后:如需继续历史欠账,可在窗口收尾再跑一次 `/handoff`。

## Refs

- 仓库根:`D:\lr2oraja k\lr2oraja-endlessdream`(分支 LDJ)
- 本次提交:`e0147274`;前序:`23421ae1`(IIDXFolderBar)、`41e7693b`(虚拟谱面模型 + music_data.json provider)
- 关键文件:`core/src/bms/player/beatoraja/select/MusicSelector.java`、`iidx/IIDXChartRef.java`、`iidx/IIDXConversionService.java`、`iidx/IIDXSongProvider.java`、`iidx/IIDXTempFileManager.java`、`iidx/IIDXFolderBar.java`、`Config.java`
- 编译命令:`./gradlew -Dplatform=windows -I /d/jdkfx/fx-init.gradle core:compileJava`
- 记忆库分层:`.zcode/memory/project.md`、`facts.md`、`decisions.md`、`lessons.md`
