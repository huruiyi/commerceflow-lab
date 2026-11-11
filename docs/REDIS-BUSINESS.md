# Redis 业务 Key 与降级边界

| Key | TTL | 用途 | 事实来源 |
| --- | --- | --- | --- |
| `commerceflow:session:*` | 30 分钟 | Spring Session | 登录会话，不可降级 |
| `commerceflow:login:failures:*` | 5 分钟 | 登录失败限流 | 可重建 |
| `commerceflow:catalog:products:v1` | 5 分钟 | 商品、评分和评价列表缓存 | MySQL |
| `commerceflow:idempotency:payment:*` | 30 分钟 | 支付重放快速定位 | MySQL 唯一约束 |

商品读取在 Redis 暂时不可用时直接查询 MySQL；评价成功后删除商品缓存。支付正确性始终由 MySQL 幂等键、请求指纹和状态条件更新保证，Redis 只做加速。Session 依赖 Redis，因此 Redis 故障期间已经登录的受保护请求可能需要重新登录，但订单、支付和评价数据库数据不得被回滚或伪造。
