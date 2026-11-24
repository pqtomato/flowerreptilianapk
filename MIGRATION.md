# 项目迁移记录

本仓库由 `D:\project\test\wanhongyy` 迁移而来，迁移日期 **2026-11-24**。

## 迁到了哪里

| 项目 | 值 |
| --- | --- |
| 新路径 | `D:\project\flowerreptilianapk` |
| 旧路径 | `D:\project\test\wanhongyy`（**仍然保留**，未删除） |
| Git 远端 | `https://github.com/pqtomato/flowerreptilianapk.git`（分支 `main`） |
| 迁入的提交 | 11 个（完整历史，含此前未推送的 7 个提交） |

## 一、代码与 Git 历史

用 `robocopy` 复制整个工作区（含 `.git`），只排除构建产物：

```
android\app\build  android\core\build  android\build  android\.gradle
android\.cxx  target  data  .idea
```

校验方式与结果：

- `git status` 干净（工作区与 11 个提交完全一致）；
- `git fsck` 无损坏；
- 源/目标逐文件比对：**仅源有 0 个文件**（目标多出的只有 `main` 分支改名产生的 `.git` 引用）。

> 注意：`android\local.properties`（SDK 路径，已被 gitignore）也一并复制了，
> 因为同一台机器上它依然有效；换机器时要按新机器改。

后续变更：

- `origin` 从原 Gitee 仓库改为上面的 GitHub 仓库；
- 分支 `master` 改名为 `main`（与新建的 GitHub 仓库一致）；
- 清理了指向旧仓库的过期远程跟踪引用。

## 二、AI 会话记忆（DSH 历史）

迁移的同一个工作区下原有 5 个 DSH 会话。**关键约束**：DSH 的 jsonl 后端有一条硬不变式 ——

> 同一个 `sessionId` 不允许出现在两个项目目录里，否则启动/列举会话时抛出
> `duplicate JSONL session id "session-xxx" appears in multiple project directories`

所以历史**只能移动，不能复制**。第一次按"复制"导入时正是踩到了这条，随后已回滚
（回滚后 DSH 恢复正常：34 个会话、0 失败）。

已迁移（4 个）：

- `session-c5d99ebb-68f2-4028-995d-fdd2324c7d95`（3836 个事件，本次开发的主体会话）
- `session-27da038c-d1d2-4e54-9280-2b9e4fe97ded`
- `session-b8ef2a5c-bcc2-4356-bea8-fbbbc2ac286c`
- `session-f9851d10-2c01-40af-88bb-9db785e6c19c`

同时做了两件事，缺一不可：

1. **改写日志 header 里的 `cwd`** —— 否则历史能在新目录列出来，但续用时会指回旧目录；
2. **更新 `storages/workspace.json`** —— 把 sessionId 从旧工作区摘掉、挂到新工作区。

### 还剩 1 个会话没迁（需要你手动收尾）

`session-9893e968-a618-4f32-a96d-3b7b9ec48a88` 是**迁移当时正在使用的会话**
（也就是执行这次迁移的对话），文件被运行中的 DSH 占用，不能搬。

**关闭 DSH 之后**，在本目录执行一次即可（工具幂等，可重复跑）：

```bash
node tools/dsh-workspace-move.mjs --from "D:\project\test\wanhongyy" --to "D:\project\flowerreptilianapk"
```

跑完重启 DSH，该会话就会出现在新工作区下。

### 校验

用 DSH **自己的存储后端**（`@deepseek-ai/dsh-session-persistence-jsonl`
的 `list()` / `open()` / `read()`）验证，而不是只看文件能不能解开：

```bash
node tools/dsh-verify-with-dsh-reader.mjs \
  --npm-root "C:\Users\89710\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
```

结果：**34 个会话，失败 0 个**；迁移过去的 4 个会话 `cwd` 均为
`D:\project\flowerreptilianapk`。

## 三、备份与回滚

| 内容 | 位置 |
| --- | --- |
| 迁移前的会话记忆包（含全部 7 个日志文件，cwd 已改写） | `D:\project\flowerreptilianapk-memory-pack` |
| 目标目录原来的空 `.git`（GitHub 空仓库） | `D:\project\.flowerreptilianapk-git-empty-backup` |
| DSH 导入前的登记表备份 | `C:\Users\<你>\.dsh\dsh-import-backup-*\workspace.json.before` |

**回滚到旧路径**（比如发现新路径不对劲）：

```bash
node tools/dsh-workspace-move.mjs --from "D:\project\flowerreptilianapk" --to "D:\project\test\wanhongyy"
```

旧目录一直原样保留，所以在确认新路径稳定之前，**不要删** `D:\project\test\wanhongyy`。

## 四、本次新增的工具

| 工具 | 作用 |
| --- | --- |
| `tools/lib/session-log.mjs` | 多帧 zstd 会话日志的读写公共库（按魔数切帧、只重压 header 帧） |
| `tools/dsh-history-repath.mjs` | 改写单个/一批会话日志的 `cwd`（用于换机器、换目录） |
| `tools/dsh-workspace-move.mjs` | 把某个工作区的会话**移动**到新路径（改写 cwd + 搬目录 + 更新登记表 + 查重） |
| `tools/dsh-history-import.mjs` | 原有导入工具，本次修了两个问题，见下 |
| `tools/dsh-verify-with-dsh-reader.mjs` | 原有校验工具（用 DSH 真实后端校验） |

`tools/dsh-history-import.mjs` 的两个修复：

1. **原来只复制每代的第一个日志文件** —— 用 `find()` 取 `session.v*.jsonl.zstd`，
   而 DSH 升级格式后会同时留下 `session.v3.*` 和 `session.v4.*`（当前生效的是高版本）。
   实测主会话 v3 有 3080 个事件、v4 有 3335 个，只搬 v3 会让历史**回退到旧状态**。
   现已改为复制全部代次。
2. **附件复制失败会中断整个导入** —— 而它发生在"登记表落盘"之前，会留下
   「日志已复制、却没登记」的半成品。现已改为失败只告警、继续走完。

## 五、迁移后还要做的事

1. 关闭并重启 DSH，确认新工作区 `D:\project\flowerreptilianapk` 下能看到 4 个历史会话；
2. 按第二节收尾最后 1 个会话；
3. 确认无误后，再决定是否删除旧目录 `D:\project\test\wanhongyy`；
4. 首次在新目录构建安卓包时确认 `android/local.properties` 的 `sdk.dir` 有效：

   ```bash
   cd D:\project\flowerreptilianapk\android
   gradle :core:test        # 内核单元测试
   gradle :app:assembleDebug
   ```
