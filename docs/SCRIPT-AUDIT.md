# Windows/Bash 脚本契约与非破坏性审计

## 范围与结论

2026-11-13 审计 `scripts/windows/verify-*.ps1` 与 `scripts/linux/verify-*.sh`。加入告警管道验收后共 37 组主要验收入口，文件基名全部一一对应。`generate-expiration-strategy-video.ps1` 是 Windows 教学视频生成器，不是业务验收脚本，不在成对范围内。

参数契约以 [script-contracts.tsv](../scripts/script-contracts.tsv) 为机器可读清单：PowerShell 使用命名参数，Bash 的普通单项脚本按同一顺序使用位置参数，组合入口使用长选项。审计修正了物流服务地址、支付服务地址、RabbitMQ 凭据/等待时间和支付/取消竞态参数顺序的差异。

## 默认安全语义

- 没有主要脚本默认执行 `docker compose down -v`、`docker volume rm` 或 `docker volume prune`。
- 全新卷专项默认保留其随机临时项目卷；只有显式 `-RemoveTemporaryVolumes` / `--remove-temporary-volumes` 会执行该临时项目的 `down --volumes`。它不匹配或删除默认 `fairy-commerceflow_*` 卷。
- Flyway 空库/旧库演练使用随机容器名、独立网络和 `/var/lib/mysql` tmpfs，结束时只删除这两个临时容器和网络，不创建或删除持久数据卷。
- Bash 的递归删除仅用于 `mktemp/new_temp_dir` 创建的每次运行临时目录；审计入口会拒绝指向 `/`、`~`、`$HOME` 或项目根的递归删除。

## 可重复审计

Windows：

```powershell
./scripts/windows/audit-script-contracts.ps1
```

Bash：

```bash
bash ./scripts/linux/audit-script-contracts.sh
```

两个入口都检查文件名集合、契约清单覆盖、Bash 参数绑定、禁止的宽范围删除和空卷显式开关。PowerShell 入口还通过 AST 校验每个 `.ps1` 的参数名称与顺序。
