# 电子发票模拟平台

服务独占 PostgreSQL 的 `invoice_platform` 数据库 中的请求、模拟票据和回执账本。调用方提供已核算的正票金额和单笔成功退款金额。金额均为正数，记至两位小数，最大为 `9999999999999999.99`，币种使用三个大写字母且红票与原票一致。正票记录开票申请号和原支付单号，红票记录原正票号和退款单号。

## 请求与状态

| 方法与路径 | 内容 |
| --- | --- |
| `POST /api/invoice-requests/{requestId}` | `applicationNo`、`paymentNo`、`amount`、`currency` 和可选 `simulation` |
| `POST /api/red-invoice-requests/{requestId}` | `originalInvoiceNo`、`refundNo`、`amount`、`currency` 和可选 `simulation` |
| `GET /api/invoice-requests/{requestId}` | 按原请求号查询正票或红票的处理结果 |
| `GET /api/invoices/{invoiceNo}` | 按票号读取对应结果 |

提交受理返回 HTTP 202 和 `ACCEPTED`。终态为 `SUCCEEDED` 或 `FAILED`，分别包含 `invoiceNo` 或 `failureCode`，并记录 UTC 受理与完成时间。查询已有请求或重放终态请求返回 HTTP 200。结果中的 `kind` 为 `ISSUE` 或 `RED`，`referenceNo` 对应开票申请号或退款单号。

重放使用原 `requestId` 与相同的请求内容。相同请求号改用其他金额、业务标识或模拟结果会返回 HTTP 409 `REQUEST_CONTENT_MISMATCH`。同一开票申请或同一正票下的同一退款单最多有一个受理中或成功请求。明确失败后，调用方可带新的请求号重试相同业务。红冲按原正票串行校验额度，受理中和成功红冲共同占用额度，失败释放额度。超额返回 HTTP 409 `RED_AMOUNT_EXCEEDS_AVAILABLE`，币种不符返回 HTTP 409 `CURRENCY_MISMATCH`。受理校验拒绝的请求会回滚。

`simulation` 默认 `SUCCESS`。`FAIL` 异步终结为 `SIMULATED_CHANNEL_FAILURE`。`ACCEPT_THEN_TIMEOUT` 在受理提交后故意延迟首次 POST 响应，随后仍异步生成票据。调用方超时后应使用原请求号查询，或使用原请求号和原内容重放。

## 签名与回执

所有业务 API 和回执使用 `X-Invoice-Timestamp` 和 `X-Invoice-Signature`。时间戳为 Unix 秒，容许时差为 300 秒，签名为 HMAC-SHA256 的十六进制表示。签名内容为 UTF-8 前缀 `timestamp + "\n" + HTTP_METHOD + "\n" + rawPath + "\n"` 拼接原始请求体字节，GET 的请求体为空。请求和回执各用独立密钥。

终态、成功票据和待投递回执在同一事务提交。回执以 POST 发往固定的 `INVOICE_CALLBACK_URL`，正文包含 `eventId` 和 `result`。同一回执重投使用相同的事件 ID（`evt-<requestId>`）和结果，每次重新签名。接收方应先按事件 ID 幂等处理，再返回 2xx 确认。超时、断线或非 2xx 会触发重投，待处理请求和待确认回执均由 PostgreSQL 账本恢复。

独立运行时设置 `SPRING_PROFILES_ACTIVE=postgresql`，并填写 Spring 的 PostgreSQL 数据源变量，并设置 `INVOICE_REQUEST_SECRET`、`INVOICE_CALLBACK_SECRET`（不同且各至少 32 字符）和 `INVOICE_CALLBACK_URL`。验收入口从仓库根目录运行，使用独立 Compose 项目和数据卷，结束后保留该卷供复核。

```powershell
pwsh -NoProfile -File scripts/invoice/verify-invoice-simulator.ps1
```
