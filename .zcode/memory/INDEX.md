---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-10-10 16:59
updated: 2026-10-10 22:40
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-10-10] iidx2bms 仓库精简分流(下一窗口做):上游 `Glebsin/iidx2bms`,用户 fork `krrcream/iidx2bms`;在 fork 建分支 **`orajaroot`** 删 GUI、只留 lr2oraja 需要的文件,`main` 纯同步上游;本地 `D:\iidx2bms-src\iidx2bms` 切到 `orajaroot`;尽量少改 lr2oraja 侧 | **先出计划、再动手** | 快照 handoffs/iidx2bms精简为orajaroot.memo.md

## 下一步

- **先只读侦察复核** iidx2bms 依赖闭包(`conversion/` + `search_engine/` + `music_data/music_data.json` + 三个 exe),再出计划给用户批准,然后才动手。
- 计划要点:添加 krrcream remote、建 `orajaroot`、删除清单(`gui/` `window/` `main.py` `history/` `remywiki/` `ifs_unpack/` `compile.bat` `icon/`)、一步一提交、本地 checkout 切分支。
- 核对 lr2oraja 侧改动面:`Config.java:229 LDJ_ToolPath` 默认 `""`,checkout 路径不变则**零改动**;工作方式仍为 先侦察→出计划→批准→改→一步一提交→提交前必编译(编译须 `-I /d/jdkfx/fx-init.gradle`)。

## 最近快照

- [2026-10-10 22:40] handoffs/iidx2bms精简为orajaroot.memo.md — active
- [2026-10-10 22:07] handoffs/LDJ需求①②已提交-③待实现.memo.md — absorbed
- [2026-10-10 21:27] handoffs/LDJ三项需求方案与代码锚点已核实.memo.md — absorbed
- [2026-10-10 16:59] handoffs/IIDX集成Step6完成.memo.md — absorbed
