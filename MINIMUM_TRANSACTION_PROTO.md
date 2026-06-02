# 最小事务原型架构设计

## 原型目标

**事务名：** `tx_back_once`  
**验证目标：** 无障碍识别、页面判断、动作执行、结果校验四个环节的连通性  
**业务范围：** 仅验证单次返回动作，不处理复杂业务逻辑

---

## 核心架构原则

### 1. 状态驱动
事务执行采用**状态机模式**，每个步骤都有明确的状态标识，避免逻辑散落在各处。

### 2. 模块分离
- **窗口检测器** - 只负责找到微信root节点
- **页面识别器** - 只负责判断当前页面类型
- **事务调度器** - 只负责任务编排和状态推进
- **动作执行器** - 只负责执行具体动作
- **结果验证器** - 只负责验证动作效果

### 3. 资源管理
所有 `AccessibilityNodeInfo` 必须在使用后调用 `recycle()`，避免内存泄漏。

### 4. 异步执行
事务执行是suspend函数，运行在协程中，不阻塞UI线程。

---

## 模块划分

### 1. Transaction.kt - 数据模型

```kotlin
enum class TransactionStatus {
    WAITING,
    DETECT_WECHAT_WINDOW,
    DETECT_CHAT_PAGE,
    EXECUTE_BACK,
    VERIFY_PAGE_CHANGED,
    SUCCESS,
    FAIL
}

data class Transaction(
    val txId: String,
    val type: TransactionType,
    val targetApp: String,
    val precondition: String,
    val action: String,
    val verify: String,
    var status: TransactionStatus,
    var reason: String,
    val createdAt: Long,
    var completedAt: Long?
)
```

**职责：** 定义事务的数据结构和状态枚举

---

### 2. WindowDetector.kt - 窗口检测器

```kotlin
object WindowDetector {
    fun detectWeChatWindow(retryCount: Int = 3, retryDelay: Long = 500): AccessibilityNodeInfo?
    fun isWeChatWindowActive(): Boolean
}
```

**职责：**
- 从系统窗口中找到微信的root节点
- 提供重试机制应对竞态条件
- 验证包名是否为 `com.tencent.mm`

**关键设计：**
- 带重试的轮询机制（默认3次，每次间隔500ms）
- 快速检查方法（无重试）用于实时判断

---

### 3. ResultVerifier.kt - 结果验证器

```kotlin
object ResultVerifier {
    fun verifyPageChanged(rootBefore: AccessibilityNodeInfo?, rootAfter: AccessibilityNodeInfo?): Boolean
    fun verifyPageType(root: AccessibilityNodeInfo?, expectedPageType: PageType): Boolean
    fun getResultDescription(...): String
}
```

**职责：**
- 比较动作前后的页面类型
- 判断页面是否发生预期变化
- 生成人类可读的验证结果描述

**关键设计：**
- 自动回收传入的节点引用
- 支持页面类型匹配验证
- 提供详细的验证日志

---

### 4. ActionExecutor.kt - 动作执行器

```kotlin
object ActionExecutor {
    fun executeGlobalBack(): Boolean
    fun executeClickByText(text: String): Boolean
    fun getActionDescription(actionType: String, success: Boolean): String
}
```

**职责：**
- 封装无障碍动作执行
- 统一的动作日志输出
- 为未来扩展预留接口（点击、输入等）

**关键设计：**
- 委托给 `AccessibilityActionHelper` 执行
- 统一的日志格式
- 返回布尔值表示执行成功与否

---

### 5. TransactionScheduler.kt - 事务调度器

```kotlin
object TransactionScheduler {
    suspend fun executeBackOnceTransaction(): Transaction
    fun getStatusDescription(transaction: Transaction): String
}
```

**职责：**
- 按状态机顺序推进事务执行
- 协调各模块的调用时机
- 处理异常和资源清理
- 提供状态描述供UI显示

**关键设计：**
- 使用 `suspend` 函数支持协程
- try-catch-finally 确保资源回收
- 每个步骤都有明确的日志输出
- 失败时立即返回并记录原因

---

## 事务执行流程

```
WAITING
   ↓
DETECT_WECHAT_WINDOW  ← WindowDetector.detectWeChatWindow()
   ↓ (success)
DETECT_CHAT_PAGE      ← WeChatPageDetector.detectPageType()
   ↓ (success)
EXECUTE_BACK          ← ActionExecutor.executeGlobalBack()
   ↓ (success + delay)
VERIFY_PAGE_CHANGED   ← ResultVerifier.verifyPageChanged()
   ↓
SUCCESS / FAIL
```

### 详细步骤说明

#### Step 1: 检测微信窗口
```kotlin
transaction.updateStatus(DETECT_WECHAT_WINDOW)
rootBefore = WindowDetector.detectWeChatWindow(retryCount = 3, retryDelay = 500)
if (rootBefore == null) {
    transaction.markFailed("未能检测到微信窗口")
    return transaction
}
```

**可能失败原因：**
- 无障碍服务未启动
- 当前前台不是微信应用
- 窗口获取超时

---

#### Step 2: 识别页面类型
```kotlin
transaction.updateStatus(DETECT_CHAT_PAGE)
val pageTypeBefore = WeChatPageDetector.detectPageType(rootBefore)
if (pageTypeBefore == UNKNOWN) {
    transaction.markFailed("无法识别当前页面类型")
    rootBefore.recycle()
    return transaction
}
```

**可能失败原因：**
- 页面特征不明显
- UI树结构异常
- 微信版本差异导致识别失败

---

#### Step 3: 执行返回动作
```kotlin
transaction.updateStatus(EXECUTE_BACK)
delay(500)  // 等待页面稳定
val actionSuccess = ActionExecutor.executeGlobalBack()
if (!actionSuccess) {
    transaction.markFailed("返回动作执行失败")
    rootBefore.recycle()
    return transaction
}
delay(1000)  // 等待页面切换
```

**可能失败原因：**
- 无障碍服务权限不足
- 系统拦截了返回动作
- 当前页面不允许返回

---

#### Step 4: 验证页面变化
```kotlin
transaction.updateStatus(VERIFY_PAGE_CHANGED)
rootAfter = WindowDetector.detectWeChatWindow(retryCount = 2, retryDelay = 300)
if (rootAfter == null) {
    transaction.markFailed("动作后未能检测到微信窗口")
    rootBefore.recycle()
    return transaction
}
val pageChanged = ResultVerifier.verifyPageChanged(rootBefore, rootAfter)
if (pageChanged) {
    transaction.markSuccess()
} else {
    transaction.markFailed("页面未发生变化")
}
```

**可能失败原因：**
- 页面切换动画未完成
- 前后都是同一页面（已在列表页）
- 窗口检测失败

---

## UI集成

### MainActivity集成点

```kotlin
@Composable
fun MainScreen(...) {
    var currentTransaction by remember { mutableStateOf<Transaction?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    Button(
        onClick = {
            if (!isExecuting && isServiceRunning) {
                isExecuting = true
                coroutineScope.launch {
                    currentTransaction = TransactionScheduler.executeBackOnceTransaction()
                    isExecuting = false
                }
            }
        },
        enabled = isServiceRunning && !isExecuting
    ) {
        Text(if (isExecuting) "执行中..." else "执行返回事务")
    }
    
    // 显示事务状态
    currentTransaction?.let { tx ->
        Text(TransactionScheduler.getStatusDescription(tx))
        if (tx.isCompleted()) {
            Text("耗时: ${tx.duration()}ms")
        }
    }
}
```

**UI特性：**
- 按钮在服务运行时才可用
- 执行期间禁用按钮防止重复触发
- 实时显示事务状态
- 完成后显示耗时

---

## 日志规范

### 日志级别

- `Log.e` - 关键事件（事务开始/结束、成功/失败）
- `Log.d` - 调试信息（步骤进展、中间状态）
- `Log.w` - 警告信息（重试、非致命错误）

### 日志格式

```
========================================
🚀 开始执行事务: tx_back_once_1234567890
事务类型: BACK_ONCE
========================================
📍 步骤1: 检测微信窗口...
✅ 成功检测到微信窗口
📍 步骤2: 识别当前页面...
当前页面类型: CHAT_DETAIL
✅ 页面识别成功: CHAT_DETAIL
📍 步骤3: 执行返回动作...
✅ 返回动作执行成功
⏳ 等待页面切换...
📍 步骤4: 验证页面变化...
========== 页面变化验证 ==========
动作前页面类型: CHAT_DETAIL
动作后页面类型: CHAT_LIST
页面是否变化: ✅ 是
========== 验证完成 ==========
========================================
🎉 事务执行成功!
事务ID: tx_back_once_1234567890
耗时: 2345ms
========================================
```

---

## 测试场景

### 场景1：聊天详情页 → 会话列表页
**前置条件：** 打开任意聊天窗口  
**预期结果：** 返回到会话列表页  
**验证点：** 页面类型从 `CHAT_DETAIL` 变为 `CHAT_LIST`

### 场景2：已在会话列表页
**前置条件：** 停留在会话列表页  
**预期结果：** 可能退出微信或无变化  
**验证点：** 页面类型可能从 `CHAT_LIST` 变为 `UNKNOWN`

### 场景3：非微信应用
**前置条件：** 前台是其他应用  
**预期结果：** 事务失败  
**验证点：** 失败原因为"未能检测到微信窗口"

### 场景4：无障碍服务未开启
**前置条件：** 服务未启动  
**预期结果：** 事务失败  
**验证点：** 失败原因为"无障碍服务未启动"

---

## 已集成的功能

✅ 无障碍服务启动与状态感知  
✅ 微信前台窗口识别  
✅ 微信页面识别  
✅ 单次系统返回动作执行  
✅ 动作前后状态校验  
✅ 事务日志记录  
✅ UI状态回显/调试显示  

---

## 未集成的功能（后续扩展）

❌ ASR语音识别  
❌ TTS语音播报  
❌ 混元大模型意图理解  
❌ 联系人检索  
❌ 消息发送完整链路  
❌ 风险词拦截与二次确认  

---

## 关键文件清单

- `Transaction.kt` - 事务数据模型
- `WindowDetector.kt` - 窗口检测器
- `ResultVerifier.kt` - 结果验证器
- `ActionExecutor.kt` - 动作执行器
- `TransactionScheduler.kt` - 事务调度器
- `MainActivity.kt` - UI集成（修改）

---

## 下一步扩展方向

### 1. 多事务支持
添加更多事务类型：
- `tx_find_contact` - 查找联系人
- `tx_send_message` - 发送消息
- `tx_open_moments` - 打开朋友圈

### 2. 事务队列
实现事务队列管理器，支持：
- 事务排队执行
- 事务取消
- 事务重试策略

### 3. 语音交互
集成ASR/TTS：
- 语音指令识别
- 执行结果语音反馈
- 危险操作语音确认

### 4. 智能意图
接入大模型：
- 自然语言理解
- 多步任务规划
- 上下文记忆

---

**作者：** moxuan  
**创建日期：** 2026-06-02  
**架构原则：** 最小闭环 > 复杂功能 | 状态驱动 > 过程驱动
