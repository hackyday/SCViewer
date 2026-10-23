#!/bin/sh

##############################################################################
#
#  Gradle start up script for POSIX — auto-bootstraps the wrapper jar if
#  missing (text-only file transfer can't carry the binary jar).
#
##############################################################################

# Attempt to set APP_HOME

# Resolve links: $0 may be a link
app_path=$0

# Need this for daisy-chained symlinks.
while
    APP_HOME=${app_path%"${app_path##*/}"}  # leaves a trailing /; empty if no leading path
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in             #(
      /*)   app_path=$link ;; #(
      *)    app_path=$APP_HOME$link ;;
    esac
done

# This is normally unused
# shellcheck disable=SC2034
APP_HOME=$( cd -P "$( dirname "$app_path" )" >/dev/null 2>&1 && pwd )
APP_BASE_NAME=$(basename "$0")

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in                #(
  CYGWIN* )         cygwin=true  ;; #(
  Darwin* )         darwin=true  ;; #(
  MSYS* | MINGW* )   msys=true    ;; #(
  NONSTOP* )        nonstop=true ;;
esac

# --- Auto-bootstrap the wrapper jar if it's missing ----------------------
JAR_PATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
BOOTSTRAP="$APP_HOME/bootstrap-gradle.sh"

if [ ! -f "$JAR_PATH" ] || [ ! -s "$JAR_PATH" ]; then
    echo "gradlew: wrapper jar missing, running bootstrap-gradle.sh to fetch it..." >&2
    # Prefer bash (the bootstrap script uses bash-ish features in places);
    # fall back to sh. The bootstrap script is now POSIX-sh compatible so
    # either works, but bash is still preferred for robustness.
    if command -v bash >/dev/null 2>&1; then
        bash "$BOOTSTRAP" || die "gradlew: bootstrap-gradle.sh failed. Run it manually: bash $BOOTSTRAP"
    elif command -v sh >/dev/null 2>&1; then
        sh "$BOOTSTRAP" || die "gradlew: bootstrap-gradle.sh failed. Run it manually: sh $BOOTSTRAP"
    else
        die "gradlew: bootstrap-gradle.sh not found at $BOOTSTRAP"
    fi
    # Re-check
    if [ ! -f "$JAR_PATH" ] || [ ! -s "$JAR_PATH" ]; then
        die "gradlew: wrapper jar still missing after bootstrap. Run $BOOTSTRAP manually."
    fi
fi

CLASSPATH=$JAR_PATH

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1
    then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
fi

# Increase the maximum file descriptors if we can.
if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    case $MAX_FD in #(
      max*)
        # In POSIX sh, ulimit -H is undefined. That's why the result is checked to see if it worked.
        # shellcheck disable=SC2039,SC3045
        MAX_FD=$( ulimit -H -n ) ||
            warn "Could not query maximum file descriptor limit"
    esac
    case $MAX_FD in  #(
      '' | soft) :;; #(
      *)
        # In POSIX sh, ulimit -n is undefined. That's why the result is checked to see if it worked.
        # shellcheck disable=SC2039,SC3045
        ulimit -n "$MAX_FD" ||
            warn "Could not set maximum file descriptor limit to $MAX_FD"
    esac
fi

# Collect all arguments for the java command, stacking in reverse order:
#   * args from the command line
#   * the main class name
#   * -classpath
#   * DEFAULT_JVM_OPTS, JAVA_OPTS, and GRADLE_OPTS environment variables.

# For Cygwin or MSYS, switch paths to Windows format before running java
if "$cygwin" || "$msys" ; then
    APP_HOME=$( cygpath --path --mixed "$APP_HOME" )
    CLASSPATH=$( cygpath --path --mixed "$CLASSPATH" )

    JAVACMD=$( cygpath --unix "$JAVACMD" )

    # Now convert the arguments - kludge to limit ourselves to /bin/sh
    for arg do
        if
            case $arg in                                #(
              -*)   false ;;                            # don't mess with options
              /?*)  t=${arg#/} t=/${t%%/*}              # looks like a POSIX filepath
                    [ -e "$t" ] ;;                      #(
              *)    false ;;
            esac
        then
            arg=$( cygpath --path --ignore --mixed "$arg" )
        fi
        # Roll the args list around, prepending to the front
        set -- "$arg" "$@"
    done
fi

# Add the main class name back to the front of the list
set -- org.gradle.wrapper.GradleWrapperMain "$@"

# Add -classpath and the CLASSPATH env var back to the front of the list
set -- \
        -classpath "$CLASSPATH" \
        "$@"

# Use "eval set --" so that DEFAULT_JVM_OPTS — which contains literal
# double-quotes — is properly split into separate JVM args instead of being
# passed as one giant quoted string. This is the same trick the official
# Gradle wrapper uses.
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" "$@"

exec "$JAVACMD" "$@"
