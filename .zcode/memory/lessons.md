---
summary: 踩坑记录:现象、根因与规避方式
created: 2026-10-10 16:59
updated: 2026-10-10 16:59
status: active
---

# 教训

<!-- - [YYYY-MM-DD] **现象一句话**:根因;规避:做法 -->

- [2026-10-10] **编译报 7 个"找不到符号",全部指向 PlayerConfig 变量 `config`**:IIDX 设置访问器在全局 `Config`,不在 `PlayerConfig`;规避:新增 `private Config iidxConfig() { return main.getPlayerResource().getConfig(); }` 统一取值(`import bms.player.beatoraja.*;` 已覆盖 Config,无需新 import)。
- [2026-10-10] **Edit 报 "File has not been read yet"**:本会话(或多会话)未先 Read 该文件;规避:编辑前先 Read 目标文件(分段,≤400–500 行)。
- [2026-10-10] **疑似 Step 6 代码"消失"**:过期的 Read 快照误导;规避:用 grep 复核真实文件内容,再决定是否改。
