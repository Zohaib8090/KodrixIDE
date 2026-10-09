#!/usr/bin/env bash
# Host test for the self-path answers of libkodrix_exec.so (androidApp/src/main/cpp/kodrix_exec.c).
# Programs that ask "which file am I?" must get the real program, not /system/bin/linker64.
# Builds the shim for this machine with gcc and checks readlink / realpath of /proc/self/exe.
# Usage: scripts/test-exec-shim.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT

gcc -shared -fPIC -O1 -o "$T/libshim.so" "$ROOT/androidApp/src/main/cpp/kodrix_exec.c" -ldl
cat > "$T/probe.c" <<'C'
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
int main(void) {
    char *a = realpath("/proc/self/exe", NULL);
    char rl[PATH_MAX]; ssize_t n = readlink("/proc/self/exe", rl, sizeof rl - 1);
    rl[n > 0 ? n : 0] = 0;
    printf("%s\n%s\n", a ? a : "(null)", rl);
    return 0;
}
C
gcc -o "$T/probe" "$T/probe.c"

mkdir -p "$T/inst/lib/dotnet"; : > "$T/inst/lib/dotnet/dotnet"; ln -s lib/dotnet/dotnet "$T/inst/bin-link"
want="$(cd "$T/inst/lib/dotnet" && pwd -P)/dotnet"
fail=0

# 1. with KODRIX_EXE (even through a symlink) both calls must give the resolved program
out="$(KODRIX_EXE="$T/inst/bin-link" LD_PRELOAD="$T/libshim.so" "$T/probe")"
[ "$(sed -n 1p <<<"$out")" = "$want" ] || { echo "FAIL realpath: $(sed -n 1p <<<"$out") != $want"; fail=1; }
[ "$(sed -n 2p <<<"$out")" = "$want" ] || { echo "FAIL readlink: $(sed -n 2p <<<"$out") != $want"; fail=1; }

# 2. without KODRIX_EXE nothing changes
want2="$(readlink -f "$T/probe")"
out="$(env -u KODRIX_EXE LD_PRELOAD="$T/libshim.so" "$T/probe")"
[ "$(sed -n 1p <<<"$out")" = "$want2" ] && [ "$(sed -n 2p <<<"$out")" = "$want2" ] || { echo "FAIL passthrough: $out"; fail=1; }

[ "$fail" = 0 ] && echo "exec shim: self-path checks passed"
exit "$fail"
