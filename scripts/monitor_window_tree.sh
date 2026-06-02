#!/bin/bash

# 微信窗口树诊断日志监控脚本
# 用途：监控 WeChatWindowTreeProvider 的详细诊断日志
# 作者：moxuan

echo "=========================================="
echo "微信窗口树诊断日志监控"
echo "=========================================="
echo ""
echo "监控目标："
echo "1. 所有窗口的详细信息 (id/type/layer/isActive/isFocused/pkg/className/childCount)"
echo "2. 候选微信窗口选择过程"
echo "3. childCount=0 的问题诊断"
echo "4. rootInActiveWindow 备选方案"
echo ""
echo "按 Ctrl+C 停止监控"
echo "=========================================="
echo ""

# 清除之前的日志
adb logcat -c

# 开始监控，过滤 WeChatWindowTreeProvider 标签的日志
# 显示 VERBOSE 级别及以上的所有日志
adb logcat WeChatWindowTreeProvider:V *:S | while read line; do
    # 高亮显示关键信息
    if echo "$line" | grep -q "窗口\["; then
        # 窗口列表信息 - 蓝色
        echo -e "\033[34m$line\033[0m"
    elif echo "$line" | grep -q "找到候选微信窗口"; then
        # 候选窗口 - 绿色
        echo -e "\033[32m$line\033[0m"
    elif echo "$line" | grep -q "选择窗口"; then
        # 最终选择的窗口 - 绿色加粗
        echo -e "\033[1;32m$line\033[0m"
    elif echo "$line" | grep -q "备选 rootInActiveWindow"; then
        # 备选方案 - 黄色
        echo -e "\033[33m$line\033[0m"
    elif echo "$line" | grep -q "childCount=0\|childCount == 0"; then
        # childCount为0的警告 - 红色
        echo -e "\033[31m$line\033[0m"
    elif echo "$line" | grep -q "✅"; then
        # 成功信息 - 绿色
        echo -e "\033[32m$line\033[0m"
    elif echo "$line" | grep -q "❌\|未找到"; then
        # 错误信息 - 红色
        echo -e "\033[31m$line\033[0m"
    else
        # 普通日志
        echo "$line"
    fi
done
