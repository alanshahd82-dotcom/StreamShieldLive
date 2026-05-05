#!/bin/sh
APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
APP_NAME="Gradle"
APP_BASE_NAME="$(basename "$0")"
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD=maximum
warn() { echo "$*"; } >&2
die() { echo; echo "$*"; echo; exit 1; } >&2
case "$(uname)" in
  CYGWIN*) cygwin=true;;
  Darwin*) darwin=true;;
  MSYS*|MINGW*) msys=true;;
  NONSTOP*) nonstop=true;;
esac
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ -n "$JAVA_HOME" ]; then
  if [ -x "$JAVA_HOME/jre/sh/java" ]; then
    JAVACMD="$JAVA_HOME/jre/sh/java"
  else
    JAVACMD="$JAVA_HOME/bin/java"
  fi
  [ -x "$JAVACMD" ] || die "JAVA_HOME is set to an invalid directory: $JAVA_HOME"
else
  JAVACMD=java
  which java >/dev/null 2>&1 || die "JAVA_HOME is not set and no 'java' found in PATH."
fi
if ! "$cygwin" && ! "$darwin" && ! "$nonstop"; then
  case $MAX_FD in
    max*) MAX_FD="$(ulimit -H -n)" || warn "Could not query max file descriptor limit";;
  esac
  case $MAX_FD in
    ''|soft) :;;
    *) ulimit -n "$MAX_FD" || warn "Could not set max file descriptor limit to $MAX_FD";;
  esac
fi
if "$cygwin" || "$msys"; then
  APP_HOME="$(cygpath --path --mixed "$APP_HOME")"
  CLASSPATH="$(cygpath --path --mixed "$CLASSPATH")"
  JAVACMD="$(cygpath --unix "$JAVACMD")"
fi
eval "set -- $(printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" | xargs -n1 | sed 's~[^-[:alnum:]+,./:=@_]~\\&~g;' | tr '\n' ' ')" '"$@"'
exec "$JAVACMD" "-Dorg.gradle.appname=$APP_BASE_NAME" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
