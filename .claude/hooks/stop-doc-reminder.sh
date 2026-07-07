#!/bin/bash
# Stop hook：git status 检测本回合改动 → 提醒对应 docs（按 CLAUDE.md 文档维护工作流）
# 仅提醒（exit 0，不阻断）；输出到 stderr 让 Claude 看到并自查
# 规则文件化（CLAUDE.md 是软约束，本 hook 是硬触发提醒，互补）
PROJECT_DIR="/Users/mac/Downloads/demo"
cd "$PROJECT_DIR" 2>/dev/null || exit 0
CHANGED=$(git status --short 2>/dev/null)
[ -z "$CHANGED" ] && exit 0

WARN=""
echo "$CHANGED" | grep -qE 'Controller\.java$' && WARN+="  • Controller 变更 → 核对 docs/api/*.md（接口契约）\n"
echo "$CHANGED" | grep -q 'application\.properties' && WARN+="  • 配置变更 → 核对 docs/deployment.md（部署/环境变量）\n"
echo "$CHANGED" | grep -qE '(EmergencyService|OrderLifecycleService|DispatchService|OrderCreationService)\.java$' && WARN+="  • 核心服务变更 → 核对 ISSUES.md（缺陷状态）/ CHANGELOG.md（版本日志）\n"
echo "$CHANGED" | grep -qE '(websocket/.*Handler|WebSocketConfig|UnifiedSessionRegistry)\.java$' && WARN+="  • WebSocket 变更 → 核对 docs/websocket-protocol.md\n"
echo "$CHANGED" | grep -qE '(SecurityConfig|JwtFilter|RateLimitInterceptor|TokenBlacklistService)\.java$' && WARN+="  • 安全变更 → 核对 ISSUES.md / CLAUDE.md（安全规则）\n"

if [ -n "$WARN" ]; then
  echo "📋 [文档维护提醒] 本回合改动可能涉及以下文档，按 CLAUDE.md 工作流核对（确认即可，不必立即改）：" >&2
  echo -e "$WARN" >&2
fi
exit 0
