# 何时使用 Mock

仅在**系统边界**处使用 Mock：

- 外部 API（支付、邮件等）
- 数据库（有时——优先使用测试数据库）
- 时间/随机数
- 文件系统（有时）

不要 Mock：

- 你自己的类/模块
- 内部协作者
- 任何你控制范围内的东西

## 设计可 Mock 的接口

在系统边界处，设计易于 Mock 的接口：

**1. 使用依赖注入**

将外部依赖作为参数传入，而不是在内部创建它们：

```typescript
// 易于 mock
function processPayment(order, paymentClient) {
  return paymentClient.charge(order.total);
}

// 难以 mock
function processPayment(order) {
  const client = new StripeClient(process.env.STRIPE_KEY);
  return client.charge(order.total);
}
```

**2. 优先使用 SDK 风格的接口，而非通用请求器**

为每个外部操作创建具体的函数，而非一个带条件逻辑的通用函数：

```typescript
// 好：每个函数可独立 mock
const api = {
  getUser: (id) => fetch(`/users/${id}`),
  getOrders: (userId) => fetch(`/users/${userId}/orders`),
  createOrder: (data) => fetch('/orders', { method: 'POST', body: data }),
};

// 坏：mock 时需要在内部写条件逻辑
const api = {
  fetch: (endpoint, options) => fetch(endpoint, options),
};
```

SDK 方式的好处：
- 每个 mock 返回一种特定的数据形态
- 测试设置中无需条件逻辑
- 更容易看出测试涉及了哪些端点
- 每个端点有类型安全
