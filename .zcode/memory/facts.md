---
summary: 环境约束、关键路径与可复用命令
created: 2026-10-10 16:59
updated: 2026-10-10 16:59
status: active
---

# 事实

<!-- 原子事实:环境约束、关键路径、可复用命令、版本号。一条一事,带日期前缀。 -->

## 环境

- [2026-10-10] Windows + Git Bash 环境;工作目录含空格(`D:\lr2oraja k`),Bash 里必须加引号。
- [2026-10-10] Bash 工具每次调用可能回到 `D:\lr2oraja k`,命令前要显式 `cd "/d/lr2oraja k/lr2oraja-endlessdream"`。
- [2026-10-10] 读源文件单次最多 400–500 行(用户硬约束),禁止一次读整个文件。

## 路径与位置

- [2026-10-10] 仓库根:`D:\lr2oraja k\lr2oraja-endlessdream`
- [2026-10-10] 只读参考:`D:\iidx2bms-src\iidx2bms`(禁止修改,不要用打包 EXE)
- [2026-10-10] 记忆库:仓库根 `.zcode/memory/`(本会话初始化)
- [2026-10-10] 相关文件:`core/src/bms/player/beatoraja/select/MusicSelector.java`、`core/src/bms/player/beatoraja/iidx/*`、`core/src/bms/player/beatoraja/Config.java`

## 命令与操作

- [2026-10-10] 编译验证:`./gradlew -Dplatform=windows -I /d/jdkfx/fx-init.gradle core:compileJava`(在仓库根执行;Step 6 通过,BUILD SUCCESSFUL)。
- [2026-10-10] 查状态:`git branch --show-current; git log --oneline -3; git status --porcelain`
- [2026-10-10] 提交规范:`feat(iidx): ...` / `chore(memory): ...` 英文 Conventional Commits(与 41e7693b、f30d1319 等一致)。
- [2026-10-10] 工具链:Gradle 9.3.1 daemon,项目 toolchain JDK 17,Python 3.13.7;JavaFX 非 gradle 依赖 ⇒ **每次** gradle 调用都要 `-I /d/jdkfx/fx-init.gradle`。
- [2026-10-10] 打包产物:`dist/lr2oraja-0.8.8-endlessdream-windows-pre0.4.1.jar`,Main-Class `bms.player.beatoraja.MainLoader`。
- [2026-10-10] 配置序列化:libGDX `com.badlogic.gdx.utils.Json` 反射序列化,**JSON 键名 == Java 字段名**,声明顺序==序列化顺序,`write()` 整文件回写 ⇒ 手改磁盘不生效 ⇒ 改字段名要先改 Java 再启动游戏(97eee93b 把 LDJ_* 字段沉到 Config.java 末尾即因此)。
- [2026-10-10] `Mode.BEAT_7K`=SP、`Mode.BEAT_14K`=DP。
