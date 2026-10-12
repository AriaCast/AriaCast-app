#!/usr/bin/env bash
OUT=${OUT:-/tmp/airplay-e2e}
for p in raop raop-pw ap2 nqptp; do
  [[ -f "$OUT/$p.pid" ]] && kill "$(cat "$OUT/$p.pid")" 2>/dev/null || true
  rm -f "$OUT/$p.pid"
done
ip link del veth-ap2 2>/dev/null || true
ip netns del ap2e2e 2>/dev/null || true
