#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
# JAVA_OPTS is intentionally split into JVM arguments; use JAVA for the executable.
exec "${JAVA:-java}" ${JAVA_OPTS:-} -cp 'Universe.jar:lib/*' com.nekoyu.Universe.Universe "$@"
