import { Injectable, signal } from '@angular/core';

export type Locale = 'en' | 'zh-CN';

const en: Record<string, string> = {
  brand: 'CommerceFlow Lab', products: 'Products', cart: 'Cart', addresses: 'Addresses', orders: 'My Orders',
  fulfillment: 'Fulfillment', users: 'Users', logout: 'Logout', account: 'Account', search: 'Search',
  language: '中文', toggleNavigation: 'Toggle navigation', searchProducts: 'Search products', searchResults: '{count} matching products', noSearchResults: 'No products match your search.', clearSearch: 'Clear search',
  loginTitle: 'User Login', email: 'Email address', password: 'Password', emailPrivacy: "We'll never share your email with anyone else.",
  learningEnvironment: 'LOCAL LEARNING ENVIRONMENT', loginWelcome: 'Learn the complete commerce flow', loginDescription: 'Explore authentication, catalog, checkout, payment, fulfillment, and reviews in one local lab.', secureLogin: 'Secure access', emailPlaceholder: 'name@example.com', passwordPlaceholder: 'Enter your password',
  remember: 'Keep me signed in for 14 days', signIn: 'Sign in', signingIn: 'Signing in...',
  invalidLogin: 'Enter a valid email address and password.', loginRateLimited: 'Too many failed attempts. Please wait five minutes and try again.',
  loginUnavailable: 'The login service is temporarily unavailable. Please try again.', loginRejected: 'The email or password is incorrect, or this account is disabled.',
  demoAccounts: 'Demo accounts', customer: 'Customer', merchant: 'Merchant', administrator: 'Administrator', allAccountPassword: 'Password for all accounts',
  productList: 'Product List', details: 'Product Detail', quantity: 'Quantity', addBasket: 'Add to Basket',
  customerReviews: 'Customer reviews', reviews: 'reviews', noReviews: 'No reviews yet.', subtotal: 'Subtotal', close: 'Close',
  decreaseQuantity: 'Decrease quantity', increaseQuantity: 'Increase quantity', productQuantity: 'Product quantity',
  shoppingCart: 'Shopping Cart', clearCart: 'Clear cart', checkout: 'Checkout', emptyCart: 'Your cart is empty.',
  manageAddresses: 'Manage shipping addresses', itemId: 'Item ID', productImage: 'Product image', updated: 'Updated',
  productName: 'Product name', unitPrice: 'Unit price', action: 'Action', remove: 'Remove', orderQuantity: 'Order quantity', checkoutFailed: 'Checkout failed.',
  shippingAddresses: 'Shipping Addresses', label: 'Label', recipient: 'Recipient', phone: 'Phone', countryCode: 'Country code',
  province: 'Province/State', city: 'City', district: 'District', addressLine: 'Address line', postalCode: 'Postal code',
  addressLine2: 'Address line 2', defaultAddress: 'Default address', update: 'Update', addAddress: 'Add address', cancel: 'Cancel',
  default: 'Default', selected: 'Selected', edit: 'Edit', setDefault: 'Set default', useForCheckout: 'Use for checkout', delete: 'Delete',
  noAddresses: 'No shipping addresses yet.', addressSaved: 'Address saved.', checkoutAddressSelected: 'Checkout address selected.',
  paymentChapter: 'CHAPTER 05A-6', paymentLab: 'Multi-channel Payment Lab',
  paymentIntro: 'Create a payment, enter the simulated provider cashier, and observe the signed asynchronous result.',
  realisticFlow: 'Realistic learning flow', realisticFlowDetail: 'Merchant request → provider checkout → signed callback → transactional outbox → Kafka projection.',
  order: 'ORDER', paymentChannel: 'PAYMENT CHANNEL', selectProvider: 'Select a simulated provider', serverCredentials: 'Server-side credentials',
  merchantNumber: 'Merchant number', applicationId: 'Application ID', protocol: 'Protocol', secretsBackend: 'Secrets remain in the Spring Boot backend and are never sent to this page.',
  idempotencyKey: 'IDEMPOTENCY KEY', idempotencyHint: 'Stored in this browser session so an uncertain request can be retried safely.',
  generateKey: 'Generate new key', creating: 'Creating…', createPayment: 'Create payment', advancedExperiments: 'Advanced idempotency experiments',
  replayExplanation: 'Replay returns the original payment. Reusing its key with another channel must return HTTP 409.', replayExact: 'Replay exact request', reuseAnotherChannel: 'Reuse key on another channel',
  latestPayment: 'LATEST PAYMENT', paymentNumber: 'Payment number', channelTransaction: 'Channel transaction', amount: 'Amount', created: 'Created', paid: 'Paid',
  qrPayload: 'QR payload', openCashier: 'Open provider cashier', refreshStatus: 'Refresh callback status', requestCreated: 'Payment request created',
  providerResult: 'Provider result received', orderPaidEvent: 'Order paid and event published', replayLast: 'Replay last idempotent request',
  noOrders: 'No orders yet', noOrdersHint: 'Add products to the cart, select a shipping address, and checkout to start the payment lab.',
  yourReview: 'Your review', submitReview: 'Submit review', reviewed: 'Reviewed', confirmReceipt: 'Confirm receipt',
  signedCallbackPaid: 'Signed callback received. The order is paid.', waitingCallback: 'Still waiting for the asynchronous provider callback.',
  cashierOpened: 'Cashier opened. Complete or cancel there, then refresh payment status.', receiptConfirmed: 'Receipt confirmed. You can now review each purchased item.',
  reviewSubmitted: 'Review submitted. Thank you.', replayingRequest: 'Replaying the stored idempotent request…', submittingPayment: 'Submitting the payment request…',
  paymentPending: 'Payment {payment} is pending. Open the provider cashier and wait for its signed callback.', paymentRequestFailed: 'Payment request failed. The same key is preserved for a safe retry.',
  alipay: 'Alipay', wechatPay: 'WeChat Pay', unionPay: 'UnionPay', alipayDescription: 'App payment with an RSA-signed merchant request.', wechatDescription: 'QR-style payment using the demo API secret.', unionPayDescription: 'Gateway payment with a simulated UnionPay certificate.',
  userList: 'User List', userDetails: 'Go to details', detailClicked: 'Detail clicked for user {id}',
  orderFulfillment: 'Order Fulfillment', fulfillmentChapter: 'CHAPTER 06 · MERCHANT', fulfillmentHelp: 'Confirm paid orders and record auditable shipment details.',
  confirmOrder: 'Confirm order', carrier: 'Carrier', tracking: 'Tracking number', markShipped: 'Mark shipped', noMerchantOrders: 'No merchant orders yet.', shipTo: 'Ship to',
  orderConfirmed: 'Order {order} confirmed.', orderShipped: 'Order {order} shipped.',
  pageNotFound: 'Page not found', pageNotFoundDetail: 'The page you are looking for does not exist.', goHome: 'Go home',
  PENDING_PAYMENT: 'Pending payment', PAID: 'Paid', CONFIRMED: 'Confirmed', SHIPPED: 'Shipped', RECEIVED: 'Received',
  simulatedDecline: 'Simulated decline', PENDING: 'Pending', SUCCESS: 'Success', FAILED: 'Failed', CLOSED: 'Closed', EXPIRED: 'Expired'
};

const zh: Record<string, string> = {
  brand: '商流实验室', products: '商品', cart: '购物车', addresses: '收货地址', orders: '我的订单', fulfillment: '商家履约', users: '用户',
  logout: '退出登录', account: '账户', search: '搜索', language: 'English', toggleNavigation: '展开或收起导航', searchProducts: '搜索商品', searchResults: '找到 {count} 件商品', noSearchResults: '没有找到匹配的商品。', clearSearch: '清除搜索',
  loginTitle: '用户登录', email: '邮箱', password: '密码', emailPrivacy: '我们不会向任何人泄露你的邮箱。', remember: '记住登录 14 天',
  learningEnvironment: '本地学习环境', loginWelcome: '学习完整商城业务流程', loginDescription: '在一个本地实验室中体验认证、商品、结算、支付、履约和评价。', secureLogin: '安全登录', emailPlaceholder: 'name@example.com', passwordPlaceholder: '输入密码',
  signIn: '登录', signingIn: '正在登录…', invalidLogin: '请输入有效的邮箱和密码。', loginRateLimited: '失败次数过多，请等待五分钟后重试。',
  loginUnavailable: '登录服务暂时不可用，请稍后重试。', loginRejected: '邮箱或密码错误，或者账号已被禁用。',
  demoAccounts: '演示账号', customer: '客户', merchant: '商家', administrator: '管理员', allAccountPassword: '所有账号的密码',
  productList: '商品列表', details: '商品详情', quantity: '数量', addBasket: '加入购物车', customerReviews: '用户评价', reviews: '条评价', noReviews: '暂无评价。',
  subtotal: '小计', close: '关闭', decreaseQuantity: '减少数量', increaseQuantity: '增加数量', productQuantity: '商品数量',
  shoppingCart: '购物车', clearCart: '清空购物车', checkout: '结算', emptyCart: '购物车为空。', manageAddresses: '维护收货地址',
  itemId: '条目 ID', productImage: '商品图片', updated: '更新时间', productName: '商品名称', unitPrice: '单价', action: '操作', remove: '移除', orderQuantity: '购物车数量', checkoutFailed: '结算失败。',
  shippingAddresses: '收货地址', label: '标签', recipient: '收件人', phone: '电话', countryCode: '国家代码', province: '省/州', city: '城市', district: '区县',
  addressLine: '详细地址', postalCode: '邮编', addressLine2: '补充地址', defaultAddress: '默认地址', update: '更新', addAddress: '新增地址', cancel: '取消',
  default: '默认', selected: '已选择', edit: '编辑', setDefault: '设为默认', useForCheckout: '用于结算', delete: '删除', noAddresses: '还没有收货地址。',
  addressSaved: '地址已保存。', checkoutAddressSelected: '已选择结算地址。',
  paymentChapter: '第 05A-6 章', paymentLab: '多渠道支付实验', paymentIntro: '创建支付单、进入模拟渠道收银台，并观察签名异步通知结果。',
  realisticFlow: '仿真学习流程', realisticFlowDetail: '商户请求 → 渠道收银台 → 签名回调 → 事务发件箱 → Kafka 投影。',
  order: '订单', paymentChannel: '支付渠道', selectProvider: '选择模拟支付渠道', serverCredentials: '服务端保管凭据', merchantNumber: '商户号', applicationId: '应用 ID', protocol: '协议',
  secretsBackend: '密钥始终保存在 Spring Boot 后端，绝不会发送到当前页面。', idempotencyKey: '幂等键', idempotencyHint: '保存在当前浏览器会话中，请求结果不确定时可安全重试。',
  generateKey: '生成新幂等键', creating: '正在创建…', createPayment: '创建支付', advancedExperiments: '高级幂等实验',
  replayExplanation: '精确重放会返回原支付单；使用同一幂等键切换渠道必须返回 HTTP 409。', replayExact: '精确重放请求', reuseAnotherChannel: '换渠道复用幂等键',
  latestPayment: '最近支付', paymentNumber: '支付单号', channelTransaction: '渠道交易号', amount: '金额', created: '创建时间', paid: '支付时间', qrPayload: '二维码载荷',
  openCashier: '打开渠道收银台', refreshStatus: '刷新回调状态', requestCreated: '支付请求已创建', providerResult: '已收到渠道结果', orderPaidEvent: '订单已支付且事件已发布',
  replayLast: '重放上次幂等请求', noOrders: '还没有订单', noOrdersHint: '请先添加商品到购物车、选择收货地址并结算，以开始支付实验。',
  yourReview: '填写评价', submitReview: '提交评价', reviewed: '已评价', confirmReceipt: '确认收货', signedCallbackPaid: '已收到签名回调，订单支付成功。',
  waitingCallback: '仍在等待支付渠道的异步回调。', cashierOpened: '收银台已打开，请在其中完成或取消支付，然后刷新支付状态。', receiptConfirmed: '已确认收货，现在可以评价每件商品。',
  reviewSubmitted: '评价已提交，谢谢。', replayingRequest: '正在重放已保存的幂等请求…', submittingPayment: '正在提交支付请求…',
  paymentPending: '支付单 {payment} 正在处理中，请打开渠道收银台并等待签名回调。', paymentRequestFailed: '支付请求失败，原幂等键已保留，可安全重试。',
  alipay: '支付宝', wechatPay: '微信支付', unionPay: '银联支付', alipayDescription: '使用 RSA 签名商户请求的应用支付。', wechatDescription: '使用演示 API 密钥的二维码支付。', unionPayDescription: '使用模拟银联证书的网关支付。',
  userList: '用户列表', userDetails: '查看详情', detailClicked: '已点击用户 {id} 的详情', orderFulfillment: '订单履约', fulfillmentChapter: '第 06 章 · 商家',
  fulfillmentHelp: '确认已支付订单并记录可审计的物流信息。', confirmOrder: '确认订单', carrier: '承运商', tracking: '物流单号', markShipped: '确认发货',
  noMerchantOrders: '暂无商家订单。', shipTo: '收货地址', orderConfirmed: '订单 {order} 已确认。', orderShipped: '订单 {order} 已发货。',
  pageNotFound: '页面不存在', pageNotFoundDetail: '你访问的页面不存在。', goHome: '返回首页',
  PENDING_PAYMENT: '待支付', PAID: '已支付', CONFIRMED: '已确认', SHIPPED: '已发货', RECEIVED: '已收货',
  simulatedDecline: '模拟支付被拒绝', PENDING: '处理中', SUCCESS: '成功', FAILED: '失败', CLOSED: '已关闭', EXPIRED: '已过期'
};

@Injectable({ providedIn: 'root' })
export class I18nService {
  private state = signal<Locale>((localStorage.getItem('commerceflow-locale') as Locale) || 'en');
  readonly locale = this.state.asReadonly();

  t(key: string, params?: Record<string, string | number>): string {
    let value = (this.state() === 'zh-CN' ? zh : en)[key] || key;
    for (const [name, replacement] of Object.entries(params || {})) value = value.replace(`{${name}}`, String(replacement));
    return value;
  }

  switch(): void {
    const next = this.state() === 'en' ? 'zh-CN' : 'en';
    localStorage.setItem('commerceflow-locale', next);
    this.state.set(next);
    window.location.reload();
  }
}
