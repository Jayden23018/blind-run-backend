#!/bin/bash
# Stop hook：本回合改/新增了 .java → gradlew compileJava 验证编译
# - 编译失败 → exit 2（阻止 Stop，把错误喂给 Claude 强制继续修复）
# - 编译成功 → exit 0（输出确认）
# - 无 .java 改动 → exit 0（跳过，避免拖慢）
# 参考社区共识：Stop hook 做 end-of-turn 确定性质量门禁（PubNub/Claude Code 官方）
PROJECT_DIR="/Users/mac/Downloads/demo"
cd "$PROJECT_DIR" 2>/dev/null || exit 0

# git status --short 含 untracked(??)/modified(M)/added(A)，覆盖新建+修改的 .java
if ! git status --short 2>/dev/null | grep -qE '\.java$'; then
  exit 0
fi

# 增量编译，通常很快（只编译改动的类）
OUTPUT=$("$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" compileJava -q 2>&1)
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
  echo "❌ [Stop hook] Java 编译失败（gradlew compileJava），请先修复再结束本轮：" >&2
  echo "$OUTPUT" | tail -30 >&2
  exit 2
fi
echo "✅ [Stop hook] Java 编译通过（gradlew compileJava）"
exit 0
