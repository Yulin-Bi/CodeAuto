# 好测试与坏测试

## 好测试

**集成风格**：通过真实接口测试，而非模拟内部组件。

```typescript
// 好：测试可观察的行为
test("用户可以用有效的购物车完成结账", async () => {
  const cart = createCart();
  cart.add(product);
  const result = await checkout(cart, paymentMethod);
  expect(result.status).toBe("confirmed");
});
```

特征：

- 测试用户/调用者关心的行为
- 只使用公共 API
- 能够经受住内部重构
- 描述"做什么"而非"怎么做"
- 每个测试只有一个逻辑断言

## 坏测试

**实现细节测试**：与内部结构耦合。

```typescript
// 坏：测试实现细节
test("结账调用了 paymentService.process", async () => {
  const mockPayment = jest.mock(paymentService);
  await checkout(cart, payment);
  expect(mockPayment.process).toHaveBeenCalledWith(cart.total);
});
```

危险信号：

- 模拟内部协作者
- 测试私有方法
- 断言调用次数/顺序
- 重构时测试失败，但行为未变
- 测试名描述"怎么做"而非"做什么"
- 通过外部手段而非接口验证

```typescript
// 坏：绕过接口去验证
test("createUser 保存到数据库", async () => {
  await createUser({ name: "Alice" });
  const row = await db.query("SELECT * FROM users WHERE name = ?", ["Alice"]);
  expect(row).toBeDefined();
});

// 好：通过接口验证
test("createUser 创建的用户可被检索到", async () => {
  const user = await createUser({ name: "Alice" });
  const retrieved = await getUser(user.id);
  expect(retrieved.name).toBe("Alice");
});
```
