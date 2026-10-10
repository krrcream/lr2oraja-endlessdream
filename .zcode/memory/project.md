---
summary: 项目目标、结构与进行中任务
created: 2026-10-10 16:59
updated: 2026-10-10 22:40
status: active
---

# 项目状态

## 目标

lr2oraja-endlessdream(beatoraja 的 fork)上集成 beatmania IIDX 支持:把 IIDX 曲库以虚拟文件夹形式接入选曲画面,并在选曲时用 iidx2bms 现场把 IIDX 谱面转换成 BMS 再走原有 readChart 流程播放。

## 结构

- `core/src/bms/player/beatoraja/` — 游戏核心;选曲在 `select/`,IIDX 集成代码在 `iidx/`。
- `core/src/bms/player/beatoraja/select/MusicSelector.java` — 选曲状态机;IIDX 转换的接入点(Step 6 已改)。
- `core/src/bms/player/beatoraja/iidx/` — IIDXChartRef / IIDXChartMeta / IIDXSongProvider / IIDXTempFileManager / IIDXConversionService / IIDXConversionOverlay。
- `core/src/bms/player/beatoraja/select/bar/` — LDJ 目录栏 `IIDXFolderBar`(父栏「LDJ」)、`IIDXStarFolderBar`(☆1–☆12 子栏)。
- `core/src/resources/iidx2bms/cli_convert.py` — lr2oraja **自带**的桥接脚本(jar 资源,运行时解出到临时目录),不依赖 iidx2bms 的 GUI。
- `Config.java` — 全局设置,持有 IIDX 设置项访问器(`LDJ_*`,如 `LDJ_ToolPath`);`PlayerConfig.java` 是玩家个别设置,**没有** IIDX 访问器。
- `D:\iidx2bms-src\iidx2bms` — lr2oraja 的转换后端源码(checkout);下一窗口计划把它从「只读参考」转为「可改」,切到自有分支 `orajaroot` 精简。

## 已交付

- [2026-10-10] IIDX/LDJ 集成主体 | **已完成**:Step 6 `e0147274`、进度浮层 `63000df4`、LDJ 化 `d6c63a1d`/`4f67eabe`/`97eee93b`/`037d7350` | 无卡点
- [2026-10-10] LDJ 需求①进 LDJ 目录不上传 IR 但本地点灯记成绩 | **已完成** `c485ad50`(走 `PlayerResource.fromIIDX` 瞬态标志)
- [2026-10-10] LDJ 需求②难度名区分 SP/DP | **已完成** `ad3e1f35`(`[SP]`/`[DP]` 方括号前缀)
- [2026-10-10] LDJ 需求③LDJ 目录内按 ☆ 分 24 张表 | **已完成** `a1ecc59f`(父栏 `IIDXFolderBar` + 子栏 `IIDXStarFolderBar`,均 `FolderBar` 子类 + `setSortable(false)`)
- [2026-10-10] jar 交付:已 `core:shadowJar` 构建并拷为 `D:\MUG\beatoraja\beatoraja.jar.new`,**用户已自行替换**到 `beatoraja.jar`

## 进行中

- [2026-10-10] iidx2bms 仓库精简为 `orajaroot` 分支 | 待下一窗口**先出计划** | 见 INDEX 活跃任务与快照 handoffs/iidx2bms精简为orajaroot.memo.md
