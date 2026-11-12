# mock-payment-ui

Vue 3.5.45 + TypeScript 6.0.3 + Vite 8.3.8 支付平台 UI。UI 使用 Ant Design Vue Next，表格场景使用 VXE-Table，包括：

- 安全登录页与三种演示角色快捷入口；
- 支付控制台和安全边界提示；
- 商户入驻、一次性 App Secret、凭据轮换、启停与平台公钥下载；
- 支付宝跳转、微信二维码、银联网关三种收银体验；
- 后台账号、角色、MFA 策略和商户数据范围；
- 渠道交易账本、关闭交易、故障场景与审计日志。
- HMAC/RSA Web Crypto 签名实验台，展示防重放验证管线；
- TOTP 二维码、恢复码和一次性 Step-up 令牌安全中心。

容器入口：`http://localhost:14201`。

本地建议使用 Node.js 26.13+ 和 npm 11.20+。Ant Design Vue 按项目要求使用 `next` 发布通道的 `4.0.0-rc.6`，并在 `tsconfig.app.json` 中开启 `skipLibCheck` 以隔离该 RC 版与 TypeScript 6 的第三方声明兼容问题。
