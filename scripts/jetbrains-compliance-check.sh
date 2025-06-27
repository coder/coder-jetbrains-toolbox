#!/bin/bash

# JetBrains Auto-Approval Compliance Check Script
# This script checks for violations of JetBrains auto-approval requirements

set -e

echo "🔍 JetBrains Auto-Approval Compliance Check"
echo "==========================================="
echo

VIOLATIONS=0
SOURCE_DIR="src/main/kotlin"

# Function to report violations
report_violation() {
    echo "❌ VIOLATION: $1"
    echo "   File: $2"
    echo "   Line: $3"
    echo "   Context: $4"
    echo
    VIOLATIONS=$((VIOLATIONS + 1))
}

# Function to report warnings
report_warning() {
    echo "⚠️  WARNING: $1"
    echo "   File: $2"
    echo "   Line: $3"
    echo "   Context: $4"
    echo
}

echo "1. Checking for experimental API usage..."
# Check for forbidden experimental annotations (excluding acceptable coroutines ones)
grep -rn "@ExperimentalApi\|@ExperimentalStdlibApi\|@ExperimentalUnsignedTypes\|@ExperimentalContracts\|@ExperimentalTypeInference\|@InternalCoroutinesApi\|@ExperimentalTime" $SOURCE_DIR 2>/dev/null | while IFS=: read -r file line content; do
    report_violation "Forbidden experimental API usage" "$file" "$line" "$content"
done

# Check for @OptIn with forbidden experimental APIs
grep -rn "@OptIn.*ExperimentalApi\|@OptIn.*ExperimentalStdlibApi\|@OptIn.*InternalCoroutinesApi" $SOURCE_DIR 2>/dev/null | while IFS=: read -r file line content; do
    report_violation "@OptIn with forbidden experimental API" "$file" "$line" "$content"
done

echo "2. Checking for manual thread creation..."
# Check for direct thread creation
grep -rn "Thread(\|ThreadPoolExecutor\|ScheduledThreadPoolExecutor\|ForkJoinPool\|Timer(\|TimerTask" $SOURCE_DIR 2>/dev/null | while IFS=: read -r file line content; do
    report_warning "Manual thread creation detected - ensure proper cleanup in CoderRemoteProvider#close()" "$file" "$line" "$content"
done

# Check for Executors usage
grep -rn "Executors\.new\|CompletableFuture\.runAsync\|CompletableFuture\.supplyAsync" $SOURCE_DIR 2>/dev/null | while IFS=: read -r file line content; do
    report_warning "Executor/CompletableFuture usage detected - ensure proper cleanup in CoderRemoteProvider#close()" "$file" "$line" "$content"
done

# Check for classes extending Thread or implementing Runnable
grep -rn "class.*extends Thread\|class.*implements Runnable\|: Thread\|: Runnable" $SOURCE_DIR 2>/dev/null | while IFS=: read -r file line content; do
    report_warning "Class extending Thread or implementing Runnable - consider using coroutines" "$file" "$line" "$content"
done

echo "3. Checking for Java runtime hooks..."
# Check for runtime hooks
grep -rn "Runtime\..*addShutdownHook\|System\.setSecurityManager\|setUncaughtExceptionHandler\|setDefaultUncaughtExceptionHandler" $SOURCE_DIR 2>/dev/null | while IFS=: read -r file line content; do
    report_violation "Java runtime hook usage forbidden" "$file" "$line" "$content"
done

# Check for suspicious system property modifications
grep -rn "System\.setProperty.*java\.security\|System\.setProperty.*java\.awt\.headless\|System\.setProperty.*file\.encoding" $SOURCE_DIR 2>/dev/null | while IFS=: read -r file line content; do
    report_violation "Suspicious system property modification" "$file" "$line" "$content"
done

echo "4. Checking for bundled libraries..."
# Check for imports that might indicate bundled libraries
grep -rn "import org\.slf4j\|import org\.jetbrains\.annotations" $SOURCE_DIR 2>/dev/null | while IFS=: read -r file line content; do
    report_warning "Import of potentially bundled library - ensure it's not bundled" "$file" "$line" "$content"
done

echo "5. Checking for coroutines best practices..."
# Check for GlobalScope usage (should use provided scope)
grep -rn "GlobalScope\.launch\|GlobalScope\.async" $SOURCE_DIR 2>/dev/null | while IFS=: read -r file line content; do
    report_warning "GlobalScope usage detected - consider using provided coroutine scope" "$file" "$line" "$content"
done

echo "==========================================="
if [ $VIOLATIONS -eq 0 ]; then
    echo "✅ No critical violations found!"
    echo "   Your code appears to comply with JetBrains auto-approval requirements."
    echo
    echo "📋 Summary of requirements:"
    echo "   ✓ No forbidden Kotlin experimental APIs"
    echo "   ✓ No Java runtime hooks"
    echo "   ✓ No suspicious system modifications"
    echo "   ⚠️  Manual thread creation warnings (if any) - ensure cleanup in close()"
    echo "   ⚠️  Library bundling warnings (if any) - verify not bundling Toolbox libs"
    echo
    exit 0
else
    echo "❌ Found $VIOLATIONS critical violations!"
    echo "   Please fix these issues before submitting for auto-approval."
    echo
    exit 1
fi
