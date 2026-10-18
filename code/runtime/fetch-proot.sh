#!/bin/sh
# Termux's proot for aarch64 (with its loader and libraries), which the app runs the Alpine
# userland under. usage: fetch-proot.sh <out dir>
set -eu
OUT=$(realpath "$1")
REPO=https://packages.termux.dev/apt/termux-main
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl -fsSL "$REPO/dists/stable/main/binary-aarch64/Packages" > "$work/Packages"
for pkg in proot libtalloc libandroid-shmem; do
    entry=$(awk -v RS= -v p="$pkg" '$0 ~ "(^|\n)Package: " p "\n"' "$work/Packages")
    file=$(printf '%s\n' "$entry" | sed -n 's/^Filename: //p')
    sum=$(printf '%s\n' "$entry" | sed -n 's/^SHA256: //p')
    curl -fsSL -o "$work/$pkg.deb" "$REPO/$file"
    echo "$sum  $work/$pkg.deb" | sha256sum -c -
    mkdir -p "$work/$pkg"
    (cd "$work/$pkg" && ar x "../$pkg.deb" && tar -xf data.tar.*)
done
usr=data/data/com.termux/files/usr
mkdir -p "$OUT"
cp "$work/proot/$usr/bin/proot" "$OUT/proot"
cp "$work/proot/$usr/libexec/proot/loader" "$OUT/loader"
cp "$work/libtalloc/$usr/lib/libtalloc.so.2."* "$OUT/libtalloc.so.2"
cp "$work/libandroid-shmem/$usr/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"
ls -l "$OUT"
