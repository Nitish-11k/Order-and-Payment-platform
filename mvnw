#!/bin/sh
set -e

DIRNAME="$(dirname "$0")"
WRAPPER_JAR="$DIRNAME/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading Maven Wrapper JAR..."
    mkdir -p "$DIRNAME/.mvn/wrapper"
    curl -fsSL https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar -o "$WRAPPER_JAR"
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" -Dmaven.multiModuleProjectDirectory="$DIRNAME" -cp "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
