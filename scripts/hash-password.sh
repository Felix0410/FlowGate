#!/bin/sh
# 从已构建 Control Jar 提取 BCrypt 工具依赖；密码从隐藏输入或 stdin 读取。
set -eu
task_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
task_libs=$(mktemp -d)
trap 'rm -rf "$task_libs"' EXIT HUP INT TERM
unzip -j -q "$task_root/flowgate-control/target/flowgate-control-0.0.1-SNAPSHOT.jar" 'BOOT-INF/lib/spring-security-crypto-*.jar' 'BOOT-INF/lib/commons-logging-*.jar' -d "$task_libs"
"${JAVA_HOME:?请先选择 JDK17}/bin/java" -cp "$task_libs/*" "$task_root/scripts/PasswordHashTool.java"
