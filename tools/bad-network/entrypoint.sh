#!/bin/sh
set -eu

DELAY="${NETEM_DELAY:-300ms}"
JITTER="${NETEM_JITTER:-100ms}"
LOSS="${NETEM_LOSS:-5%}"
RATE="${NETEM_RATE:-8mbit}"
TARGET="${TARGET:-gateway:443}"

for dev in $(ls /sys/class/net | grep -v '^lo$'); do
  tc qdisc replace dev "$dev" root netem delay "$DELAY" "$JITTER" loss "$LOSS" rate "$RATE"
done

echo "bad-network: intarziere $DELAY (+/- $JITTER), pierdere pachete $LOSS, banda $RATE -> $TARGET"
exec socat TCP-LISTEN:443,fork,reuseaddr,nodelay "TCP:$TARGET,nodelay"
