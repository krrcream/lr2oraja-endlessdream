# LR2oraja \~Endless Dream\~ Personal Build

[简体中文](README.zh-CN.md)・English

This repository is a personal custom build of [LR2oraja \~Endless Dream\~](https://github.com/seraxis/lr2oraja-endlessdream). It keeps every upstream feature intact, integrates backbeat support, and adds personal-use functionality such as the in-game LDJ song list and trainer enhancements.

>
> **About this project**: This is a personally maintained branch for my own use. No technical support of any kind is provided, and submitted issues may not be handled.

---

## Important Notice and Copyright

1. This repository contains only the modified game engine code. It contains no music, chart, movie or other game data files, and provides no channels or methods for obtaining and downloading such resources.
2. This repository provides no methods, tools or information for cracking, bypassing or decrypting any protection or encryption.
3. The LDJ feature only converts and reads files the user already has locally; all resource files must be prepared by the user.
4. Please make sure that every resource you use is legally licensed, and that you use it only for personal study and research.
5. This project is not responsible for any third-party copyright issues. Do not use this program for any infringing purpose.

---

## New Features

### BMS SP-DP Converter

Directly in game, you can split an SP chart into a DP chart, or compress a DP chart into an SP chart to play.

### LDJ In-Game Song List

This feature uses the standalone tool [iidx2bms (personal fork)](https://github.com/krrcream/iidx2bms) as its format conversion backend, whose upstream project is [Glebsin/iidx2bms](https://github.com/Glebsin/iidx2bms). It converts the corresponding chart format the user already has locally in real time, and integrates the result into the in-game song select screen for direct browsing and playing.

Corresponds to two iidx2bms branches: `orajaroot` is the standard version (writes audio intermediate products during conversion); `orajaroot-plus` is the no-audio-intermediate-product version.
- A separate `LDJ` category folder in the song select screen
- Support for custom difficulty tables (Tables), which can be imported as external table files
- Format conversion starts automatically the first time a chart is selected; once it finishes you can play normally
- Local conversion cache, so the same content is not converted twice and does not tie up system resources
- Scores are stored separately in `ldjscore.db` and never mixed with `score.db` for native charts
- IR upload is disabled by default, so native score records are unaffected

>
> Note: iidx2bms is an independent format conversion tool project. This repository contains neither its code nor any data files; download and configure it separately.

### trainer_plus Enhanced Trainer Menu

The following training features were added to the built-in Mod menu (shortcut `F5` / `Insert`):

- Emulates IIDX-style groove gauge and judgement
- Enhanced frequency (rate) training: 0.1% fine adjustment, 0.5% steps in practice mode, simulation of the original judgement logic, and simulation of the original gauge behavior

### backbeat Integration

Merged the [Endless Dream branch maintained by zkldi with backbeat support](https://github.com/zkldi/lr2oraja-endlessdream/releases), which can be used for chart downloads; see the upstream project's documentation for usage.

---

## Configuration

### Prerequisites

1. Have a working base beatoraja 0.8.8 installation.
   - If there is no `config_sys.json` in the directory, launch the game once and close it to generate the file.
2. Separately clone [iidx2bms](https://github.com/krrcream/iidx2bms) into any directory, and make sure it contains `conversion/conversion.py` and `music_data/music_data.json`. The standard version uses the `orajaroot` branch, and the no-audio-intermediate-product version uses the `orajaroot-plus` branch.
### Configuration Options

Edit `config_sys.json` in the game directory and add the following options:

| Option | Description |
| --- | --- |
| `LDJ_SoundPath` | Root directory of the sound files for the corresponding local format. Leave empty to disable the entire LDJ feature. |
| `LDJ_MoviePath` | Root directory of the movie files for the corresponding local format. |
| `LDJ_ToolPath` | Full path to the directory containing the iidx2bms tool. |
| `LDJ_Sources` | Directory for LDJ runtime files (cache, session data, `ldjscore.db`). Leave empty to use the system temporary directory, where the cache may be cleaned up by the system. |
| `LDJ_CacheEnabled` | Whether to enable the conversion cache; enabling it avoids repeated conversions. |
| `LDJ_CacheMaxSizeMB` | Maximum size of the conversion cache, in MB. |
| `LDJ_IncludeBGA` | Whether to include background movies when converting. |
| `LDJ_IncludePreview` | Whether to include preview audio when converting. |

### Example Configuration

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

### LDJ Difficulty Tables (LDJ_table)

#### Prerequisites

- `LDJ_ToolPath` in `config_sys.json` must point correctly to the root of the iidx2bms branch (standard version `orajaroot`, no-audio-intermediate-product version `orajaroot-plus`), and that directory must contain `music_data/music_data.json`; without that file the whole LDJ category is not shown.
- Charts in a difficulty table must be matchable in `music_data.json`; entries that fail to match are silently ignored.

#### Location

Create an `LDJ_table` folder next to `beatoraja.jar` (the directory name is fixed, with no additional option for it), and put the difficulty table `.json` files directly into it.

```
beatoraja/
├── beatoraja.jar
├── config_sys.json
└── LDJ_table/
    ├── table1.json
    └── table2.json
```

#### File Rules

- Only the `.json` files directly at the top level of the folder are scanned; subdirectories are not read recursively.
- Sorted by filename in case-insensitive ascending order, which determines the display order of the tables.
- If a file has no `title` field, the filename (without the extension) is used as the table name by default.

#### Format

The `iidx-pro-difficulty-table` format is used (not beatoraja's native difficulty table format). Its core structure is:

```
{
  "title": "table name",
  "groups": [
    {
      "name": "group name",
      "charts": [
        { "musicId": 12345, "chartType": 3, "difficultyValue": 12.5, "label": "★12" }
      ]
    }
  ]
}
```

- `musicId`: the chart number in `music_data.json`
- `chartType`: 0–4 correspond to the SP difficulties, 5–9 to the DP difficulties
- `difficultyValue`: the sort key for charts within a group, in ascending order
- `label`: the difficulty label shown after the chart

#### Notes

- Difficulty tables are read only once at game launch, so adding, removing or editing files requires a restart to take effect.
- When there is no valid table file, the `Table` category is not shown, and the other LDJ features are unaffected.
- Corrupted files, format errors and failed matches are all handled silently and will not crash the game.

### Launching

Launch the game and open the song select screen; the `LDJ` category folder is there. The conversion progress is shown the first time a chart is selected, and once it finishes you can play normally.

---

## Credits

This project is open source under the GNU GPL v3 license; see the [LICENSE](LICENSE) file for the full text.

It is derived from the following upstream projects:

- [beatoraja](https://github.com/exch-bms2/beatoraja)
- [LR2oraja](https://github.com/wcko87/lr2oraja)
- [LR2oraja \~Endless Dream\~](https://github.com/seraxis/lr2oraja-endlessdream)
- [The Endless Dream branch with backbeat support](https://github.com/zkldi/lr2oraja-endlessdream/releases)

The upstream of the [iidx2bms personal fork](https://github.com/krrcream/iidx2bms) that the LDJ feature depends on is [Glebsin/iidx2bms](https://github.com/Glebsin/iidx2bms).

---

>
> ⚠️ Do not use this program to play copyrighted content you are not authorized to play.
