#!/bin/sh
: << '::CMD'
@echo off
if defined JAVA_HOME (set "JAVA_CMD=%JAVA_HOME%\bin\java") else (set "JAVA_CMD=java")
"%JAVA_CMD%" -jar "%~dp0..\lib\intellij-debug-mcp-proxy.jar" %*
exit /b
::CMD
JAVA_CMD="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_CMD="${JAVA_CMD:-java}"
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$JAVA_CMD" -jar "$DIR/../lib/intellij-debug-mcp-proxy.jar" "$@"
