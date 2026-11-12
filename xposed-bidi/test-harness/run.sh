#!/usr/bin/env bash
# Compiles and runs the pure-Java bidi-level suite on the JVM (no Android needed).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac -d out \
    ../src/main/java/com/newoether/agora/bidi/BidiLevelFixer.java \
    BidiLevelFixerTest.java
java -cp out BidiLevelFixerTest
