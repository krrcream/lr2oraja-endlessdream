# LR2oraja \~Endless Dream\~ 个人修改版

简体中文・[English](README.md)

本仓库是 [LR2oraja \~Endless Dream\~](https://github.com/seraxis/lr2oraja-endlessdream) 的个人定制分支，在完整保留上游全部功能与特性的基础上，整合了 backbeat 支持，并新增了 LDJ 游戏内歌单、训练工具增强等自用功能。

>
> **项目说明**：这是个人自用维护分支。

---

## 重要声明与版权说明

1. 本仓库仅包含游戏引擎的修改代码，不包含任何音乐、谱面、影像等游戏数据文件，也不提供任何资源获取渠道与下载方式。
2. 本仓库不提供任何破解、绕过或解密保护与加密的方法、工具或相关信息。
3. LDJ 功能仅对用户本地已有的文件进行格式转换与读取，所有资源文件需由用户自行准备。
4. 请确保您所使用的一切资源均已获得合法授权，仅可用于个人学习与研究用途。
5. 本项目不对任何第三方资源的版权问题负责，请勿将本程序用于任何侵权用途。

---

## 新增特性

### BMS SP-DP 转换器

可以直接在游戏中把 SP 谱拆分成 DP 谱，也能把 DP 谱压缩成 SP 谱游玩。

### LDJ 游戏内歌单 <sub>（不在dev分支内，请用LDJPlus分支，LDJ分支已不更新）</sub>

本功能以独立工具 [iidx2bms（个人分支）](https://github.com/krrcream/iidx2bms) 作为格式转换后端，其上游项目为 [Glebsin/iidx2bms](https://github.com/Glebsin/iidx2bms)，可将用户本地已有的对应格式谱面实时转换后，集成到游戏选曲界面中直接浏览与游玩。

对应 iidx2bms 的两个分支：`orajaroot` 为常规版（转换时落盘音频中间产物）；`orajaroot-plus` 为不落盘音频中间产物版。
- 选曲界面新增独立的 `LDJ` 分类目录
- 支持自定义难度表（Table）分类，可导入外部难度表文件
- 首次选择曲目时自动触发格式转换，完成后即可正常游玩
- 支持本地转换缓存，避免重复转换占用系统资源
- 游玩成绩独立存储于 `ldjscore.db`，不与原生谱面的 `score.db` 混用
- 默认不上传 IR，避免影响原生成绩记录

>
> 注意：iidx2bms 为独立的格式转换工具项目，本仓库不包含其代码与任何数据文件，需自行单独下载配置。

### trainer_plus 增强训练菜单

在内置 Mod 菜单（快捷键 `F5` / `Insert`）中追加了以下训练功能：

- 模拟IIDX血条（Gauge）与判定（Judge）
- 频率训练增强：支持 0.1% 精细调速、练习模式 0.5% 步长、模拟原版判定逻辑、模拟原版血条机制

### backbeat 集成

合并了 [zkldi 维护的带 backbeat 支持的 Endless Dream 分支](https://github.com/zkldi/lr2oraja-endlessdream/releases)，可用于谱面下载，具体用法可参考上游项目说明。

---

## 使用配置

### 前置准备

1. 下载 beatoraja 0.8.8 本体。
2. 克隆本仓库所需的分支（LDJ 功能请用 `LDJPlus` 分支），构建出 jar，并用它替换 beatoraja 0.8.8 目录下的 `beatoraja.jar`。
3. 若目录中没有 `config_sys.json`，先启动一次游戏再关闭以生成该文件。
4. 单独克隆 [iidx2bms](https://github.com/krrcream/iidx2bms) 的 `orajaroot-plus` 分支到任意目录，确保目录内包含 `conversion/conversion.py` 与 `music_data/music_data.json`。常规版用 `orajaroot` 分支，不落盘音频中间产物版用 `orajaroot-plus` 分支。
### 配置项说明

编辑游戏目录下的 `config_sys.json`，添加以下配置项：

| 配置项 | 说明 |
| --- | --- |
| `LDJ_SoundPath` | 本地对应格式资源的 sound 根目录。留空则禁用整个 LDJ 功能。 |
| `LDJ_MoviePath` | 本地对应格式资源的影像根目录。 |
| `LDJ_ToolPath` | iidx2bms 工具所在目录的完整路径。 |
| `LDJ_Sources` | LDJ 运行时文件（缓存、会话数据、`ldjscore.db`）的存放目录。留空则使用系统临时目录，缓存可能被系统清理。 |
| `LDJ_CacheEnabled` | 是否启用转换缓存，启用后可避免重复转换。 |
| `LDJ_CacheMaxSizeMB` | 转换缓存的最大容量，单位为 MB。 |
| `LDJ_IncludeBGA` | 转换时是否包含背景影像。 |
| `LDJ_IncludePreview` | 转换时是否包含预览音频。 |

### 配置示例

```
"LDJ_SoundPath": "D:\\Games\\LDJ\\data\\sound",
"LDJ_MoviePath": "D:\\Games\\LDJ\\data\\movie",
"LDJ_ToolPath": "D:\\Tools\\iidx2bms",
"LDJ_Sources": "D:\\Games\\beatoraja\\ldj",
"LDJ_CacheEnabled": true,
"LDJ_CacheMaxSizeMB": 2048,
"LDJ_IncludeBGA": true,
"LDJ_IncludePreview": true
```

### LDJ 难度表（LDJ_table）

#### 前置条件

- `config_sys.json` 中 `LDJ_ToolPath` 需正确指向 iidx2bms 的分支根目录（常规版 `orajaroot`，不落盘音频中间产物版 `orajaroot-plus`），且目录下存在 `music_data/music_data.json`；无该文件时整个 LDJ 分类不会显示。
- 难度表中的曲目需能在 `music_data.json` 中匹配到，匹配失败的条目会被静默忽略。

#### 存放位置

在与 `beatoraja.jar` 同级的目录下新建 `LDJ_table` 文件夹（目录名固定，无额外配置项），将难度表 `.json` 文件直接放入即可。

```
beatoraja/
├── beatoraja.jar
├── config_sys.json
└── LDJ_table/
    ├── 难度表1.json
    └── 难度表2.json
```

#### 文件规则

- 仅扫描目录顶层的 `.json` 文件，不递归读取子目录。
- 按文件名不区分大小写升序排列，决定难度表的显示顺序。
- 若文件内无 `title` 字段，默认用文件名（去除扩展名）作为表名。

#### 格式说明

采用 `iidx-pro-difficulty-table` 格式（非 beatoraja 原生难度表格式），核心结构如下：

```
{
  "title": "表名",
  "groups": [
    {
      "name": "分组名",
      "charts": [
        { "musicId": 12345, "chartType": 3, "difficultyValue": 12.5, "label": "★12" }
      ]
    }
  ]
}
```

- `musicId`：对应 `music_data.json` 中的曲目编号
- `chartType`：0–4 对应 SP 各难度，5–9 对应 DP 各难度
- `difficultyValue`：分组内曲目排序依据，升序排列
- `label`：曲目后显示的难度标签

#### 注意事项

- 难度表仅在游戏启动时读取一次，增删修改文件后需重启游戏生效。
- 无有效难度表文件时，`Table` 分类不会显示，不影响 LDJ 其他功能。
- 文件损坏、格式错误或匹配失败的内容均静默处理，不会导致游戏崩溃。

### 启动使用

启动游戏进入选曲界面，即可看到 `LDJ` 分类文件夹。首次选择曲目时会显示转换进度，转换完成后即可正常游玩。

---

## 致谢

本项目基于 GNU GPL v3 协议开源，完整协议见 [LICENSE](LICENSE) 文件。

本项目衍生自以下上游项目：

- [beatoraja](https://github.com/exch-bms2/beatoraja)
- [LR2oraja](https://github.com/wcko87/lr2oraja)
- [LR2oraja \~Endless Dream\~](https://github.com/seraxis/lr2oraja-endlessdream)
- [带 backbeat 支持的 Endless Dream 分支](https://github.com/zkldi/lr2oraja-endlessdream/releases)

LDJ 功能依赖的 [iidx2bms 个人分支](https://github.com/krrcream/iidx2bms) 上游为 [Glebsin/iidx2bms](https://github.com/Glebsin/iidx2bms)。

---

>
> ⚠️ 请勿使用本程序游玩未经授权的版权内容。
