#!/bin/sh
# Builds the Linux userland the app runs Claude Code in: Alpine, a few packages and Claude Code,
# packed as one tar.xz (unpacked by the app on first launch).
# usage: build-rootfs.sh <aarch64|x86_64> <out.tar.xz>   (as root: apk sets file owners)
set -eu
ARCH=$1
OUT=$(realpath "$2")
ALPINE=3.24
ALPINE_RELEASE=3.24.2
PACKAGES="bash python3 git ripgrep libgcc libstdc++ curl less"
MIRROR=https://dl-cdn.alpinelinux.org/alpine
# What the app's apk installs from later (fast from China, works anywhere).
APP_MIRROR=https://mirrors.aliyun.com/alpine

case $ARCH in
    aarch64) PLATFORM=linux-arm64-musl ;;
    x86_64) PLATFORM=linux-x64-musl ;;
    *) echo "unknown arch $ARCH" >&2; exit 1 ;;
esac

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
root=$work/root
mkdir -p "$root"

curl -fsSL "$MIRROR/v$ALPINE/releases/$ARCH/alpine-minirootfs-$ALPINE_RELEASE-$ARCH.tar.gz" | tar -xz -C "$root"

# apk for this machine, installing packages for $ARCH without running them.
host=$(uname -m)
curl -fsSL "$MIRROR/v$ALPINE/main/$host/APKINDEX.tar.gz" | tar -xzO APKINDEX > "$work/index"
apkver=$(awk '/^P:apk-tools-static$/ {f=1} f && /^V:/ {print substr($0, 3); exit}' "$work/index")
curl -fsSL "$MIRROR/v$ALPINE/main/$host/apk-tools-static-$apkver.apk" | tar -xz -C "$work" sbin/apk.static 2>/dev/null
"$work/sbin/apk.static" --arch "$ARCH" --root "$root" --no-scripts --no-cache \
    -X "$MIRROR/v$ALPINE/main" -X "$MIRROR/v$ALPINE/community" -U add $PACKAGES

printf '%s/v%s/main\n%s/v%s/community\n' "$APP_MIRROR" "$ALPINE" "$APP_MIRROR" "$ALPINE" > "$root/etc/apk/repositories"
echo /bin/bash >> "$root/etc/shells"
sed -i 's|^root:x:0:0:root:/root:/bin/sh$|root:x:0:0:root:/root:/bin/bash|' "$root/etc/passwd"

# Claude Code is not in it: the app installs it from Anthropic's releases with claude-update on
# first launch, and claude-update updates it, the only place it comes from.
cat > "$root/usr/local/bin/claude-update" <<'UPDATE'
#!/bin/sh
# claude-update [version]: puts that version of Claude Code (the latest when none is given) in
# /usr/local/bin/claude, checked against its release manifest. App updates leave it.
set -eu
B=https://downloads.claude.ai/claude-code-releases
V=${1:-$(curl -fsSL "$B/latest")}
case $(uname -m) in aarch64) P=linux-arm64-musl ;; *) P=linux-x64-musl ;; esac
sum=$(curl -fsSL "$B/$V/manifest.json" | python3 -c "import json,sys; print(json.load(sys.stdin)['platforms']['$P']['checksum'])")
curl -fL -o /tmp/claude.new "$B/$V/$P/claude"
echo "$sum  /tmp/claude.new" | sha256sum -c -
chmod 755 /tmp/claude.new
/tmp/claude.new --version
mv -f /tmp/claude.new /usr/local/bin/claude
UPDATE
chmod 755 "$root/usr/local/bin/claude-update"

rm -rf "$root/var/cache/apk/"*
tar --numeric-owner --hard-dereference -C "$root" -cf - . | xz -T0 -6 > "$OUT"
ls -l "$OUT"
