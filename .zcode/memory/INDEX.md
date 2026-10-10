---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-10-10 16:59
updated: 2026-10-10 21:27
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-10-10] lr2oraja-endlessdream IIDX/LDJ 集成(分支 LDJ):Step 6 已完成并提交 e0147274,进度浮层 63000df4、文案 LDJ 化 d6c63a1d | 快照 handoffs/IIDX集成Step6完成.memo.md
- [2026-10-10] LDJ 需求①进 LDJ 目录不上传 IR(本地点灯记成绩) | 方案+锚点已核实,待改代码 | 快照 handoffs/LDJ三项需求方案与代码锚点已核实.memo.md
- [2026-10-10] LDJ 需求②难度名区分 SP/DP(`[SP]`/`[DP]` 标题前缀) | 方案+锚点已核实,待改代码 | 同上快照
- [2026-10-10] LDJ 需求③LDJ 目录内按 ☆ 分 24 张表(SP/DP 各 12) | 方案+锚点已核实,待改代码,需重编译 | 同上快照

## 下一步

- 需求①:在 `PlayerResource` 加显式瞬态标志 `fromIIDX`(镜像 `fromOSU`),MusicSelector 启动转换处置位,`play/BMSPlayer.java` 里 OR 进本地 `forceNoIRSend` 后再 `setForceNoIRSend`;否掉路径判定与 `isIIDXPath` 两路
- 需求②:在 `iidx/IIDXSongProvider.java:150` 的 `song.setTitle(...)` 加 `[SP]`/`[DP]` 前缀,只动 LDJ
- 需求③:把 `IIDXFolderBar`(当前 `extends DirectoryBar` ⇒ BarRenderer 兜底 value=-1 不可见)重塑为 `FolderBar` 子类、标题走合成 `FolderData`,并按 ☆ 拆 SP/DP 各 12 子栏,父栏与每个 ☆ 子栏都 `setSortable(false)`;改完重编译
- 只读参考 D:\iidx2bms-src\iidx2bms 禁止修改、不要用打包 EXE

## 最近快照

- [2026-10-10 21:27] handoffs/LDJ三项需求方案与代码锚点已核实.memo.md — active
- [2026-10-10 16:59] handoffs/IIDX集成Step6完成.memo.md — absorbed
