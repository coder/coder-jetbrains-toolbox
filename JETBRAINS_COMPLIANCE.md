# JetBrains Auto-Approval Compliance

This document describes the linting setup to ensure compliance with JetBrains auto-approval requirements for Toolbox plugins.

## Overview

JetBrains has enabled auto-approval for this plugin, which requires following specific guidelines to maintain the approval status. This repository includes automated checks to ensure compliance.

## Requirements

Based on communication with JetBrains team, the following requirements must be met:

### ✅ Allowed
- **Coroutines**: Use `coroutineScope.launch` for concurrent operations
- **Library-managed threads**: Libraries like OkHttp with their own thread pools are acceptable
- **Some experimental coroutines APIs**: `kotlinx.coroutines.selects.select` and `kotlinx.coroutines.selects.onTimeout` are acceptable
- **Proper cleanup**: Ensure resources are released in `CoderRemoteProvider#close()` method

### ❌ Forbidden
- **Kotlin experimental APIs**: Core Kotlin experimental APIs (not coroutines-specific ones)
- **Java runtime hooks**: No lambdas, handlers, or class handles to Java runtime hooks
- **Manual thread creation**: Avoid `Thread()`, `Executors.new*()`, `ThreadPoolExecutor`, etc.
- **Bundled libraries**: Don't bundle libraries already provided by Toolbox
- **Ill-intentioned actions**: No malicious or harmful code

## Linting Setup

### JetBrains Compliance with Detekt

The primary compliance checking is done using Detekt with custom configuration in `detekt.yml`:

```bash
./gradlew detekt
```

This configuration includes JetBrains-specific rules that check for:
- **ForbiddenAnnotation**: Detects forbidden experimental API usage
- **ForbiddenMethodCall**: Detects Java runtime hooks and manual thread creation
- **ForbiddenImport**: Detects potentially bundled libraries
- **Standard code quality rules**: Complexity, naming, performance, etc.

## CI/CD Integration

The GitHub Actions workflow `.github/workflows/jetbrains-compliance.yml` runs compliance checks on every PR and push.

## Running Locally

```bash
# Run JetBrains compliance and code quality check
./gradlew detekt

# View HTML report
open build/reports/detekt/detekt.html
```

## Understanding Results

### Compliance Check Results

- **✅ No critical violations**: Code complies with JetBrains requirements
- **❌ Critical violations**: Must be fixed before auto-approval
- **⚠️ Warnings**: Should be reviewed but may be acceptable

### Common Warnings

1. **Manual thread creation**: If you see warnings about thread creation:
   - Prefer coroutines: `coroutineScope.launch { ... }`
   - If using libraries with threads, ensure cleanup in `close()`

2. **Library imports**: If you see warnings about library imports:
   - Verify the library isn't bundled in the final plugin
   - Check that Toolbox doesn't already provide the library

3. **GlobalScope usage**: If you see warnings about `GlobalScope`:
   - Use the coroutine scope provided by Toolbox instead

## Resources

- [JetBrains Toolbox Plugin Development](https://plugins.jetbrains.com/docs/toolbox/)
- [Detekt Documentation](https://detekt.dev/)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
