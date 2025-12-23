#!/usr/bin/env -S kotlin -Xmulti-dollar-interpolation

/**
 * Kotlin Debian 13 custom ISO builder
 *
 * - Fetches latest Debian 13.x netinst ISO
 * - Injects preseed, SSH keys, btrfs tooling
 * - On first boot, rearranges btrfs subvols + snapper
 *
 * Note: Script itself does not enforce root, but many steps will require it.
 */

@file:Suppress("TooManyFunctions", "SameParameterValue")

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.system.exitProcess

// ----------------------------- Utilities -----------------------------

data class CmdResult(val code: Int, val out: String, val err: String)

fun which(cmd: String): Boolean = runCatching {
    val p = ProcessBuilder("bash", "-lc", "command -v ${cmd}")
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    p.exitValue() == 0 && out.isNotBlank()
}.getOrElse { false }

fun runCmd(vararg args: String, cwd: File? = null, env: Map<String, String> = emptyMap()): CmdResult {
    println("[CMD] ${args.joinToString(" ")}")
    val pb = ProcessBuilder(*args)
    if (cwd != null) pb.directory(cwd)
    val processEnv = pb.environment()
    env.forEach { (k, v) -> processEnv[k] = v }
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText()
    val err = p.errorStream.bufferedReader().readText()
    val code = p.waitFor()
    println("[EXIT CODE] ${code}")
    return CmdResult(code, out, err)
}

fun requireSuccess(result: CmdResult, step: String) {
    if (result.code != 0) {
        System.err.println("ERROR at $step\nSTDOUT:\n${result.out}\nSTDERR:\n${result.err}")
        exitProcess(result.code)
    }
}

fun ensureDir(path: Path): Path = Files.createDirectories(path)

fun writeFile(path: Path, content: String, executable: Boolean = false) {
    ensureDir(path.parent)
    Files.writeString(path, content)
    if (executable) {
        path.toFile().setExecutable(true, true)
    }
}

fun appendLine(path: Path, content: String) {
    ensureDir(path.parent)
    val existing = if (Files.exists(path)) Files.readString(path) else ""
    Files.writeString(path, existing + content + "\n")
}

fun chmod(path: Path, perms: String) {
    runCmd("bash", "-lc", "chmod $perms '${path.toAbsolutePath()}'")
        .also { requireSuccess(it, "chmod $perms $path") }
}

fun chownRecursive(path: Path, user: String, group: String) {
    runCmd("bash", "-lc", "chown -R ${user}:${group} '${path.toAbsolutePath()}'")
        .also { requireSuccess(it, "chown -R $user:$group $path") }
}

fun isRoot(): Boolean =
    (System.getenv("EUID") ?: runCmd("bash", "-lc", "id -u").out.trim()) == "0"

fun getEnv(name: String, default: String): String = System.getenv(name) ?: default

// ----------------------------- Config (env with defaults) -----------------------------

// Get script's directory as base working directory
val SCRIPT_DIR = runCmd("bash", "-c", "cd \$(dirname \${BASH_SOURCE[0]:-${__FILE__}}) && pwd").out.trim().let { Path.of(it) }

val DEB_LOCALE = getEnv("DEB_LOCALE", "en_AU.UTF-8")
val DEB_KEYMAP = getEnv("DEB_KEYMAP", "us")
val DEB_TIMEZONE = getEnv("DEB_TIMEZONE", "Australia/Hobart")

val ADMIN_USER = getEnv("ADMIN_USER", "sysop")
val IMAGE_ID = getEnv("IMAGE_ID", "debian13-$ADMIN_USER")

val DOWNLOAD_DIR = SCRIPT_DIR.resolve(getEnv("DOWNLOAD_DIR", "downloads"))
val MIRROR_BASE = getEnv("MIRROR_BASE", "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd")
val ISOLINUX_MBR = getEnv("ISOLINUX_MBR", "/usr/lib/ISOLINUX/isohdpfx.bin")

val SSH_KEY_NAME = getEnv("SSH_KEY_NAME", "id_ed25519_${IMAGE_ID}")
val KEYSTORE_DIR = Path.of(System.getProperty("user.home")).resolve(".ssh")

// ----------------------------- Start -----------------------------

println("=== Debian Distribution Builder ===")
println("IMAGE_ID: $IMAGE_ID")
println("ADMIN_USER: $ADMIN_USER")
println()

// ----------------------------- Dependencies -----------------------------

fun checkDependencies() {
    println("\n[STEP] Checking dependencies...")
    val needed = mutableListOf<String>()
    if (!which("xorriso")) needed += "xorriso"
    if (!which("cpio")) needed += "cpio"
    if (!which("gzip")) needed += "gzip"
    if (!which("md5sum")) needed += "coreutils"
    if (!which("sha512sum")) needed += "coreutils"
    if (!which("curl")) needed += "curl"
    if (!which("ssh-keygen")) needed += "openssh-client"
    if (!File(ISOLINUX_MBR).exists()) needed += "isolinux"

    if (needed.isNotEmpty()) {
        System.err.println("\n[ERROR] Missing required packages: ${needed.joinToString(", ")}")
        System.err.println("\nInstall them with:")
        System.err.println("  sudo apt update && sudo apt install -y ${needed.joinToString(" ")}")
        exitProcess(1)
    } else {
        println("[✓] All dependencies present")
    }
}

checkDependencies()

// ----------------------------- Download ISO -----------------------------

println("\n[STEP] Preparing download directory: $DOWNLOAD_DIR")
ensureDir(DOWNLOAD_DIR)

println("[STEP] Fetching mirror index from: $MIRROR_BASE")
val htmlIndex = runCmd("bash", "-lc", "curl -fsSL '$MIRROR_BASE/'")
    .also { requireSuccess(it, "download mirror index") }
    .out

println("[STEP] Searching for latest Debian 13 netinst ISO...")

data class DebianIso(val name: String, val major: Int, val minor: Int)

val isoName = htmlIndex.lines()
    .mapNotNull { line ->
        Regex("""debian-(\d+)\.(\d+)\.0-amd64-netinst\.iso""")
            .find(line)
            ?.let { m ->
                val (maj, min) = m.destructured
                DebianIso(m.value, maj.toInt(), min.toInt())
            }
    }
    .filter { it.major == 13 }
    .distinctBy { it.name }
    .maxWithOrNull(compareBy<DebianIso> { it.major }.thenBy { it.minor })
    ?.name
    ?: run {
        System.err.println("No Debian 13 netinst ISO found in mirror index")
        exitProcess(1)
    }

println("[✓] Found ISO: $isoName")
val isoIn = DOWNLOAD_DIR.resolve(isoName)
val isoUrl = "$MIRROR_BASE/$isoName"

if (!Files.exists(isoIn)) {
    println("[STEP] Downloading ISO from: $isoUrl")
    println("       Destination: $isoIn")
    requireSuccess(
        runCmd("bash", "-lc", "curl -fLo '${isoIn}' '${isoUrl}'"),
        "download ISO"
    )
    println("[✓] Download complete")
} else {
    println("[✓] ISO already exists: $isoIn")
}

// Verify SHA512
println("[STEP] Verifying SHA512 checksum...")
runCmd(
    "bash", "-lc",
    "cd '${DOWNLOAD_DIR}' && curl -fsSL '$MIRROR_BASE/SHA512SUMS' | grep ' $isoName\$' | sha512sum -c -"
).also { requireSuccess(it, "verify SHA512SUMS") }
println("[✓] SHA512 verification passed")

// ----------------------------- SSH key management -----------------------------

println("\n[STEP] Managing SSH keys...")
ensureDir(KEYSTORE_DIR)
val metaFile = KEYSTORE_DIR.resolve("${IMAGE_ID}.meta")

data class Keys(val basename: String, val priv: Path, val pub: Path)

fun ensureKeys(): Keys {
    if (Files.exists(metaFile)) {
        println("[INFO] Found existing key metadata for IMAGE_ID '$IMAGE_ID'")
        val basename = Files.readAllLines(metaFile).firstOrNull()?.trim().orEmpty()
        val priv = KEYSTORE_DIR.resolve(basename)
        val pub = KEYSTORE_DIR.resolve("$basename.pub")
        if (!Files.exists(priv) || !Files.exists(pub)) {
            System.err.println("ERROR: Key files missing for IMAGE_ID '$IMAGE_ID'. Refusing to proceed.")
            exitProcess(1)
        }
        println("[✓] Using existing SSH key: $basename")
        return Keys(basename, priv, pub)
    }

    println("[INFO] No existing key found, generating new SSH key pair...")
    val basename = SSH_KEY_NAME
    val priv = KEYSTORE_DIR.resolve(basename)
    val pub = KEYSTORE_DIR.resolve("$basename.pub")

    println("[STEP] Generating ed25519 key: $basename")
    requireSuccess(
        runCmd("ssh-keygen", "-t", "ed25519", "-N", "", "-f", priv.toString()),
        "ssh-keygen"
    )
    Files.writeString(metaFile, basename + "\n")
    println("[✓] SSH key pair generated")
    return Keys(basename, priv, pub)
}

val keys = ensureKeys()

// Install builder key into invoking user's ~/.ssh
println("[STEP] Installing SSH key to user's ~/.ssh directory...")
val runUser = System.getenv("SUDO_USER") ?: System.getenv("USER") ?: System.getProperty("user.name")
println("[INFO] Target user: $runUser")
val userHome = runCmd("bash", "-lc", "eval echo ~${runUser}")
    .out.trim()
    .ifBlank { System.getProperty("user.home") }
val targetSshDir = Path.of(userHome, ".ssh")
ensureDir(targetSshDir)
val targetPriv = targetSshDir.resolve(keys.priv.fileName.toString())
val targetPub = targetSshDir.resolve(keys.pub.fileName.toString())
println("[STEP] Copying keys to $targetSshDir")
Files.copy(keys.priv, targetPriv, StandardCopyOption.REPLACE_EXISTING)
Files.copy(keys.pub, targetPub, StandardCopyOption.REPLACE_EXISTING)
chmod(targetPriv, "600")
chmod(targetPub, "644")
chownRecursive(targetSshDir, runUser, runUser)

println("[STEP] Updating SSH config...")
val sshConfig = targetSshDir.resolve("config")
if (!Files.exists(sshConfig)) Files.createFile(sshConfig)
chmod(sshConfig, "600")
val idLine = "IdentityFile ~/.ssh/${keys.basename}"
val configText = Files.readString(sshConfig)
if (!configText.lines().any { it.trim() == idLine }) {
    appendLine(sshConfig, idLine)
    println("[✓] Added IdentityFile entry to SSH config")
} else {
    println("[✓] SSH config already contains IdentityFile entry")
}

// ----------------------------- ISO extraction workdir -----------------------------

println("\n[STEP] Setting up ISO work directory...")
val ISO_WORK_DIR = SCRIPT_DIR.resolve("isofiles")
val ISO_OUT = SCRIPT_DIR.resolve("${isoName.removeSuffix(".iso")}-${IMAGE_ID}-custom.iso")
println("[INFO] Work directory: $ISO_WORK_DIR")
println("[INFO] Output ISO: $ISO_OUT")

if (Files.exists(ISO_WORK_DIR)) {
    println("[STEP] Cleaning existing work directory...")
    runCmd("bash", "-lc", "chmod -R +w '${ISO_WORK_DIR}' && rm -rf '${ISO_WORK_DIR}'")
        .also { requireSuccess(it, "clean ISO_WORK_DIR") }
}
Files.createDirectory(ISO_WORK_DIR)
println("[✓] Work directory created")

println("[STEP] Extracting ISO contents with xorriso (this may take a moment)...")
requireSuccess(
    runCmd(
        "xorriso",
        "-osirrox", "on",
        "-indev", isoIn.toString(),
        "-extract", "/", ISO_WORK_DIR.toString()
    ),
    "xorriso extract"
)
println("[✓] ISO extraction complete")

println("[STEP] Copying SSH public key to ISO...")
Files.copy(keys.pub, ISO_WORK_DIR.resolve("ssh-builder.pub"), StandardCopyOption.REPLACE_EXISTING)
println("[✓] SSH key copied")

// ----------------------------- zshrc template -----------------------------
println("\n[STEP] Creating configuration files...")
val zshrcTemplate = """
# Auto-generated default zshrc for this image
PROMPT="%F{red}%M%f %F{14}%n%f %d %T : "
export EDITOR=nvim
""".trimIndent()

writeFile(ISO_WORK_DIR.resolve("zshrc-template"), zshrcTemplate)
println("[✓] zshrc template created")

// ----------------------------- btrfs-subvol-setup.sh -----------------------------
// NOTE: This is inside a $$""" ... """ block, so:
//   - Kotlin interpolation uses $$
//   - Single $ is passed literally to the shell
val btrfsSubvolSetup = $$"""#!/bin/bash
set -euo pipefail

# Only operate on Btrfs roots.
if ! findmnt -n -o FSTYPE / | grep -q btrfs; then
  exit 0
fi

# Raw mount source for /, e.g. "/dev/vda1[/@rootfs]" or "/dev/nvme0n1p2[/@]" or just "/dev/vda1"
raw_src="$(findmnt -n -o SOURCE /)"

# Split into block device and subvolume (if present).
case "$raw_src" in
  *'['*']')
    rootdev="${raw_src%%\[*}"
    root_sv="${raw_src#*\[}"
    root_sv="${root_sv%\]}"
    ;;
  *)
    rootdev="$raw_src"
    # If no explicit subvol was used, treat top-level as root and pick a conventional name.
    root_sv="@"
    ;;
esac

echo "btrfs-subvol-setup: raw_src=$raw_src rootdev=$rootdev root_sv=$root_sv" >&2

# Mount top-level root (id=5)
mkdir -p /mnt/btrfs-root
mount -o subvolid=5 "$rootdev" /mnt/btrfs-root

root_path="/mnt/btrfs-root/$root_sv"
[ -d "$root_path" ] || root_path="/mnt/btrfs-root"

echo "btrfs-subvol-setup: existing subvolumes under /mnt/btrfs-root:" >&2
btrfs subvolume list /mnt/btrfs-root >&2 || true

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
    rsync -aXS "$root_path/$rel/" "/mnt/btrfs-root/$dest_sv/"
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
""" .trimIndent()

writeFile(ISO_WORK_DIR.resolve("btrfs-subvol-setup.sh"), btrfsSubvolSetup)
chmod(ISO_WORK_DIR.resolve("btrfs-subvol-setup.sh"), "755")
println("[✓] btrfs-subvol-setup.sh created")

// ----------------------------- firstboot-btrfs-snapper scripts -----------------------------
// Run very early in boot, before local-fs.target, so subvols + fstab are ready
val firstbootScript = """#!/bin/bash
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
systemctl disable firstboot-btrfs-snapper.service || true
""".trimIndent()

writeFile(ISO_WORK_DIR.resolve("firstboot-btrfs-snapper.sh"), firstbootScript)
chmod(ISO_WORK_DIR.resolve("firstboot-btrfs-snapper.sh"), "755")

// Early-boot unit: before local-fs.target, wanted by local-fs.target
val firstbootService = """[Unit]
Description=First boot Btrfs subvol + Snapper setup
DefaultDependencies=no
After=local-fs-pre.target
Before=local-fs.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/firstboot-btrfs-snapper.sh

[Install]
WantedBy=local-fs.target
""".trimIndent()

writeFile(ISO_WORK_DIR.resolve("firstboot-btrfs-snapper.service"), firstbootService)
println("[✓] firstboot scripts and service created")

// ----------------------------- preseed.cfg -----------------------------
val preseed = """### Debian 13 Preseed — minimal automation, with KDE + sane defaults.

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
""".trimIndent()

writeFile(ISO_WORK_DIR.resolve("preseed.cfg"), preseed)
println("[✓] preseed.cfg created")

// ----------------------------- Inject preseed into initrd -----------------------------
println("\n[STEP] Injecting preseed.cfg into initrd...")
val initrdDir = ISO_WORK_DIR.resolve("install.amd").toFile()
println("[INFO] initrd directory: ${initrdDir.absolutePath}")

println("[STEP] Making ISO work directory writable...")
runCmd("bash", "-lc", "chmod -R +w '${ISO_WORK_DIR}'")
    .also { requireSuccess(it, "chmod -R +w ISO_WORK_DIR") }

Files.copy(
    ISO_WORK_DIR.resolve("preseed.cfg"),
    initrdDir.toPath().resolve("preseed.cfg"),
    StandardCopyOption.REPLACE_EXISTING
)

println("[STEP] Checking initrd.gz is a valid gzip archive...")
val checkInitrd = runCmd(
    "bash", "-lc",
    "cd '${initrdDir.absolutePath}' && gzip -t initrd.gz"
)
requireSuccess(checkInitrd, "verify initrd.gz is gzip-compressed")
println("[INFO] initrd.gz passed gzip -t")

println("[STEP] Unpacking initrd, injecting preseed.cfg, and repacking (this may take a moment)...")
val injectCmd = """
    cd '${initrdDir.absolutePath}' &&
    gunzip initrd.gz &&
    echo preseed.cfg | cpio -H newc -o -A -F initrd &&
    gzip initrd
""".trimIndent()

val injectResult = runCmd("bash", "-lc", injectCmd)
requireSuccess(injectResult, "initrd injection")
println("[✓] preseed.cfg injected into initrd")

// ----------------------------- Boot configs -----------------------------
println("\n[STEP] Configuring boot loaders...")
val isolinuxCfg = """default auto
prompt 0
timeout 0

label auto
  kernel /install.amd/vmlinuz
  append initrd=/install.amd/initrd.gz auto=true priority=high file=/preseed.cfg netcfg/dhcp_timeout=30
""".trimIndent()

writeFile(ISO_WORK_DIR.resolve("isolinux/isolinux.cfg"), isolinuxCfg)
println("[✓] ISOLINUX config written")

val grubCfg = """set default=0
set timeout=0

menuentry "Automated install" {
  linux   /install.amd/vmlinuz auto=true priority=high file=/preseed.cfg netcfg/dhcp_timeout=30
  initrd  /install.amd/initrd.gz
}
""".trimIndent()

writeFile(ISO_WORK_DIR.resolve("boot/grub/grub.cfg"), grubCfg)
println("[✓] GRUB config written")

// ----------------------------- md5sum.txt rebuild -----------------------------
println("\n[STEP] Rebuilding md5sum.txt for modified ISO contents...")
runCmd(
    "bash", "-lc",
    "cd '${ISO_WORK_DIR}' && find . -type f ! -name md5sum.txt -print0 | xargs -0 md5sum > md5sum.txt"
).also { requireSuccess(it, "rebuild md5sum.txt") }
println("[✓] md5sum.txt rebuilt")

// ----------------------------- Build final ISO -----------------------------
println("\n[STEP] Building final ISO image...")
println("[INFO] Output file: $ISO_OUT")
if (ISO_OUT.exists()) {
    System.err.println("\nERROR: Output ISO file already exists: $ISO_OUT")
    System.err.println("Please remove it first with:\n")
    System.err.println("    sudo rm -f '$ISO_OUT'\n")
    exitProcess(1)
}
println("[INFO] Using ISOLINUX MBR: $ISOLINUX_MBR")

val buildCmd = arrayOf(
    "xorriso", "-as", "mkisofs",
    "-isohybrid-mbr", ISOLINUX_MBR,
    "-c", "isolinux/boot.cat",
    "-b", "isolinux/isolinux.bin",
    "-no-emul-boot",
    "-boot-load-size", "4",
    "-boot-info-table",
    "-eltorito-alt-boot",
    "-e", "boot/grub/efi.img",
    "-no-emul-boot",
    "-isohybrid-gpt-basdat",
    "-o", ISO_OUT.toString(),
    ISO_WORK_DIR.toString()
)

println("[STEP] Running xorriso to create bootable hybrid ISO (this may take a moment)...")
runCmd(*buildCmd).also { requireSuccess(it, "build final ISO") }
println("[✓] ISO build complete")

println("\n" + "=".repeat(60))
println("Custom ISO ready: $ISO_OUT")
println("IMAGE_ID: $IMAGE_ID")
println("SSH key:   ~/.ssh/${keys.priv.fileName}")
println("NOTE: This custom ISO is not Secure Boot signed. Disable Secure Boot to boot it.")
println("=".repeat(60))
