---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-10-10 16:59
updated: 2026-10-10 21:40
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-10-10] lr2oraja-endlessdream IIDX/LDJ 集成(分支 LDJ):Step 6 已完成并提交 e0147274,进度浮层 63000df4、文案 LDJ 化 d6c63a1d | 快照 handoffs/IIDX集成Step6完成.memo.md
- [2026-10-10] LDJ 需求③LDJ 目录内按 ☆ 分 24 张表(SP/DP 各 12,进 LDJ 后才可见) | 方案+锚点已核实,**待改代码并重编译** | 快照 handoffs/LDJ需求①②已提交-③待实现.memo.md

## 下一步

- 需求③:把 `select/bar/IIDXFolderBar.java` 由 `extends DirectoryBar`(当前 `BarRenderer` 兜底 value=-1 ⇒ 不可见)重塑为 `FolderBar` 子类,标题走合成 `FolderData`("LDJ");新增 ☆ 桶子栏类(建议 `IIDXStarFolderBar extends FolderBar`),SP/DP 各 12 桶、标题 `SP☆1…SP☆12`/`DP☆1…DP☆12`;分桶键 = `IIDXChartMeta.getLevel(IIDXChartRef.Difficulty)`;父栏与每个 ☆ 桶都 `setSortable(false)` 并 override `updateFolderStatus()` 为空(否则 BarManager:797-801 会跑无效 SQLite 查询);改 `select/MusicSelector.java:259` 的构造;改完从仓库根跑 `./gradlew -Dplatform=windows -I /d/jdkfx/fx-init.gradle core:compileJava`
- 先与用户确认:空 ☆ 桶是否显示(推荐只建非空桶,避免点了没反应的死桶;正常仍是 24 个)
- 工作方式:先只读侦察 → 出计划给用户 → 批准后再改 → 一步一提交 → 提交前必编译
- 只读参考 D:\iidx2bms-src\iidx2bms 禁止修改、不要用打包 EXE(连调试也不用);单次 Read ≤400–500 行

## 最近快照

- [2026-10-10 21:40] handoffs/LDJ需求①②已提交-③待实现.memo.md — active
- [2026-10-10 21:27] handoffs/LDJ三项需求方案与代码锚点已核实.memo.md — absorbed
- [2026-10-10 16:59] handoffs/IIDX集成Step6完成.memo.md — absorbed
