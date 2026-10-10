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
- [2026-10-10] **历史交接快照行号多处失真**(如 BMSPlayer 曾记在 beatoraja 根、真实为 `play/BMSPlayer.java`;fromOSU 曾记在 PlayerResource、真实在 BMSModel):根因是跨窗口推断未复核;规避:改任何锚点前先 grep/Read 真实源码。
- [2026-10-10] **handoff 快照文件名缺 `.memo.md` 后缀则 meow-recall 永远扫不到**:recall 只 glob `handoffs/*.memo.md`;规避:新建/重命名快照必须带后缀,且文件名概括 == `summary` == 正文标题,≤20 字。
- [2026-10-10] **`IIDXFolderBar extends DirectoryBar` 在 BarRenderer 落兜底 value=-1 ⇒ LDJ 根栏不可见**:BarRenderer 只认 Table/Hash/Executable/Grade/RandomCourse/Folder/Song/SearchWord/Command/Container/Function;规避:自定义目录栏要继承 `FolderBar` 才能拿到 value=1。
- [2026-10-10] **`BarManager.java:315` 用进入栏的 `isSortable` 排序其全部子项**:☆ 子栏若不 `setSortable(false)`,子项会按字符串排成 ☆1,☆10,☆11,☆12,☆2…;规避:LDJ 父栏和每个 ☆ 子栏都关掉。
