# 历史清理：已完成 / History cleanup: DONE

**状态：远端 `origin` 已清理完毕，21 个 tag 全部推上去了。**
只有一个远端做不了，原因见第三节。

---

## 一、已经做完的（origin: `fadersketch/Promaid-mod`）

```
+ 05103ae2...b8a48285  main -> main                       (forced update)
+ 0964efca...4f6a89db  experimental/memory-port           (forced update)
+ 21 个 tag 全部强制更新（v1.0.2 … v1.3.0-beta）
```

结果核对（都是实跑）：

| 项 | 清理前 | 清理后 |
|---|---|---|
| `origin/main` | `05103ae` | `b8a48285` |
| 远端 tag 数 | 18 | **21**（补上 v1.0.0 / v1.0.1 / v1.2.1-ace-warlock-exp1）|
| `origin/main` 里的诊断残留 | 有 | **0**（`_tlm_jar` / `_modtlm_jar` / `_cdx` / `fix69.py` / `compile_out.txt` … 全为 0）|
| 真实源码历史 | — | **保留 206 个提交**动过 `promaid_src_neo/com/maidsmart` |

清理只动目录结构，**不动任何源码内容**：清理前后工作树逐文件比对，
1599 个文件 0 差异；21 个 tag 全部仍能解析到提交。

## 二、PR #26 已通知

历史重写会让这个 PR 的 base 失效，所以先在你的 PR 上留了说明与 rebase 步骤：

https://github.com/fadersketch/Promaid-mod/pull/26#issuecomment-5918742262

（他的提交在 fork 上，不会丢；内容没变，rebase 应当无冲突。）

## 三、做不了的：`rod` 远端

`rod` 指向 **`rodericksthescriptkid/Promaid-mod-Ace-Warlock`** ——
这是**别人的 fork**，GitHub 明确拒绝写入：

```json
"permissions": {"admin": false, "maintain": false, "push": false, "triage": false, "pull": true}
```

而且它确实是 fork（`"fork": true`，parent 就是你的仓库）：

```
remote: ! [remote rejected]  main -> main  (permission denied)
```

**这不是"要不要做"的问题，是权限上做不到。** 只有两种途径能清它：

1. **让 fork 主人自己清**（推荐）：让他同步你清理后的 `main` ——
   ```bat
   git fetch upstream
   git reset --hard upstream/main
   git push --force-with-lease
   ```
   或者干脆删掉重建 fork（fork 没有独立价值时最省事）。
2. **申请该仓库的协作者权限或转移**，再清。

好消息是：**fork 是"衍生副本"，不是"污染源"。** 上游（权威版本）已经干净，
新 clone 的人都从 `fadersketch/Promaid-mod` 拿干净历史；那个 fork 里的旧对象只影响它自己。

## 四、回滚（万一）

两份东西都留在本地：

- `_cleanup_backup/promaid-ALL-refs-20261001.bundle`
  清理**之前**的全部 48 个 ref（含所有旧 tag 与分支）。
- `_cleanup_backup/remote-state-before-force-push.json`
  强推**之前**两个远端每个分支/tag 的确切 SHA。

从备份恢复一份旧仓库：

```bat
git clone _cleanup_backup\promaid-all-refs-20261001.bundle restore-old
cd restore-old
git push --force origin <旧SHA>:refs/heads/main
```

## 五、顺带修掉的根因

清理只是治标，**治本的是 `.gitignore`**：

原来只按「文件后缀」逐条排除（`/_*.py`、`/_*.txt`…），
所以**目录形态**的草稿（`_693tree/`、`_swb/`、`_f743/`…）全部漏网 ——
一次 `git add -A` 就会把上万个反编译/解包文件推上远端。

现在改成锚定仓库根的前缀规则 `/_*/` 与 `/_*`，未跟踪条目从 **14037 降到 0**。
以后不会再长出来。
