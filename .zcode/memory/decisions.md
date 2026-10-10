---
summary: 已拍板的决策与理由,append-only
created: 2026-10-10 16:59
updated: 2026-10-10 16:59
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
