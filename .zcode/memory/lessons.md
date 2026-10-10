---
summary: 踩坑记录:现象、根因与规避方式
created: 2026-10-10 16:59
updated: 2026-10-10 22:40
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
- [2026-10-10] **`D:\iidx2bms-src` 的 git 根 ≠ 应用目录**:git 根是 `D:\iidx2bms-src`,但 tracked 文件全在 `iidx2bms/` 前缀下;桥接 `--project-root`(= `IIDXConversionService` 组装,须含 `conversion/` 与 `search_engine/`)必须指向 `D:\iidx2bms-src\iidx2bms`;规避:切分支/删文件都在 git 根做,但路径校验以应用目录那层为准。
- [2026-10-10] **切分支前 checkout 内有未跟踪的 `__pycache__/`**:`git add -A` 会误提交;规避:切分支或提交前先看 `git status --porcelain`,不要无脑 `-A`。
- [2026-10-10] **删 `ifs_unpack/` 前需确认**:`conversion/conversion.py` 只用 pip 包 `ifstools`,不 import 本地 `ifs_unpack/`(本地那份疑似死重复);规避:删前再 grep 一次 import 闭包。
- [2026-10-10] **别误删运行期必需文件**:`music_data/music_data.json`(桥接运行时读,`cli_convert.py:158` 构造 `SearchEngine`)与三个 exe(`2dx_extract`/`s3p_extract`/`one2bme`)必需,`stagefiles/`(34 图)仅 stagefile 时用;仓库内**没有 ffmpeg**,BGA/预览靠 lr2oraja 侧开关,别去补。
