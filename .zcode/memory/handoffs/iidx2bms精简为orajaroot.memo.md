---
summary: iidx2bms精简为orajaroot
created: 2026-10-10 22:40
updated: 2026-10-10 22:40
status: active
---

# iidx2bms精简为orajaroot

## Summary

- lr2oraja 侧 IIDX/LDJ 集成**收尾杀青**:需求①`c485ad50`、②`ad3e1f35`、③`a1ecc59f` 全部提交,工作区干净 `HEAD=a1ecc59f`(分支 `LDJ`);构建产物已拷为 `D:\MUG\beatoraja\beatoraja.jar.new`,**用户已自行替换** ⇒ jar 侧无需再动。
- **下一窗口任务(用户已指定)**:精简 iidx2bms **仓库本身**。上游 `https://github.com/Glebsin/iidx2bms`,用户 fork `https://github.com/krrcream/iidx2bms`;在 fork 建分支 **`orajaroot`**,删掉 GUI、只留 lr2oraja 真正需要的文件;`main` 保持纯同步上游;本地 `D:\iidx2bms-src\iidx2bms` 检出切到 `orajaroot`;尽量少改 lr2oraja 侧。
- **硬要求:下一窗口先出计划、再动手**(用户原话:"下一个对话的时候先做计划不要再这个对话中做计划")——本窗口**不做计划**。

## Done

- 需求③ `a1ecc59f feat(iidx): split LDJ folder into star-level sub-bars`:改 `select/bar/IIDXFolderBar.java`(+100/-35 区段)、新增 `select/bar/IIDXStarFolderBar.java`(+43)、`select/MusicSelector.java`(1 行)。工作区干净,`HEAD=a1ecc59f`。
- jar 已构建(`core:shadowJar`,67,891,522 B)并交付 `.new`;用户已替换到 `D:\MUG\beatoraja\beatoraja.jar`。
- 只读侦察 iidx2bms 上游**依赖闭包**(下一窗口计划的唯一依据),结论见 Decisions/Pitfalls/Refs。

## Decisions

- 下一窗口顺序:先只读侦察复核闭包 → 出计划给用户 → 批准后建 `orajaroot` 并删文件 → 一步一提交、提交前必编译。
- 分流用分支方案:`main` 纯同步上游,精简只落在 `orajaroot`(用户指定)。
- 建议远程命名沿用 lr2oraja 惯例:`origin`=上游 Glebsin、`fork`=krrcream。**当前 checkout 只有 `origin=Glebsin`,尚无 krrcream remote**,需先 `git remote add`。
- 删 GUI 的依据:lr2oraja **不依赖** iidx2bms 的 GUI/`main.py`,自带桥接脚本 `core/src/resources/iidx2bms/cli_convert.py`(jar 资源,运行时解出),只依赖 `conversion/` 与 `search_engine/`。
- 删除候选:`gui/`、`window/`、`main.py`、`history/`、`remywiki/`、`ifs_unpack/`、`compile.bat`、`icon/`。
- 必须保留:`conversion/`、`search_engine/`、`music_data/music_data.json`、三个 exe、`stagefiles/`。

## Pitfalls

- `D:\iidx2bms-src` 的 **git 根 ≠ 应用目录**:git 根是 `D:\iidx2bms-src`,60 个 tracked 文件**全在 `iidx2bms/` 前缀下**;桥接 `--project-root` 必须指向含 `conversion/` 与 `search_engine/` 的那层,即 `D:\iidx2bms-src\iidx2bms`。
- 旧约束"`D:\iidx2bms-src\iidx2bms` 禁止修改"**已被用户撤销**(本窗口明确要切该 checkout);但"**不要用打包 EXE**"仍有效。
- 删 `ifs_unpack/` 前确认:`conversion.py` 只用 pip 包 `ifstools`,不 import 本地 `ifs_unpack/`(本地那份疑似死重复)。
- `music_data/music_data.json`(bridge 运行时读)与三个 exe(`2dx_extract`/`s3p_extract`/`one2bme`)是运行期必需,别误删;`stagefiles/`(34 图)仅 stagefile 时用。
- 仓库内**没有 ffmpeg**(全仓 `find` 无命中);BGA/预览靠 lr2oraja 侧开关处理,别去补 ffmpeg。
- 切换分支前 checkout 内有未跟踪的 `__pycache__/`;别 `git add -A` 误提交。

## Next

- **第一步:做计划(不写代码)**。覆盖:fork remote 添加、`orajaroot` 创建、删除清单与提交切分、本地 checkout 切分支、以及"lr2oraja 侧需要几处改动"的核对。
- 复核点:再 grep 一遍 `conversion.py` / `search_engine.py` 的 import 闭包(实测仅 stdlib + `ifstools` + `search_engine.game_names`),并确认 `cli_convert.py` 未引用被删目录。
- 核对 lr2oraja 改动面:`Config.java:229 LDJ_ToolPath = ""`(默认空,路径由用户在 config.json 配置)⇒ checkout 路径不变则**零 lr2oraja 改动**。

## Refs

- iidx2bms 实测:`HEAD=33c0766 "Update README.md"`,branch `main`,tracked=60,remote 仅 `origin=https://github.com/Glebsin/iidx2bms.git`(fetch+push),另有 `origin/main`、`origin/resources`。
- 桥接:`core/src/resources/iidx2bms/cli_convert.py`(9833 B);锚点 `IIDXTempFileManager.java:37 BRIDGE_RESOURCE`、`:38 BRIDGE_FILE_NAME`、`:92-95` 解出;`IIDXConversionService.java` 组参 `:215-233`、`ProcessBuilder :236`、python 探测 `:444-470`(`.venv/Scripts/python.exe`→`.venv/bin/python`→`py -3`→`python`→`python3`,以 `-c "import ifstools"` 验证;仓库内**无 `.venv`**)。
- 桥接约束:`cli_convert.py:143` 要求 `project_root/conversion/conversion.py` 存在;`:150 sys.path.insert(0, project_root)`;`:158 SearchEngine(project_root / "music_data" / "music_data.json")`。
- lr2oraja IIDX 代码:`iidx/` 下 IIDXChartRef / IIDXSongProvider / IIDXTempFileManager / IIDXConversionService / IIDXChartMeta / IIDXConversionOverlay;目录栏在 `select/bar/IIDXFolderBar.java`、`select/bar/IIDXStarFolderBar.java`。
- 相关提交:`c485ad50`(①)、`ad3e1f35`(②)、`a1ecc59f`(③)。
