---
summary: 已拍板的决策与理由,append-only
created: 2026-10-10 16:59
updated: 2026-10-10 22:40
status: active
---

# 决策

<!-- - [YYYY-MM-DD] **决策一句话** — 理由一句话(可含否掉的备选) -->

- [2026-10-10] **IIDX 设置从全局 Config 读,不新建 PlayerConfig 访问器** — IIDX 设置项(路径、缓存、BGA/预览开关)定义在 `Config.java`,经 `main.getPlayerResource().getConfig()` 取得;music selector 的 `config` 字段是 PlayerConfig,没有这些访问器。
- [2026-10-10] **转换跑在守护线程上,用 `Thread.isAlive()` 轮询判定完成** — 用户明确要求;`cancel()` 禁用,`join()` 不用(不能阻塞渲染线程)。
- [2026-10-10] **缓存上限在 UI 线程取值后以参数传给转换线程** — 避免工作线程读共享可变全局设置,规避可见性/竞态问题。
- [2026-10-10] **worker 写入的 `iidxResult`/`iidxError` 声明为 volatile** — `isAlive()==false` 不建立 happens-before(不同于 join),故需 volatile 保证可见性。
- [2026-10-10] **完成后在 UI 线程 `play = mode; readChart(...); play = null;`** — 让 render() 轮询块位于 `if (play != null)` 之前,避免同一帧被消费两次。
- [2026-10-10] **谱面用虚拟伪路径 `iidx://<songId>/<difficulty>`** — 在 `resource.clear();` 之前拦截,命中缓存直接重写 SongData 路径,否则启动转换。
- [2026-10-10] **一步一提交,提交前必须编译通过** — 用户工作流要求。
- [2026-10-10] **需求①(进 LDJ 不上传 IR)走 `PlayerResource.fromIIDX` 显式瞬态标志** — 镜像 `BMSModel.fromOSU`,在 MusicSelector 启动转换处置位、在 `play/BMSPlayer` 里 OR 进本地 `forceNoIRSend` 后再 `setForceNoIRSend`;否掉两条路:(a) 用路径含 `lr2oraja_iidx` 判 IIDX 谱,(b) 在 BMSPlayer 里查 `isIIDXPath(song.getPath())`(缓存命中时 `applyIIDXResult` 会把路径改写成真实 BMS 文件,判不准)。
- [2026-10-10] **需求②用 `[SP]`/`[DP]` 方括号前缀改 LDJ 曲目标题** — 只动 `IIDXSongProvider.createSongData` 的 `song.setTitle`;否掉改全局 StringPropertyFactory(会波及全库标题)。
- [2026-10-10] **需求③在 LDJ 虚拟目录内按 ☆ 分表** — 只改 LDJ;否掉改全库 `folder/default.json` 的 LEVEL 分组。
- [2026-10-10] **LDJ 目录栏一律改继承 `FolderBar`(借 value=1 可见性),标题用合成 `FolderData` 提供** — 因 `FolderBar.getTitle()` 是 `public final` 改不了;`DirectoryBar.isSortable` 默认 true 且作用于整层子项 ⇒ LDJ 父栏与每个 ☆ 子栏都要 `setSortable(false)`。
- [2026-10-10] **iidx2bms 分流靠分支:上游同步留 `main`,精简只落 `orajaroot`** — 用户指定;`main` 保持纯同步上游(Glebsin),自有精简分支 `orajaroot` 承载 GUI 剥离后的最小文件集。
- [2026-10-10] **iidx2bms remote 命名沿用 lr2oraja 惯例:`origin`=上游 Glebsin、`fork`=krrcream** — 与 lr2oraja 仓库一致,便于记忆与脚本统一;当前 checkout 只有 `origin=Glebsin`,需先 `git remote add` 补 krrcream。
- [2026-10-10] **删除 GUI 的依据:lr2oraja 不依赖 iidx2bms 的 GUI/`main.py`** — lr2oraja 自带桥接 `core/src/resources/iidx2bms/cli_convert.py`(jar 资源,运行时解出),只 import `conversion.conversion.convert_chart` 与 `search_engine.search_engine.SearchEngine` ⇒ `gui/`、`window/`、`main.py` 属删除候选。
