---
summary: LDJ需求①②已提交,③待实现
created: 2026-10-10 21:40
updated: 2026-10-10 22:40
status: absorbed
---

# 交接快照:LDJ 需求①②已提交、需求③待实现

## Summary

lr2oraja-endlessdream(beatoraja fork,分支 **LDJ**)的 IIDX/LDJ 三项需求中,**①(进 LDJ 目录不上传 IR、本地照常点灯记成绩)与 ②(难度名区分 SP/DP)已在源码中实现并各自单独提交**;本窗口只做**只读侦察 + 交接**,未改任何业务代码。侦察已收口 ③ 的最后一个未知项:☆ 分桶键 = `IIDXChartMeta.getLevel(difficulty)`(且 `hasChart() == getLevel() > 0` ⇒ 每个 `SongBar` 的 level 必 ≥1,天然可分入 ☆1–☆12)。**③(LDJ 目录内按 ☆ 分 24 桶)尚未动代码**,完整设计与全部锚点已核实并写在下方,交新窗口按"先给计划、批准后再改、一步一提交、提交前必编译"执行。工作区干净(`git status --short` 无输出),无需任何源码提交。

## Done

- **需求① 已实现并提交** `c485ad50 feat(iidx): skip IR submission for LDJ charts while keeping local scores`。实际锚点(本窗口对照真实源码):
  - `core/src/bms/player/beatoraja/PlayerResource.java:136 private boolean forceNoIRSend;`;`:140 private boolean fromIIDX;`(注释说明"本次读入的谱面是否 LDJ 由来,读谱时设置、clear() 时解除");`clear()` 内 `:168 fromIIDX = false;`。
  - `core/src/bms/player/beatoraja/play/BMSPlayer.java:481 forceNoIRSend |= resource.isFromIIDX();`(注释"LDJ(IIDX)由来の譜面はローカルに成績を残すがIRへは送信しない");`:489 resource.setForceNoIRSend(forceNoIRSend);`。既有锚点仍在:`:219 forceNoIRSend=false`、`:222/:223 if(model.isFromOSU())`、`:263 =true`(freq trainer)。
- **需求② 已实现并提交** `ad3e1f35 feat(iidx): prefix LDJ song titles with [SP]/[DP]`。锚点:`core/src/bms/player/beatoraja/iidx/IIDXSongProvider.java:151 song.setTitle("[" + (difficulty.isDoublePlay() ? "DP" : "SP") + "] " + meta.getTitle());`(注释"SP/DPは難易度名からも判別できるようにするが、選曲・リザルトでは楽曲名しか出ないため接頭辞で補う")。
- **需求③ 未实现**。现状仍是 `core/src/bms/player/beatoraja/select/bar/IIDXFolderBar.java:18 extends DirectoryBar` ⇒ `BarRenderer` 兜底 `value = -1` ⇒ **LDJ 根栏当前不可见**(既是 ③ 的连带 bug,也是 ② 的呈现前提)。
- **③ 侦察收口(本窗口新结论)**:
  - 分桶键 = `IIDXChartMeta.getLevel(Difficulty)`(1..12);`iidx/IIDXChartMeta.java:118-119 hasChart(difficulty) { return getLevel(difficulty) > 0; }` ⇒ `IIDXSongProvider.getSongBars(boolean)` 产出的每个 `SongBar` 都 level ≥ 1,**无 level-0 散项**(仅需对越界值做防御性钳制)。
  - `Difficulty` **不是顶层类**,是嵌套枚举 `bms.player.beatoraja.iidx.IIDXChartRef.Difficulty`(`IIDXChartMeta.java:3` 导入),10 个值 SPB/SPN/SPH/SPA/SPL + DPB/DPN/DPH/DPA/DPL,带 `isDoublePlay()`/`getBmsDifficulty()`/`getMode()`。实现时按 `IIDXChartRef.Difficulty` 引用(读 `iidx/Difficulty.java` 会报 File does not exist)。
  - `IIDXSongProvider.getSongBars(Mode)` = `getSongBars(mode == Mode.BEAT_14K)`;`getSongBars(boolean doublePlay)` 遍历 `charts × Difficulty.values()`,跳过非本侧或 `!meta.hasChart(difficulty)`,产出 `new SongBar(createSongData(meta, difficulty))`。
- 记忆库 `INDEX.md`(updated 21:27)**对 ①② 已过时**(仍写"待改代码");本快照已按实际 git 状态改写活跃任务/下一步。

## Decisions

- ① 走 `PlayerResource.fromIIDX` **显式瞬态标志**;明确否掉两条路:(a) 用路径含 `lr2oraja_iidx` 判"IIDX 谱";(b) 在 `BMSPlayer` 里查 `isIIDXPath(song.getPath())` —— 缓存命中时 `applyIIDXResult` 会把路径改写成真实 BMS 文件,判不准。
- ② 用 `[SP]`/`[DP]` 方括号前缀,只改 LDJ 曲目标题;否掉全局 `StringPropertyFactory`(会波及全库标题)。
- ③ **在 LDJ 目录内部按 ☆ 分子目录**(SP ☆1–12 + DP ☆1–12,**进入 LDJ 后才看到 24 个子目录**,不把子目录提到外层根级);否掉改全库 `folder/default.json` 的 LEVEL 分组。布局按用户拍板 = **"24 个同屏"**。
- LDJ 根栏与每个 ☆ 子栏**一律改为 `FolderBar` 子类**(借其 `value = 1` 拿可见性;`BarRenderer` 的 `FolderBar` 分支先于 `SongBar` 判定),标题由**合成 `FolderData`** 提供(`FolderBar.getTitle()/getFolderData()/getCRC()` 均 `public final`)。所有 LDJ 目录栏 **`setSortable(false)`**(`DirectoryBar.isSortable` 默认 true,且 `BarManager` 下钻时用**父**栏的 isSortable 作用于整层子项)。
- 快照文件名必须带 `.memo.md`,否则 recall 扫不到;`summary`/正文标题同源且 ≤20 字。
- 工作方式(沿袭):先只读侦察 → 给计划 → 批准后再改 → 一步一提交 → 提交前必编译。

## Pitfalls

- **必须先编译再提交**:`./gradlew -Dplatform=windows -I /d/jdkfx/fx-init.gradle core:compileJava`,从仓库根执行。JavaFX **不是** gradle 依赖,漏掉 `-I /d/jdkfx/fx-init.gradle` 必失败。
- **`FolderBar` 子类永不被移除**:`BarManager.updateBar` 的删除过滤(332–341)只剔 `SongBar && !existsSong()` 与 `GradeBar && !existsAllSongs()` ⇒ **空的 ☆ 桶也会渲染**;进入空桶时模式试探块被 `if (l.size > 0)` 跳过、目录不入栈 ⇒ **点击空桶无反应(死桶)**。故建议**只建非空桶**(正常情况仍是 24 个);若要求恒定 24 个则全建。
- **模式试探循环(343–364)**:仅剔除 `song.getMode() != 0 && != mode.id` 的 `SongBar`,并 `config.setMode(mode)` ⇒ 进 DP 桶会自动切模式(既有行为,无回归)。
- **必须 override `updateFolderStatus()`**:`BarManager:797-801` 在 `config.isFolderlamp()` 下对当前列表里每个 `DirectoryBar` 调用 `if (bar instanceof DirectoryBar) ((DirectoryBar) bar).updateFolderStatus();`;继承的 `FolderBar` 实现会跑一次无效的 SQLite `getSongDatas("parent", crc)`(还会 `SongUtils.crc32` 路径)⇒ LDJ 根栏与 24 个 ☆ 桶都要安全空实现。
- `BarRenderer:196` 消费 `((FolderBar) ba.sd).getFolderData()`(在新曲/目录标记逻辑 179–196 内)⇒ 合成 `FolderData` 必须**非 null 且稳定**(`getFolderData()` 是 final 返回构造传入引用)。
- **历史快照行号多处失真**(曾把 `BMSPlayer` 记在根、把 `fromOSU` 记在 PlayerResource)⇒ 改动前一律 grep/Read 复核。
- 约束:只读参考 `D:\iidx2bms-src\iidx2bms` **禁止修改**,不要用打包 EXE(连调试也不用);单次 Read ≤400–500 行;Git Bash 每次调用 cwd 重置回 `D:\lr2oraja k`,不要把 `/d/...` 路径喂给 Windows Python。

## Next

- **只实现需求③**(①② 已提交,不要再动)。步骤:
  1. 只读复核:`iidx/IIDXChartMeta.java`(getLevel/hasChart)、`iidx/IIDXSongProvider.java`(createSongData/getSongBars)、`select/bar/IIDXFolderBar.java` 全文、`select/bar/FolderBar.java`、`select/bar/DirectoryBar.java`、`select/MusicSelector.java:237-262 setupIIDXFolder`、`select/BarManager.java:299-399/570-571/797-801`。
  2. 先出计划给用户,批准后再改。
  3. `select/bar/IIDXFolderBar.java`:改 `extends FolderBar`(删本地 `title` 字段,标题走合成 `FolderData` "LDJ"),`getChildren()` 返回 24 个 ☆ 桶栏,override `updateFolderStatus()` 为空实现,`setSortable(false)`。
  4. 新增 ☆ 桶子栏类(建议 `select/bar/IIDXStarFolderBar.java extends FolderBar`):同样合成 `FolderData` + `setSortable(false)` + 安全 `updateFolderStatus()`;每桶持该 ☆ 的 `SongBar[]`,标题建议 `SP☆1…SP☆12` / `DP☆1…DP☆12`(与 ② 的 `[SP]/[DP]` 前缀共同保证 SP/DP 可辨)。
  5. 桶的构造:`IIDXSongProvider` 加按 `getLevel` 分桶的产出方法,或在 `MusicSelector:259` 构造处按 `((SongBar) b).getSongData().getLevel()` 分桶(1..12,越界钳制/忽略)。
  6. `select/MusicSelector.java:259` 由 `new IIDXFolderBar(this, "LDJ", provider.getSongBars(false), provider.getSongBars(true))` 改为构造 LDJ 根 + 24 ☆ 桶;`:260 manager.setAppendDirectoryBar("iidx", iidxBar)` 不变。
  7. `./gradlew -Dplatform=windows -I /d/jdkfx/fx-init.gradle core:compileJava` 通过后单独提交(`feat(iidx): ...`)。
- **需新窗口先与用户确认的一个决策点**:空 ☆ 桶是否显示(推荐"只显示非空桶",避免点了没反应的死桶;正常情况仍是 24 个)。

## Refs

- 仓库根:`D:\lr2oraja k\lr2oraja-endlessdream`(分支 LDJ;`fork`=krrcream 推送远端,`origin`=seraxis 上游)
- 本窗口核实的提交:`ad3e1f35`(②[SP]/[DP] 前缀)、`c485ad50`(①跳过 IR)、`20c86a78`(记忆)、`037d7350`、`97eee93b`、`4f67eabe`、`d6c63a1d`、`07443a0e`、`63000df4`(进度浮层)、`e0147274`(MusicSelector 接入)
- 关键文件:`core/src/bms/player/beatoraja/play/BMSPlayer.java`、`PlayerResource.java`、`select/MusicSelector.java`、`iidx/IIDXSongProvider.java`、`iidx/IIDXChartMeta.java`、`iidx/IIDXChartRef.java`、`song/FolderData.java`(无参 POJO,`new FolderData(); setTitle("LDJ")` 即可;`FolderData.EMPTY` 存在)、`select/bar/IIDXFolderBar.java`、`select/bar/FolderBar.java`、`select/bar/DirectoryBar.java`、`select/bar/BarRenderer.java`、`select/BarManager.java`
- 编译命令:`./gradlew -Dplatform=windows -I /d/jdkfx/fx-init.gradle core:compileJava`
- 打包产物:`dist/lr2oraja-0.8.8-endlessdream-windows-pre0.4.1.jar`(Main-Class `bms.player.beatoraja.MainLoader`)
- 记忆库分层:`.zcode/memory/project.md`、`facts.md`、`decisions.md`、`lessons.md`;前端快照 `handoffs/LDJ三项需求方案与代码锚点已核实.memo.md`(含 ③ 更细的 BarRenderer/FolderBar/DirectoryBar 行号锚点)
