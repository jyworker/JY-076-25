---
name: dst-timezone-handling
description: 修复 Java SDK 中夏令时(DST)切换日的统计口径偏差问题，包括时区感知的日期边界处理、归档逻辑校正和补偿重算
source: auto-skill
extracted_at: '2026-06-14T20:59:28.038Z'
updated_at: '2026-06-14T13:40:15.680Z'
---

# DST 时区处理修复指南

当修复涉及夏令时(DST)切换日的统计口径偏差问题时，需要遵循以下步骤和注意事项。

## 问题背景

在使用 `java.util.Date` 的支付 SDK 中，DST 切换日会导致：
- 同一天的时间范围变为 23h（春令时）或 25h（秋令时），而非统一的 24h
- 报表统计在 DST 切换日出现偏差
- 跨日归档逻辑未考虑 DST 边界偏移

## 修复步骤

### 1. 创建统一时区的序列化配置

创建 GsonProvider 类提供统一时区配置的 Gson 实例时，必须区分不同用途的 Gson：

```java
// HTTP 请求/响应用：可 disableHtmlEscaping
private static Gson createGson() {
    return new GsonBuilder()
            .registerTypeAdapter(Date.class, new UtcDateTypeAdapter())
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
}

// 签名计算用：必须与原有行为完全一致
// 原有 new Gson() 不 serializeNulls，不 disableHtmlEscaping
private static Gson createGsonForSignature() {
    return new GsonBuilder()
            .registerTypeAdapter(Date.class, new UtcDateTypeAdapter())
            // 不要加 serializeNulls()！否则含 null 字段的请求会产生不同 JSON
            // 不要加 disableHtmlEscaping()！否则 = 等字符不会被转义为 \u003d
            .create();
}
```

**关键教训**：共享 Gson 实例时，签名计算用的 Gson 必须与原有 `new Gson()` 行为完全一致，否则会导致 HMAC 签名不匹配。`serializeNulls()` 和 `disableHtmlEscaping()` 都会改变 JSON 输出。

### 2. 创建时区安全的时间工具类

提供以下核心方法：

```java
// 计算一天的开始/结束，自动处理 DST
public static Date getStartOfDay(Date date, TimeZone timeZone);
public static Date getEndOfDay(Date date, TimeZone timeZone);

// 检测 DST 切换日（通过比较一天起止的 UTC 偏移量）
public static boolean isDaylightSavingTransitionDay(Date date, TimeZone timeZone);
public static DstTransitionInfo getDstTransitionInfo(Date date, TimeZone timeZone);

// DST 补偿的归档时间范围
// 春调：结束时间 +1h（弥补跳过的小时）
// 秋调：开始时间 -1h（弥补重复的小时）
public static Date[] getArchivalDateRangeWithDstCorrection(Date date, TimeZone timeZone);

// 业务日期规范化
public static Date normalizeToBusinessDate(Date transactionDate, Date createdDate, TimeZone timeZone);
```

### 3. 秋调(Fall Back)业务日期归属的正确逻辑

秋调日 02:00-03:00 会出现两次，需要精确处理：

```java
// 正确做法：使用 UTC 偏移量来判断
// 秋调转换点：UTC 01:00 = Berlin 03:00 CEST → 02:00 CET
// 只有 UTC 00:00-00:59（Berlin 02:00-02:59 CEST）的交易需要归入前一天
// UTC 01:00+（Berlin 02:00+ CET，即转换后的正常时间）应归属当天

// 错误做法（不要这样做）：
// transitionPoint = 当日 02:00 Berlin time，>= 02:00 全归前一天
// 这会错误地把 04:00 等正常时段的交易归到前一天
```

### 4. 修改现有代码保持向后兼容

修改 HttpClient、IyziAuthV2Generator 等现有类时：
- 确保签名计算的 JSON 输出与修改前完全一致
- 运行原有测试验证兼容性
- 不要修改无关文件

## 测试注意事项

### 时区选择（必须在实现前验证！）

**极其重要**：实施前必须先验证所选时区是否仍有 DST 规则，否则所有测试都会通过但实际无效。

以下时区自 2016 年起**没有 DST**：
- `Europe/Istanbul`（土耳其，2016年取消）
- `Asia/Shanghai`（中国）
- `Asia/Tokyo`（日本）

以下时区**有 DST**：
- `Europe/Berlin`（德国）
- `America/New_York`（美国东部）
- `America/Los_Angeles`（美国太平洋）

**验证方法**：
```java
// 在写测试之前先验证时区有 DST
TimeZone tz = TimeZone.getTimeZone("Europe/Istanbul");
Calendar cal = Calendar.getInstance(tz);
cal.set(2025, Calendar.MARCH, 30, 1, 0, 0);
int offset1 = tz.getOffset(cal.getTimeInMillis());
cal.set(2025, Calendar.MARCH, 30, 3, 0, 0);
int offset2 = tz.getOffset(cal.getTimeInMillis());
// 如果 offset1 == offset2，说明该时区在此日期没有 DST 切换
```

### 测试必须覆盖完整执行路径

不要只测试辅助方法（findDstTransitionDates、状态枚举），必须用 mock 隔离外部依赖并测试核心业务流程：

```java
// 必须测试的场景：
// 1. 补偿调度器的完整执行链：获取数据 → 过滤 → 跨日归档 → 对账 → 判定
// 2. 签名兼容性：使用含 null 字段的复杂对象（非 String）验证 HMAC 不变
// 3. 秋调日各时段交易的归属：01:30、02:30、04:00、12:00 分别归属哪天
// 4. 春调日归档范围补偿后是否正确包含边界交易

// 使用 Mockito mock 外部 API 调用
@Mock
private HttpClient httpClient;

// 使用 ArgumentCaptor 验证序列化输出
ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
verify(httpClient).post(anyString(), any(), any(), bodyCaptor.capture(), any());
String requestBody = bodyCaptor.getValue();
assertFalse("请求体不应包含 null 字段", requestBody.contains("null"));
```

### 签名兼容性测试

```java
// 测试含 null 字段的请求对象签名一致性
Map<String, Object> request = new HashMap<>();
request.put("price", "100.00");
request.put("currency", "TRY");
request.put("conversationId", null);  // null 字段

String hash = IyziAuthV2Generator.generateAuthContent(
    "/v2/uri", "apiKey", "secretKey", "random", request);

// 验证与原有行为一致（不含 "conversationId":null）
// 原有 new Gson() 会省略 null 字段
// 如果新 Gson 序列化出 null 字段，签名会不同
```

## 注意事项

1. **不要删除无关代码**：修复 DST 问题时，绝对不要删除与任务无关的 validator、其他模块的代码或测试
2. **避免过度工程化**：只实现用户明确要求的功能，不要擅自添加自动检测、线程池、重算调度等复杂机制
3. **实施前验证领域事实**：在编写测试之前先验证时区是否有 DST、夏令时切换日期是否正确
4. **完整验证**：运行 `mvn clean test` 确保所有测试通过，包括原有测试
5. **保持向后兼容**：共享配置变更（如 Gson）必须区分用途，签名计算必须与原有行为完全一致
6. **测试覆盖核心路径**：不要只测辅助方法，必须用 mock 测试涉及外部依赖的核心业务流程
