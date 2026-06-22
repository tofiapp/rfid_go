#!/bin/sh
# Gradle start up script for POSIX
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
APP_HOME=$( cd "$( dirname "$0" )" >/dev/null 2>&1 && pwd )
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
