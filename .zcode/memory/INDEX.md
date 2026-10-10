---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-10-10 16:59
updated: 2026-10-10 16:59
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-10-10] lr2oraja-endlessdream IIDX 集成(分支 LDJ),Step 6 已提交 e0147274 | Step 7 待做 | 快照 handoffs/iidx-step6-musicselector.md
- [2026-10-10] lr2oraja-endlessdream IIDX 集成 Step 8:整包构建 + 真机联测 | 未开始 | 同上快照 Next 段

## 下一步

- 做 Step 7:在 core/src/bms/player/beatoraja/select/ 下新增 IIDXConversionOverlay,读取 MusicSelector 已有字段 iidxProgress/iidxStage 画转换进度浮层,单步单提交
- 做 Step 8:-Dplatform=windows 整包构建 + 真机联测 IIDX 转换全流程
- 只读参考 D:\iidx2bms-src\iidx2bms 禁止修改、不要用打包 EXE

## 最近快照

- [2026-10-10 16:59] handoffs/iidx-step6-musicselector.md — active
