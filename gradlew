#!/bin/sh

# Gradle wrapper launcher. The repository copy must be executable on CI.

APP_HOME=${0%"${0##*/}"}
APP_HOME=$( cd -P "${APP_HOME:-./}" > /dev/null && printf '%s\n' "$PWD" ) || exit

JAVACMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [ -z "$JAVACMD" ] || [ ! -x "$JAVACMD" ]; then
    JAVACMD=java
fi

exec "$JAVACMD" -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
