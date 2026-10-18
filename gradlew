#!/bin/sh
# Gradle wrapper script (minimal, for CI use).
APP_BASE_NAME=${0##*/}
APP_HOME=${0%/*}
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
