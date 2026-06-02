# 微信老年助手 - 日志监控指南

## 概述
本文档提供微信老年助手项目的日志监控方法，帮助开发者在测试过程中实时查看应用运行状态。

## 关键日志标签

### 1. 无障碍服务相关
- `WeChatAssistService`: 无障碍服务核心日志
  - 服务连接/断开事件
  - 页面识别结果
  - 窗口状态变化
  
### 2. 悬浮球服务相关
- `FloatingBallService`: 悬浮球服务日志
  - 服务启动/停止
  - 按钮点击事件
  - 事务执行状态

### 3. 主活动相关
- `MainActivity`: 主界面日志
  - 权限检查
  - 服务启动请求

### 4. 页面检测相关
- `WeChatAnchorPageDetector`: 页面锚点检测器
  - 锚点匹配过程
  - 页面类型识别结果

### 5. 其他重要标签
- `WeChatWindowTreeProvider`: 窗口树提供者
- `AccessibilityTreeInspector`: 无障碍树检查器
- `TransactionScheduler`: 事务调度器

## ADB日志监控命令

### 基本日志监控
```bash
# 监控所有相关日志
adb logcat | grep -E "(WeChatAssistService|FloatingBallService|MainActivity|WeChatAnchorPageDetector)"

# 仅显示错误和警告级别日志
adb logcat *:W | grep -E "(WeChatAssistService|FloatingBallService|MainActivity|WeChatAnchorPageDetector)"
```

### 按标签过滤
```bash
# 无障碍服务日志
adb logcat WeChatAssistService:V *:S

# 悬浮球服务日志
adb logcat FloatingBallService:V *:S

# 页面检测器日志
adb logcat WeChatAnchorPageDetector:V *:S
```

### 保存日志到文件
```bash
# 实时保存日志到文件
adb logcat | tee wechat_assist_log.txt

# 仅保存特定标签的日志
adb logcat WeChatAssistService:V FloatingBallService:V *:S > wechat_service_log.txt
```

### 清除日志缓冲区
```bash
# 开始新的测试前清除旧日志
adb logcat -c
```

## 测试流程中的关键日志点

### 1. 应用启动阶段
- 检查无障碍服务是否启用
- 验证悬浮窗权限
- 确认服务正常启动

### 2. 微信交互阶段
- 观察窗口状态变化事件
- 监控页面识别准确性
- 跟踪事务执行情况

### 3. 问题排查重点
- 服务连接失败
- 页面识别错误
- 权限不足问题
- 节点获取异常

## 常见日志模式

### 成功启动序列
```
WeChatAssistService: ========================================
WeChatAssistService: 无障碍服务已连接！
WeChatAssistService: 服务类名: com.example.wechat_senior_helper.service.WeChatAssistAccessibilityService
FloatingBallService: ✅ 悬浮球已显示
```

### 页面识别成功
```
WeChatAssistService: 🔍 开始获取稳定微信树...
WeChatAnchorPageDetector: ========== 开始锚点匹配 ==========
WeChatAnchorPageDetector: 📱 页面识别结果: WECHAT_HOME
WeChatAssistService: 📱 当前页面: WECHAT_HOME
```

### 错误情况示例
```
MainActivity: ❌ 未授予悬浮窗权限
WeChatAssistService: 未获取到稳定微信树
FloatingBallService: ❌ 启动微信失败: ...
```

## 调试建议

1. **测试前准备**
   - 确保设备已连接并授权ADB
   - 清除之前的日志记录
   - 准备好测试场景

2. **实时监控技巧**
   - 使用多个终端窗口分别监控不同组件
   - 重点关注ERROR和WARN级别日志
   - 注意时间戳以追踪事件顺序

3. **性能监控**
   - 关注检测节流机制是否正常工作
   - 监控内存使用情况
   - 观察是否有频繁的GC操作

4. **问题定位**
   - 当出现异常时，查看完整的堆栈跟踪
   - 对比预期行为和实际日志输出
   - 检查权限和服务状态