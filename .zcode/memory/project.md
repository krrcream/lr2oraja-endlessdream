---
summary: 项目目标、结构与进行中任务
created: 2026-10-10 16:59
updated: 2026-10-10 16:59
status: active
---

# 项目状态

## 目标

lr2oraja-endlessdream(beatoraja 的 fork)上集成 beatmania IIDX 支持:把 IIDX 曲库以虚拟文件夹形式接入选曲画面,并在选曲时用 iidx2bms 现场把 IIDX 谱面转换成 BMS 再走原有 readChart 流程播放。

## 结构

- `core/src/bms/player/beatoraja/` — 游戏核心;选曲在 `select/`,IIDX 集成代码在 `iidx/`。
- `core/src/bms/player/beatoraja/select/MusicSelector.java` — 选曲状态机;IIDX 转换的接入点(Step 6 已改)。
- `core/src/bms/player/beatoraja/iidx/` — IIDXChartRef / IIDXSongProvider / IIDXTempFileManager / IIDXConversionService / IIDXFolderBar。
- `Config.java` — 全局设置,持有 IIDX 设置项访问器(iidx2bmsPath 等);`PlayerConfig.java` 是玩家个别设置,**没有** IIDX 访问器。
- `D:\iidx2bms-src\iidx2bms` — 只读参考实现:Python 侧 cli_convert.py 是转换入口,**禁止修改**,调试也不要跑打包好的 EXE。

## 进行中

- [2026-10-10] IIDX/LDJ 集成主体 | 已完成(Step 6 e0147274 起全部落地,含进度浮层 63000df4、LDJ 化 d6c63a1d/4f67eabe/97eee93b/037d7350) | 无卡点
- [2026-10-10] LDJ 需求①进 LDJ 目录不上传 IR 但本地点灯记成绩 | 方案定案 + 锚点已核实,待改代码 | 走 `PlayerResource.fromIIDX` OR 进 `play/BMSPlayer.java:487` 前的 `forceNoIRSend`
- [2026-10-10] LDJ 需求②难度名区分 SP/DP | 方案定案 + 锚点已核实,待改代码 | 在 `iidx/IIDXSongProvider.java:150` 标题加 `[SP]`/`[DP]` 前缀
- [2026-10-10] LDJ 需求③目录内按 ☆ 分 24 张表(SP/DP 各 12) | 方案定案 + 锚点已核实,待改代码 | 需把 `IIDXFolderBar` 重塑为 `FolderBar` 子类(当前 value=-1 不可见)并 `setSortable(false)`,要重编译
