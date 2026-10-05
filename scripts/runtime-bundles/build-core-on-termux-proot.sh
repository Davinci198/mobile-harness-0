#!/usr/bin/env bash
# Build the portable Core bundle on Ubuntu 26.04 base, inside Termux, using proot.
# Same guest payload as build-core-on-rooted-android.sh, but no rooted phone:
# proot replaces chroot + mount --bind, and hard links are materialised by copy.
set -euo pipefail

VERSION="${POCKETDEV_CORE_VERSION:-2026.10.1}"
NODE_VERSION="${POCKETDEV_NODE_VERSION:-v24.19.0}"
DNS_SERVER="${POCKETDEV_DNS:-1.1.1.1}"
ROOTFS_FILE="ubuntu-base-26.04-base-arm64.tar.gz"
ROOTFS_SHA256="b2b46a37324ea1954e93f293fe6d7c2241daf2fc298c4022e6e4caceeed74cab"
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/${ROOTFS_FILE}"
ROOTFS_VERSION="ubuntu-26.04.1-arm64"
ROOTFS_MTIME="2026-10-01T00:00:00Z"

WORK="${POCKETDEV_BUILD_DIR:-$HOME/9remote-uploads/core-build}"
ROOTFS="$WORK/rootfs"
OUTPUT="$WORK/output"
DOWNLOADS="$WORK/downloads"
TARBALL="$DOWNLOADS/$ROOTFS_FILE"

[[ "$(uname -m)" == "arm64" || "$(uname -m)" == "aarch64" ]] || {
  echo "Run this from an ARM64 host." >&2
  exit 1
}
command -v proot >/dev/null || { echo "proot is required." >&2; exit 1; }

mkdir -p "$DOWNLOADS" "$OUTPUT"

if [[ ! -f "$TARBALL" ]]; then
  curl -fL --retry 3 -o "$TARBALL" "$ROOTFS_URL"
fi
printf '%s  %s\n' "$ROOTFS_SHA256" "$TARBALL" | sha256sum -c -

# Extract like the app's extractZstdTar(): hard links become byte copies because
# /data forbids link() towards files the process does not own (protected_hardlinks).
echo "==> Extracting $ROOTFS_FILE (hard links copied, never link(2))"
rm -rf "$ROOTFS"
mkdir -p "$ROOTFS"
tar -xzf "$TARBALL" -C "$ROOTFS" 2>/dev/null || true
while IFS=$'\t' read -r link_path target_path; do
  src="$ROOTFS/${target_path#./}"
  dst="$ROOTFS/${link_path#./}"
  if [[ -f "$src" ]]; then
    mkdir -p "$(dirname "$dst")"
    cp -f "$src" "$dst"
    chmod --reference="$src" "$dst"
  else
    echo "FATAL: hard-link target missing: $link_path -> $target_path" >&2
    exit 1
  fi
done < <(tar -tvzf "$TARBALL" 2>/dev/null | grep " link to " \
  | sed 's/ link to /\t/' \
  | sed 's/^hrwx[^ ]* *root\/root *0 *[0-9-]* *[0-9:]* *//')

printf 'nameserver %s\n' "$DNS_SERVER" > "$ROOTFS/etc/resolv.conf"

guest() {
  # -0 alone: chown(0:0) and privileged writes both work. proot rejects -i together
  # with -0 ("only the last -i/-0/-S option is enabled"), so -0 is the only option.
  proot -0 -r "$ROOTFS" -b /dev -b /proc -b /sys -b "$OUTPUT:/output" -w /root \
    /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash -c "$1"
}

# dpkg creates hard links while unpacking (gconv/*.so backups) and /data refuses
# link(2) towards files we do not own. --link2symlink lets link() succeed, but it
# also rewrites real binaries into symlinks, which breaks multi-call binaries such
# as coreutils and env. So it is enabled only for the apt runs.
guest_apt() {
  proot -0 --link2symlink -r "$ROOTFS" -b /dev -b /proc -b /sys -b "$OUTPUT:/output" -w /root \
    /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/bash -c "$1"
}

# With --link2symlink, dpkg's multi-call shims (coreutils, perl) turn into symlinks
# to /data/... .l2s.* files. proot then refuses them ("Requested utility does not
# match executable name"), so /usr/bin/env dies and every apt run after that fails.
# The app's extractZstdTar copies such targets byte for byte, so do the same here:
# replace every absolute host symlink with a real copy, before apt runs and again
# after each apt call, because each run recreates them.
materialise_link2symlink() {
  local link="$1" resolved
  resolved="$(readlink -f "$link" 2>/dev/null || true)"
  [[ -n "$resolved" && -f "$resolved" ]] || return 0
  rm -f "$link"
  cp -f "$resolved" "$link"
  chmod --reference="$resolved" "$link" 2>/dev/null || chmod 755 "$link"
  printf '  materialised %s (%s bytes)\n' "$link" "$(stat -c %s "$link")"
}

fix_shims() {
  local relative_link found
  while IFS= read -r relative_link; do
    # find runs inside a cd'd subshell, so its output is rootfs-relative. Prefix it
    # before resolving, otherwise readlink -f looks under the script's own cwd.
    materialise_link2symlink "$ROOTFS/$relative_link"
  done < <(cd "$ROOTFS" && find usr bin -type l -lname '/data/*' 2>/dev/null)
}

echo "==> materialising link2symlink shims (pre-apt)"
fix_shims

# dpkg leaves .l2s.* temporaries behind when link() is emulated as a symlink; they
# are build artefacts and must not ship.
#
# The usr/lib/cargo/bin farm is deliberately left as real files. Collapsing the 115
# copies of the multi-call coreutils binary into symlinks does shrink the rootfs from
# 1.6 GB to 445 MB, but proot then refuses them: its execve check compares the
# requested utility name with the resolved file name, so coreutils/env -> basename
# dies with "Requested utility `env` does not match executable name". The canonical
# build keeps them as hardlinks, which tar stores as link entries.
remove_dpkg_temporaries() {
  # Covers /usr/bin shims and /var/lib/dpkg leftovers such as status-old.
  find "$ROOTFS" -name '.l2s.*' -delete 2>/dev/null || true
  find "$ROOTFS/var/lib/dpkg" -maxdepth 1 -name 'status-old' -delete 2>/dev/null || true
  printf '  rootfs after cleanup: %s, l2s left: %s\n' \
    "$(du -sh "$ROOTFS" 2>/dev/null | cut -f1)" \
    "$(find "$ROOTFS" -name '.l2s.*' 2>/dev/null | wc -l)"
}

apt_step() {
  local label="$1" cmd="$2" attempt
  echo "==> $label"
  for attempt in 1 2 3; do
    # Repair first: the previous apt run left /usr/bin/env as a dangling absolute
    # symlink, so the guest cannot even start without this.
    fix_shims
    if guest_apt "$cmd"; then
      fix_shims
      return 0
    fi
    echo "  retry $attempt after shim repair" >&2
  done
  echo "FATAL: $label failed after 3 attempts" >&2
  exit 1
}

apt_step "apt update + upgrade" \
  "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get -y -qq upgrade"
apt_step "base tools" \
  "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git ca-certificates curl wget unzip zip xz-utils zstd"

echo "==> node $NODE_VERSION"
# --strip-components=1 matches extractNodeArchive in the app, which drops the
# node-<version>-linux-arm64 prefix. Without it the guest gets
# /usr/local/lib/nodejs/node-v24.19.0-linux-arm64/bin/node while the app expects
# /usr/local/lib/nodejs/bin/node, and its usr/local/bin links stay dangling, which
# fails the "Guest Node.js is missing" check. The relative link targets are the ones
# the app creates itself in installNodeIfNeeded.
guest "set -e; cd /tmp; curl -fsSLO https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-linux-arm64.tar.gz; curl -fsSL https://nodejs.org/dist/${NODE_VERSION}/SHASUMS256.txt | grep '  node-${NODE_VERSION}-linux-arm64.tar.gz' | sha256sum -c -; rm -rf /usr/local/lib/nodejs; mkdir -p /usr/local/lib/nodejs; tar -xzf node-${NODE_VERSION}-linux-arm64.tar.gz -C /usr/local/lib/nodejs --strip-components=1; for command in node npm npx corepack; do ln -sfn ../lib/nodejs/bin/\$command /usr/local/bin/\$command; done; rm -f /tmp/node-${NODE_VERSION}-linux-arm64.tar.gz"
echo "==> cleanup"
guest "apt-get clean; rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /var/tmp/*"
echo "==> cleaning dpkg build artefacts"
remove_dpkg_temporaries
fix_shims
printf '%s\n' "$ROOTFS_VERSION" > "$ROOTFS/.pocket-rootfs-version"
printf 'core-bundle-%s\n' "$VERSION" > "$ROOTFS/.pocket-core-tools-version"
printf 'core-bundle-%s\n' "$VERSION" > "$ROOTFS/.pocket-runtime-ready"
printf 'ubuntu-maintenance-v2\n' > "$ROOTFS/.pocket-system-upgrade-version"
printf '{"WEB":true,"PYTHON":false,"CPP":false,"PHP":false,"ANDROID":false}\n' \
  > "$ROOTFS/.pocket-dev-stacks.json"
guest "mkdir -p /workspace /opt/pocketdev /root/.gradle/init.d"
guest "rm -rf /root/.cache /root/.npm /root/.composer /root/.gradle/caches /root/.ssh; find /var/log -type f -delete; rm -f /etc/ssh/ssh_host_* /etc/machine-id /var/lib/dbus/machine-id /root/.bash_history"

BUNDLE="pocketdev-core-arm64-${VERSION}.tar.zst"
echo "==> packing $BUNDLE (zstd -19, deterministic)"
rm -f "$OUTPUT/$BUNDLE"
# The canonical script runs in a real chroot, where /proc, /sys and /dev are not
# reachable and /output is a bind mount outside the archive. Under proot they are
# all visible, so exclude the pseudo-filesystems and the output mount, and write the
# bundle to /output instead of /tmp, which is part of the archived tree.
#
# --long=24 is required: the base image hard-links 115 copies of a 10.6 MB multi-call
# coreutils binary, link(2) is refused under /data, and zstd's default 8 MB window at
# -19 cannot match them, which inflated the bundle from 85 MB to 486 MB. A 16 MB
# window collapses the consecutive identical members instead. Verified that
# `zstd -19` alone leaves three copies at 10.6 MB while `--long=24` returns 3.57 MB.
guest "export ZSTD_CLEVEL=19 ZSTD_NBTHREADS=0; tar --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./run --exclude=./output --exclude='._*' --exclude='.DS_Store' --sort=name --mtime="$ROOTFS_MTIME" --owner=0 --group=0 --numeric-owner --use-compress-program='/usr/bin/zstd --long=24' -cf /output/$BUNDLE -C / ."

echo "==> verifying guest before publishing"
# `node --version` failing here must abort the build: a dangling usr/local/bin/node
# used to pass silently and only surfaced later as "Guest Node.js is missing".
guest "set -e; bash --version | head -1; git --version; node --version; npm --version; apt-get --version | head -1; ldd --version | head -1; cat /etc/os-release | grep VERSION_ID; test -x /usr/local/lib/nodejs/bin/node; test -f /.pocket-rootfs-version; test -f /.pocket-runtime-ready"

echo
echo "Bundle:  $OUTPUT/$BUNDLE"
echo "Bytes:   $(stat -c %s "$OUTPUT/$BUNDLE")"
echo "SHA256:  $(sha256sum "$OUTPUT/$BUNDLE" | cut -d' ' -f1)"
