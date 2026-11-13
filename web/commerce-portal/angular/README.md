# Commerce portal (Angular)

当前基线为 Angular 22.2.6、TypeScript 6.0.3、SimUI 1.8.0 / Spartan UI 1.8.0、Tailwind CSS 4.3.5 和 Bootstrap 5.3.9。SimUI 采用 copy-paste 模式：Spartan Brain 从 npm 安装，Button、Card、Badge、Alert 和 Spinner 的 Helm 源码保留在 `libs/ui/` 中。新增界面优先组合这些本地组件；原有 Bootstrap 业务页面暂时保留，不从 CDN 加载。

## 功能与路由

| 路径 | 页面 | 功能 |
| --- | --- | --- |
| `/` | 登录 | Reactive Forms 校验、记住用户界面状态 |
| `/dashboard` | 商品 | 商品列表、详情、选择 1–99 数量并加入购物车 |
| `/cart` | 购物车 | 数量调整、移除、清空、小计和结算 |
| `/addresses` | 收货地址 | 地址 CRUD、默认地址和结算地址选择 |
| `/orders` | 订单与支付实验 | 三渠道选择、商户信息、幂等键、重放、冲突实验和支付回执 |
| `/users` | 用户 | 后端演示用户列表和前端搜索 |
| 其他 | 404 | 错误页 |

前端调用同源 `/api` 接口。Docker 环境中由 Nginx 反向代理至 `commerce-service:8080`；本地 `npm start` 由 `proxy.conf.json` 代理至 `localhost:8080`。

## 本地开发

先确保后端在 `localhost:8080` 运行，再执行：

本地建议使用 Node.js 26.13+ 和 npm 11.20+。

```powershell
npm ci
npm start
```

访问 `http://localhost:4200`，使用 `demo@example.com / demo-password` 登录。

## Docker 运行

推荐在项目根目录一次启动完整环境：

```powershell
docker compose up --build -d --wait
```

前端镜像由 Node.js 生产构建阶段和 Nginx 运行阶段组成，包含 SPA 路由回退和 `/api` 反向代理。

## 验证

```powershell
npm run format:check
npm run build -- --configuration production
npm test -- --watch=false --browsers=ChromeHeadless
npm audit
```

`npm run format:check` 使用项目本地 Prettier 只读检查 `src/`、`libs/` 和根配置文件；
需要明确改写格式时才执行 `npm run format:write`。正式 Docker 镜像会在 Angular 生产构建前自动运行只读检查。

## 代码阅读顺序

1. `src/app/app-routing.module.ts`：页面入口与路由保护。
2. `src/app/login/login.component.ts`：登录表单。
3. `src/app/services/account.service.ts`：CSRF、登录、注销与前端用户状态。
4. `src/app/services/api.service.ts`：商品、购物车、订单、地址和支付 API。
5. `src/app/purchase-orders/`：三渠道支付领域与幂等实验 UI。
6. `src/app/dashboard/`、`order/`、`addresses/`、`users/`：其他业务页面。
7. `libs/ui/`：由 Spartan CLI 1.8.0 复制到项目内的 SimUI/Helm 组件源码。

## 安全边界

`AuthGuard` 和浏览器中的用户资料只负责界面导航；真实认证与授权由 Spring Security 会话完成。“记住我”仅决定非敏感用户资料放入 `localStorage` 还是 `sessionStorage`，不会将密码或会话令牌写入 Web Storage。

支付页面只保存当前教学请求的随机幂等键、渠道和失败模拟标记，不保存 App Secret、私钥或平台密钥。切换渠道会生成新键；网络结果不确定时可精确重放原请求；跨渠道复用旧键会展示后端返回的 409 安全冲突。
