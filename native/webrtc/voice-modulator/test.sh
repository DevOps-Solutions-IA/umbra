#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
"${CXX:-g++}" -std=c++17 -O2 -Wall -Wextra -Werror -pthread "$root/processor_test.cc" -o "$output/processor-test"
"$output/processor-test"
