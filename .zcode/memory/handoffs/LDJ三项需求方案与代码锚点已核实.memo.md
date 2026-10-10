---
summary: LDJ三项需求方案与代码锚点已核实
created: 2026-10-10 21:27
updated: 2026-10-10 21:27
status: active
---

# 交接快照:LDJ 三项需求方案与代码锚点已核实

## Summary

lr2oraja-endlessdream(beatoraja fork,分支 LDJ)的 IIDX 集成主体已完成(Step 6 起全部落地,含 63000df4 进度浮层、07443a0e 类路径修复、d6c63a1d/4f67eabe/97eee93b/037d7350 的 LDJ 化)。本窗口**只做只读侦察**:用户新提三项需求(①进 LDJ 目录不上传 IR 但本地照常点灯记成绩;②难度名区分 SP/DP;③目录内按 ☆ 分 24 张表)。三项方案均已与用户拍板(②③经 AskUserQuestion),**所有代码锚点已逐一对照真实源码复核**(历史快照行号多处失真,勿再信)。窗口内未改任何代码、未提交。

## Done

- 需求①方案定案:在 `PlayerResource` 加瞬态标志 `fromIIDX`(镜像 `BMSModel.fromOSU`),在 `MusicSelector` 启动 IIDX 转换处置位,在 `play/BMSPlayer` 里 OR 进本地 `forceNoIRSend` 后再 `resource.setForceNoIRSend(...)`。
  - 锚点:`play/BMSPlayer.java:219 forceNoIRSend=false`;`:222 if(model.isFromOSU())` `:223 =false`;`:263 =true`(freq trainer);`:480 if(forceNoIRSend)`;`:483` 日志;`:487 resource.setForceNoIRSend(forceNoIRSend)`。
  - 锚点:`PlayerResource.java:136 private boolean forceNoIRSend`;`:665` getter;`:668-669` setter(新 `fromIIDX` 加这里)。
  - 锚点:`BMSModel.java:112 private boolean fromOSU`;`:434` getter;`:437-438` setter ⇒ `fromOSU` 在 **BMSModel** 不在 PlayerResource,由 PlayerResource 在 `loadBMSModel` 里 `decoder instanceof OSUDecoder` 时设置。
  - 锚点:`select/MusicSelector.java:341 iidxProgress=0 / :342 iidxStage=...`(转换启动点,置位处);`:413/:414` 收尾清空(非 IIDX 谱加载时也要复位,防整会话泄漏)。
- 需求②方案定案:`IIDXSongProvider.createSongData` 里给标题加方括号前缀 → `[SP] 曲名` / `[DP] 曲名`,仅 LDJ 生效,不动全局 StringPropertyFactory。锚点:`iidx/IIDXSongProvider.java:146 createSongData`;**`:150 song.setTitle(meta.getTitle())`**(编辑点);`:122/:129 getSongBars`(SP/DP 分入口);`:137 new SongBar(createSongData(...))`。
- 需求③方案定案:在 LDJ 虚拟目录**内部**按 ☆ 分表(SP ☆1–☆12 + DP ☆1–☆12),需改 Java + 重编译;否掉了改全库 `folder/default.json` LEVEL 组。
  - 顺带必修潜在 bug:`IIDXFolderBar extends DirectoryBar` 在 `BarRenderer` 里**匹配不到任何分支 ⇒ value=-1 ⇒ LDJ 根栏当前不可见**;必须让 LDJ 栏继承 **`FolderBar`**(value=1)才可见。
  - 锚点:`select/bar/BarRenderer.java:152` Table/Hash/ExecutableBar→`:153 value=2`;`:154` GradeBar→`:155`;`:156` RandomCourseBar→`:157`;`:158 else if(sd instanceof FolderBar)`→`  `:159 value=1`;`:160` SongBar→`:161`;`:162` SearchWordBar→`:163`;`:164` Command/ContainerBar→`:165`;`:166` FunctionBar→`:168`;`:171/:174/:279 value=-1`(兜底,LDJ 栏当前落这里)。
  - 锚点:`select/bar/FolderBar.java:15 extends DirectoryBar`;`:20` ctor`(selector, FolderData folder, String crc)`;`:26/:30/:35` `getFolderData/getCRC/getTitle` 均 **public final** ⇒ 子类要可见标题必须造**合成 `FolderData`**(如 title="LDJ");`:55 new FolderBar(...)`;`getChildren()` 非 final 可覆写。
  - 锚点:`select/bar/DirectoryBar.java:37 isSortable=true`;`:77 isSortable()`;`:81 setSortable(boolean)`;`:96 abstract getChildren()`;`:98 getChildren(Mode,boolean)`。`BarManager.java:315 isSortable=((DirectoryBar)bar).isSortable()` 会作用于进入栏的**全部子项** ⇒ LDJ 父栏与每个 ☆ 子栏都必须 `setSortable(false)`,否则 ☆ 子项按 ☆1,☆10,☆11,☆12,☆2… 重排。
  - 锚点:`BarManager.java:79 appendFolders` HashMap;`:299/:300` 根列表拼接;`:570-571 setAppendDirectoryBar(key,bar)`(`MusicSelector:260 manager.setAppendDirectoryBar("iidx", iidxBar)`)。
- 复核 `IIDXFolderBar.java` 全文(61 行):`:18 extends DirectoryBar`;字段 `title/spBars/dpBars`;`:39 getTitle()`;`:44 getChildren()` = `isDoublePlay()?dpBars:spBars`;`:54 isDoublePlay()` 读 `selector.main.getPlayerConfig().getMode()==Mode.BEAT_14K`。⇒ ②③ 的重塑目标。
- 已修快照可见性 bug:旧快照缺 `.memo.md` 后缀,meow-recall 的 `handoffs/*.memo.md` 永远扫不到;已 `git mv` 为 `IIDX集成Step6完成.memo.md`。

## Decisions

- 需求①走 `PlayerResource.fromIIDX` 显式瞬态标志;明确否掉两条路:(a) 用路径含 `lr2oraja_iidx` 判"IIDX 谱";(b) 在 BMSPlayer 里查 `isIIDXPath(song.getPath())` —— 缓存命中时 `applyIIDXResult` 会把路径改写成真实 BMS 文件,判不准。
- 需求②用 `[SP]`/`[DP]` 方括号前缀,只改 LDJ 曲目标题;否掉全局 StringPropertyFactory 改动(会波及全库标题)。
- 需求③在 LDJ 目录内按 ☆ 分表,否掉改全库 `folder/default.json` 的 LEVEL 分组。
- LDJ 根栏与 ☆ 子栏一律改为 `FolderBar` 子类(借其 value=1 可见性),标题用合成 `FolderData` 提供(因 getTitle 是 public final)。
- 所有 LDJ 目录栏必须 `setSortable(false)`(DirectoryBar 默认 true)。
- 记忆库快照文件名必须带 `.memo.md`,否则 recall 不可见;概括/`summary`/正文标题三者同源且 ≤20 字。
- 一步一提交、提交前编译通过(沿袭)。

## Pitfalls

- 历史快照行号**多处失真**:如曾把 `BMSPlayer` 记在 beatoraja 根,真实路径是 `play/BMSPlayer.java`;曾把 `fromOSU` 记在 PlayerResource,真实在 BMSModel。改动前一律 grep/Read 复核。
- `FolderBar` 三个 getter 是 `public final`,子类改不了 ⇒ 只能喂合成 `FolderData` 换可见标题。
- `DirectoryBar.isSortable` 默认 true 且作用于整层子项 ⇒ 不关会毁掉 ☆ 排序。
- `IIDXFolderBar` 现 extends DirectoryBar ⇒ BarRenderer 兜底 value=-1 ⇒ **当前 LDJ 根栏不可见**(既是需求③的连带 bug,也是 ②的呈现前提)。
- `bartextupdate`/字体:LDJ 栏标题里的 ☆/★/`[`/`]` 会进 TTF 图集,默认皮肤 VL-Gothic 含 U+2606/U+2605,安全。
- 约束:只读参考 `D:\iidx2bms-src\iidx2bms` 禁止修改,不要用打包 EXE;单次 Read ≤400–500 行。

## Next

- 需求①:在 `PlayerResource` 加 `fromIIDX`(字段+getter/setter),`MusicSelector` 转换启动处置位、非 IIDX 谱加载复位,`play/BMSPlayer.java:487` 前 OR 进 `forceNoIRSend`。单独提交。
- 需求②:`IIDXSongProvider.java:150` 标题加 `[SP]`/`[DP]` 前缀。单独提交。
- 需求③:把 `IIDXFolderBar` 重塑为 `FolderBar` 子类(合成 `FolderData`),新增 ☆ 子栏类(亦 `FolderBar`),SP/DP 各 12 张表,全部 `setSortable(false)`;改完 `./gradlew -Dplatform=windows -I /d/jdkfx/fx-init.gradle core:compileJava` 编译。单独提交。
- 每步先只读侦察→出方案→再改,一步一提交。

## Refs

- 仓库根:`D:\lr2oraja k\lr2oraja-endlessdream`(分支 LDJ)
- 近期提交:`037d7350`(转换落点改由 LDJ_Sources 决定)、`97eee93b`(LDJ 化配置键 + 字段沉底)、`4f67eabe`、`d6c63a1d`、`07443a0e`、`63000df4`(进度浮层)、`e0147274`(MusicSelector 接入)、`23421ae1`(IIDXFolderBar)、`41e7693b`、`f30d1319`、`917bb1c7`、`eb637460`
- 关键文件:`play/BMSPlayer.java`、`PlayerResource.java`、`select/MusicSelector.java`、`iidx/IIDXSongProvider.java`、`select/bar/IIDXFolderBar.java`、`select/bar/FolderBar.java`、`select/bar/DirectoryBar.java`、`select/bar/BarRenderer.java`、`select/BarManager.java`、`Config.java`、`core/dependencies/jbms-parser/.../bms/model/BMSModel.java`
- 编译命令:`./gradlew -Dplatform=windows -I /d/jdkfx/fx-init.gradle core:compileJava`
- 打包产物:`dist/lr2oraja-0.8.8-endlessdream-windows-pre0.4.1.jar`(Main-Class `bms.player.beatoraja.MainLoader`)
- 记忆库分层:`.zcode/memory/project.md`、`facts.md`、`decisions.md`、`lessons.md`
