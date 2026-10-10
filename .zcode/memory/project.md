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

- [2026-10-10] IIDX 集成 Step 6(在 MusicSelector 里接上现场转换) | 已完成并提交 e0147274 | 无卡点
- [2026-10-10] IIDX 集成 Step 7(IIDXConversionOverlay 进度浮层) | 未开始 | 无
- [2026-10-10] IIDX 集成 Step 8(整包构建 + 真机联测) | 未开始 | 无
