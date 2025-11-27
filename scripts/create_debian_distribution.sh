#!/usr/bin/env bash
#
# make-debian13-custom.sh
#
# NOTE ON COMMENTING STYLE:
#   Every design choice is documented **exactly where it happens**.
#   Comments explain *why this line exists*, not generic philosophy.
#   No top-level “manifesto”; decisions live next to their consequences.
#
#   This script is long because Debian Installer is old, weird, and brittle.
#   That’s not our fault, but we work around it.

set -euo pipefail

### ------------------------------------------------------------------------
### CONFIGURATION: These variables define user & ISO behavior.
###               If you override ADMIN_USER/IMAGE_ID, keys will track properly.
### ------------------------------------------------------------------------

DEB_LOCALE="${DEB_LOCALE:-en_AU.UTF-8}"
DEB_KEYMAP="${DEB_KEYMAP:-us}"
DEB_TIMEZONE="${DEB_TIMEZONE:-Australia/Hobart}"

ADMIN_USER="${ADMIN_USER:-sysop}"

# IMAGE_ID ties *all future rebuilds* of this ISO line to one SSH key.
# This prevents new-rebuild = broken SSH access to installed machines.
IMAGE_ID="${IMAGE_ID:-debian13-${ADMIN_USER}}"

DOWNLOAD_DIR="${DOWNLOAD_DIR:-./downloads}"
MIRROR_BASE="${MIRROR_BASE:-https://cdimage.debian.org/debian-cd/current/amd64/iso-cd}"
ISOLINUX_MBR="${ISOLINUX_MBR:-/usr/lib/ISOLINUX/isohdpfx.bin}"

# Only used **on first creation** of this IMAGE_ID.
SSH_KEY_NAME="${SSH_KEY_NAME:-id_ed25519_${IMAGE_ID}}"

KEYSTORE_DIR="${KEYSTORE_DIR:-./ssh-keystore}"

### ------------------------------------------------------------------------
### ROOT CHECK — d-i modification + ISO building requires root.
### ------------------------------------------------------------------------
if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  echo "Run this script with sudo." >&2
  exit 1
fi

### ------------------------------------------------------------------------
### DEPENDENCY INSTALLER
### ------------------------------------------------------------------------
apt_install_missing() {
  local pkgs=()
  command -v xorriso    >/dev/null 2>&1 || pkgs+=("xorriso")
  command -v cpio       >/dev/null 2>&1 || pkgs+=("cpio")
  command -v gzip       >/dev/null 2>&1 || pkgs+=("gzip")
  command -v md5sum     >/dev/null 2>&1 || pkgs+=("coreutils")
  command -v sha512sum  >/dev/null 2>&1 || pkgs+=("coreutils")
  command -v curl       >/dev/null 2>&1 || pkgs+=("curl")
  command -v ssh-keygen >/dev/null 2>&1 || pkgs+=("openssh-client")
  [[ -f "$ISOLINUX_MBR" ]] || pkgs+=("isolinux")

  if ((${#pkgs[@]})); then
    apt-get update
    apt-get install -y "${pkgs[@]}"
  fi
}
apt_install_missing

### ------------------------------------------------------------------------
### DOWNLOAD LATEST DEBIAN 13 ISO
### ------------------------------------------------------------------------
mkdir -p "$DOWNLOAD_DIR"
HTML_INDEX="$(curl -fsSL "$MIRROR_BASE/")"

ISO_NAME="$(printf '%s\n' "$HTML_INDEX" \
  | grep -oE 'debian-13\.[0-9]+\.0-amd64-netinst\.iso' \
  | sort -V | tail -1)"

[[ -n "$ISO_NAME" ]] || { echo "No ISO found"; exit 1; }

ISO_IN="$DOWNLOAD_DIR/$ISO_NAME"
ISO_URL="$MIRROR_BASE/$ISO_NAME"

if [[ ! -f "$ISO_IN" ]]; then
  curl -fLo "$ISO_IN" "$ISO_URL"
fi

(
  cd "$DOWNLOAD_DIR"
  curl -fsSL "$MIRROR_BASE/SHA512SUMS" \
    | grep " $ISO_NAME\$" \
    | sha512sum -c -
)

### ------------------------------------------------------------------------
### SSH KEY MANAGEMENT (PER IMAGE_ID)
### ------------------------------------------------------------------------
mkdir -p "$KEYSTORE_DIR"
META_FILE="$KEYSTORE_DIR/${IMAGE_ID}.meta"

if [[ -f "$META_FILE" ]]; then
  SSH_KEY_BASENAME="$(head -n1 "$META_FILE")"
  SSH_PRIV="$KEYSTORE_DIR/$SSH_KEY_BASENAME"
  SSH_PUB="$SSH_PRIV.pub"

  if [[ ! -f "$SSH_PRIV" || ! -f "$SSH_PUB" ]]; then
    echo "ERROR: Key files missing for IMAGE_ID '$IMAGE_ID'. Refusing to proceed."
    exit 1
  fi
else
  SSH_KEY_BASENAME="$SSH_KEY_NAME"
  SSH_PRIV="$KEYSTORE_DIR/$SSH_KEY_BASENAME"
  SSH_PUB="$SSH_PRIV.pub"
  echo "$SSH_KEY_BASENAME" > "$META_FILE"
  ssh-keygen -t ed25519 -N "" -f "$SSH_PRIV"
fi

### ------------------------------------------------------------------------
### INSTALL BUILDER KEY INTO HOST ~/.ssh
### ------------------------------------------------------------------------
RUN_USER="${SUDO_USER:-$USER}"
USER_HOME="$(eval echo ~"$RUN_USER")"
TARGET_SSH_DIR="$USER_HOME/.ssh"
mkdir -p "$TARGET_SSH_DIR"

TARGET_PRIV="$TARGET_SSH_DIR/$(basename "$SSH_PRIV")"
TARGET_PUB="$TARGET_PRIV.pub"

cp "$SSH_PRIV" "$TARGET_PRIV"; chmod 600 "$TARGET_PRIV"
cp "$SSH_PUB" "$TARGET_PUB"; chmod 644 "$TARGET_PUB"
chown -R "$RUN_USER":"$RUN_USER" "$TARGET_SSH_DIR"

SSH_CONFIG="$TARGET_SSH_DIR/config"
touch "$SSH_CONFIG"; chmod 600 "$SSH_CONFIG"

if ! grep -q "IdentityFile ~/.ssh/${SSH_KEY_BASENAME}" "$SSH_CONFIG"; then
  echo "IdentityFile ~/.ssh/${SSH_KEY_BASENAME}" >> "$SSH_CONFIG"
fi

### ------------------------------------------------------------------------
### ISO EXTRACTION
### ------------------------------------------------------------------------
ISO_WORK_DIR="isofiles"
ISO_OUT="${ISO_NAME%.iso}-${IMAGE_ID}-custom.iso"

rm -rf "$ISO_WORK_DIR"
mkdir "$ISO_WORK_DIR"

xorriso -osirrox on -indev "$ISO_IN" -extract / "$ISO_WORK_DIR"

cp "$SSH_PUB" "$ISO_WORK_DIR/ssh-builder.pub"

### ------------------------------------------------------------------------
### ZSHRC TEMPLATE
### ------------------------------------------------------------------------
cat > zshrc-template <<'EOF'
# Auto-generated default zshrc for this image
PROMPT="%F{red}%M%f %F{14}%n%f %d %T : "
export EDITOR=nvim
EOF

cp zshrc-template "$ISO_WORK_DIR/zshrc-template"

### ------------------------------------------------------------------------
### BTRFS SUBVOL SETUP SCRIPT (RUN ON FIRST BOOT)
### ------------------------------------------------------------------------
cat > btrfs-subvol-setup.sh <<'EOF'
#!/bin/bash
set -euo pipefail

# Only operate on Btrfs roots.
if ! findmnt -n -o FSTYPE / | grep -q btrfs; then
  exit 0
fi

# Raw mount source for /, e.g. "/dev/vda1[/@rootfs]" or "/dev/nvme0n1p2[/@]"
raw_src="$(findmnt -n -o SOURCE /)"

# Split into block device and subvolume.
rootdev="${raw_src%%\[*}"
root_sv="${raw_src##*\[}"
root_sv="${root_sv%\]}"
[ -n "$root_sv" ] || root_sv="@"

# Mount top-level root (id=5)
mkdir -p /mnt/btrfs-root
mount -o subvolid=5 "$rootdev" /mnt/btrfs-root

root_path="/mnt/btrfs-root/$root_sv"
[ -d "$root_path" ] || root_path="/mnt/btrfs-root"

# Helper to ensure subvolumes exist at the top level.
ensure_sv() {
  local name="$1"
  if ! btrfs subvolume list /mnt/btrfs-root | awk '{print $9}' | grep -qx "$name"; then
    btrfs subvolume create "/mnt/btrfs-root/$name"
  fi
}

ensure_sv @home
ensure_sv @var_log
ensure_sv @var_cache
ensure_sv @var_tmp
ensure_sv @snapshots

# Move a tree out of the root subvol into a dedicated subvol.
move_tree() {
  local rel="$1" dest_sv="$2"
  if [ -d "$root_path/$rel" ]; then
    rsync -aXS "$root_path/$rel"/ "/mnt/btrfs-root/$dest_sv/"
    rm -rf "$root_path/$rel"
    mkdir -p "$root_path/$rel"
  fi
}

move_tree home @home
move_tree var/log @var_log
move_tree var/cache @var_cache
move_tree var/tmp @var_tmp

mkdir -p /mnt/btrfs-root/@snapshots

# Human-facing docs in each subvolume root.
write_info() {
  local dir="$1" name="$2" mnt="$3"
  mkdir -p "$dir"
  cat > "$dir/.subvol.md" <<INFOEOF
# Btrfs subvolume: $name

- Physical device: $rootdev
- Subvolume name: $name
- Intended mount point: $mnt

This file was generated automatically by the custom Debian 13 installer.
You can snapshot or send/receive this subvolume independently of others.
INFOEOF
}

# Root subvol doc (shows up at "/").
write_info "$root_path" "$root_sv" "/"

# Docs for the others (show up at their mountpoints).
write_info "/mnt/btrfs-root/@home"       "@home"       "/home"
write_info "/mnt/btrfs-root/@var_log"    "@var_log"    "/var/log"
write_info "/mnt/btrfs-root/@var_cache"  "@var_cache"  "/var/cache"
write_info "/mnt/btrfs-root/@var_tmp"    "@var_tmp"    "/var/tmp"
write_info "/mnt/btrfs-root/@snapshots"  "@snapshots"  "/.snapshots"

# Rewrite fstab: comment any existing btrfs root line (format varies wildly)
sed -i 's|^\([^#].*[[:space:]]/[[:space:]].*btrfs.*\)$|# \1|' /etc/fstab

rootuuid="$(blkid -s UUID -o value "$rootdev" || true)"
rootref="${rootuuid:+UUID=$rootuuid}"
rootref="${rootref:-$rootdev}"
opts="compress=zstd:3,noatime,space_cache=v2"

{
  echo "$rootref  /           btrfs  subvol=$root_sv,$opts           0  0"
  echo "$rootref  /home       btrfs  subvol=@home,$opts              0  0"
  echo "$rootref  /var/log    btrfs  subvol=@var_log,$opts          0  0"
  echo "$rootref  /var/cache  btrfs  subvol=@var_cache,$opts        0  0"
  echo "$rootref  /var/tmp    btrfs  subvol=@var_tmp,$opts          0  0"
  echo "$rootref  /.snapshots btrfs  subvol=@snapshots,$opts         0  0"
} >> /etc/fstab

# Mount the new subvols immediately so this first boot sees them.
mount_subvol_if_needed() {
  local mp="$1" sv="$2"
  if mountpoint -q "$mp"; then
    return
  fi
  mkdir -p "$mp"
  mount -o "subvol=$sv,$opts" "$rootdev" "$mp" || true
}

mount_subvol_if_needed /home       @home
mount_subvol_if_needed /var/log    @var_log
mount_subvol_if_needed /var/cache  @var_cache
mount_subvol_if_needed /var/tmp    @var_tmp
mount_subvol_if_needed /.snapshots @snapshots

# Ensure all passwd-listed home directories actually exist under /home.
while IFS=: read -r _ _ _ _ _ homedir _; do
  case "$homedir" in
    /home/*)
      [ -d "$homedir" ] || mkdir -p "$homedir"
      ;;
  esac
done < /etc/passwd

umount /mnt/btrfs-root || true
EOF

chmod +x btrfs-subvol-setup.sh
cp btrfs-subvol-setup.sh "$ISO_WORK_DIR/btrfs-subvol-setup.sh"

### ------------------------------------------------------------------------
### FIRST-BOOT BTRFS+SNAPPER SETUP
### ------------------------------------------------------------------------
cat > firstboot-btrfs-snapper.sh <<'EOF'
#!/bin/bash
set -euo pipefail

if ! findmnt -n -o FSTYPE / | grep -q btrfs; then
  exit 0
fi

if [ -f /var/lib/firstboot-btrfs-snapper.done ]; then
  exit 0
fi

if [ -x /usr/local/sbin/btrfs-subvol-setup.sh ]; then
  /usr/local/sbin/btrfs-subvol-setup.sh
fi

if command -v snapper >/dev/null 2>&1; then
  snapper -c root create-config / || true
  systemctl enable snapper-timeline.timer snapper-cleanup.timer || true
fi

if command -v update-grub >/dev/null 2>&1; then
  update-grub || true
fi

touch /var/lib/firstboot-btrfs-snapper.done
systemctl disable firstboot-btrfs-snapper.service || true || true
EOF

chmod +x firstboot-btrfs-snapper.sh
cp firstboot-btrfs-snapper.sh "$ISO_WORK_DIR/firstboot-btrfs-snapper.sh"

cat > firstboot-btrfs-snapper.service <<'EOF'
[Unit]
Description=First boot Btrfs subvol + Snapper setup
DefaultDependencies=no
After=local-fs.target
Before=multi-user.target getty@tty1.service display-manager.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/firstboot-btrfs-snapper.sh

[Install]
WantedBy=multi-user.target
EOF

cp firstboot-btrfs-snapper.service "$ISO_WORK_DIR/firstboot-btrfs-snapper.service"

### ------------------------------------------------------------------------
### PRESEED CONFIG
### ------------------------------------------------------------------------
cat > preseed.cfg <<EOF
### Debian 13 Preseed — minimal automation, with KDE + sane defaults.

d-i debian-installer/locale string ${DEB_LOCALE}
d-i keyboard-configuration/xkb-keymap select ${DEB_KEYMAP}

d-i netcfg/choose_interface select auto
d-i netcfg/use_dhcp boolean true
d-i netcfg/dhcp_timeout string 30
d-i netcfg/get_domain string ""
d-i netcfg/get_domain seen true

d-i mirror/country string manual
d-i mirror/http/hostname string deb.debian.org
d-i mirror/http/directory string /debian
d-i mirror/http/proxy string
d-i apt-setup/cd/another boolean false
d-i apt-setup/cdrom/set-first boolean false

d-i time/zone string ${DEB_TIMEZONE}
d-i clock-setup/ntp boolean true

d-i passwd/root-login boolean false
d-i passwd/make-user boolean true

# Default username + fullname = ADMIN_USER (sysop by default),
# but DO NOT mark as seen so installer still prompts and allows edits.
d-i passwd/user-fullname string ${ADMIN_USER}
d-i passwd/user-fullname seen false
d-i passwd/username string ${ADMIN_USER}
d-i passwd/username seen false

d-i partman/default_filesystem string btrfs
d-i partman-auto/default_filesystem string btrfs

d-i grub-installer/only_debian boolean false
d-i grub-installer/with_other_os boolean true
d-i grub-installer/bootdev string default

# Desktop selection: show menu, but default to KDE.
tasksel tasksel/first multiselect standard, kde-desktop
tasksel tasksel/first seen false

# Extra tools + SSH server via pkgsel/include.
d-i pkgsel/include string neovim zsh git curl tmux htop sysstat iotop dstat rsync avahi-daemon avahi-utils libnss-mdns sudo qemu-guest-agent btrfs-progs snapper inotify-tools openssh-server

d-i pkgsel/upgrade select none
popularity-contest popularity-contest/participate boolean false

d-i finish-install/reboot_in_progress note

d-i preseed/early_command string sleep 3

d-i preseed/late_command string \
  in-target usermod -aG sudo ${ADMIN_USER}; \
  in-target chsh -s /usr/bin/zsh ${ADMIN_USER}; \
  mkdir -p /target/home/${ADMIN_USER}/.ssh; \
  cp /cdrom/ssh-builder.pub /target/home/${ADMIN_USER}/.ssh/authorized_keys; \
  cp /cdrom/zshrc-template /target/home/${ADMIN_USER}/.zshrc; \
  in-target chown -R ${ADMIN_USER}:${ADMIN_USER} /home/${ADMIN_USER}; \
  in-target chmod 700 /home/${ADMIN_USER}/.ssh; \
  in-target chmod 600 /home/${ADMIN_USER}/.ssh/authorized_keys; \
  in-target chmod 644 /home/${ADMIN_USER}/.zshrc; \
  in-target sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config; \
  in-target systemctl restart ssh || true; \
  mkdir -p /target/usr/local/sbin; \
  cp /cdrom/btrfs-subvol-setup.sh /target/usr/local/sbin/btrfs-subvol-setup.sh; \
  in-target chmod 755 /usr/local/sbin/btrfs-subvol-setup.sh; \
  cp /cdrom/firstboot-btrfs-snapper.sh /target/usr/local/sbin/firstboot-btrfs-snapper.sh; \
  in-target chmod 755 /usr/local/sbin/firstboot-btrfs-snapper.sh; \
  cp /cdrom/firstboot-btrfs-snapper.service /target/etc/systemd/system/firstboot-btrfs-snapper.service; \
  in-target systemctl enable firstboot-btrfs-snapper.service; \
  in-target update-grub
EOF

cp preseed.cfg "$ISO_WORK_DIR/preseed.cfg"

### ------------------------------------------------------------------------
### PRESEED → INITRD INJECTION
### ------------------------------------------------------------------------
INITRD_DIR="$ISO_WORK_DIR/install.amd"

cp preseed.cfg "$INITRD_DIR/preseed.cfg"

chmod +w "$INITRD_DIR"
(
  cd "$INITRD_DIR"
  gunzip initrd.gz
  echo preseed.cfg | cpio -H newc -o -A -F initrd
  gzip initrd
)
chmod -w "$INITRD_DIR"

### ------------------------------------------------------------------------
### AUTOBOOT (isolinux + grub)
### ------------------------------------------------------------------------
cat > "$ISO_WORK_DIR/isolinux/isolinux.cfg" <<'EOF'
default auto
prompt 0
timeout 0

label auto
  kernel /install.amd/vmlinuz
  append initrd=/install.amd/initrd.gz auto=true priority=high file=/preseed.cfg netcfg/dhcp_timeout=30
EOF

cat > "$ISO_WORK_DIR/boot/grub/grub.cfg" <<'EOF'
set default=0
set timeout=0

menuentry "Automated install" {
  linux   /install.amd/vmlinuz auto=true priority=high file=/preseed.cfg netcfg/dhcp_timeout=30
  initrd  /install.amd/initrd.gz
}
EOF

### ------------------------------------------------------------------------
### REBUILD MD5SUMS
### ------------------------------------------------------------------------
(
  cd "$ISO_WORK_DIR"
  find . -type f ! -name md5sum.txt -print0 \
    | xargs -0 md5sum > md5sum.txt
)

### ------------------------------------------------------------------------
### BUILD FINAL HYBRID ISO
### ------------------------------------------------------------------------
xorriso -as mkisofs \
  -isohybrid-mbr "$ISOLINUX_MBR" \
  -c isolinux/boot.cat \
  -b isolinux/isolinux.bin \
  -no-emul-boot \
  -boot-load-size 4 \
  -boot-info-table \
  -eltorito-alt-boot \
  -e boot/grub/efi.img \
  -no-emul-boot \
  -isohybrid-gpt-basdat \
  -o "$ISO_OUT" \
  "$ISO_WORK_DIR"

echo "Custom ISO ready: $ISO_OUT"
echo "IMAGE_ID: $IMAGE_ID"
echo "SSH key:   ~/.ssh/$(basename "$SSH_PRIV")"
echo "NOTE: This custom ISO is not Secure Boot signed. Disable Secure Boot to boot it."