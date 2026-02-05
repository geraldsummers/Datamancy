# Network Configuration Conflict Resolution

## The Problem

Your server has **THREE** network management systems all trying to control `eno1`:

```
1. systemd-networkd  → Says: "Use static IP 192.168.0.11, DHCP=no"
2. NetworkManager    → Says: "Use DHCP, got 192.168.0.14"
3. ifupdown          → Says: "Use DHCP" (we already fixed this)
```

**Result:** Dual IP addresses, routing conflicts, connection drops

---

## Evidence

### Current State:
```bash
$ ip addr show eno1 | grep inet
inet 192.168.0.11/24  ← systemd-networkd (correct)
inet 192.168.0.14/24  ← NetworkManager DHCP (conflict!)
```

### NetworkManager Status:
```bash
$ nmcli device show eno1
GENERAL.STATE: 100 (connected)
GENERAL.CONNECTION: Wired connection 1
IP4.ADDRESS[1]: 192.168.0.14/24  ← NetworkManager DHCP
IP4.ADDRESS[2]: 192.168.0.11/24  ← systemd-networkd static
```

### systemd-networkd Config:
```bash
$ cat /etc/systemd/network/20-wired-static.network
[Match]
Name=eno1

[Network]
Address=192.168.0.11/24
Gateway=192.168.0.1
DNS=1.1.1.1
DNS=8.8.8.8
DHCP=no  ← Correctly configured
```

---

## Root Cause

**NetworkManager is enabled and managing eno1**, even though systemd-networkd is configured to handle it.

On Debian/Ubuntu systems, NetworkManager often auto-creates connections for any interface it sees. When the system boots:

1. systemd-networkd starts and configures `eno1` with 192.168.0.11 (static)
2. NetworkManager starts and sees `eno1` is "available"
3. NetworkManager creates "Wired connection 1" and runs DHCP
4. DHCP assigns 192.168.0.14
5. Both IPs exist on the same interface → **CONFLICT**

---

## Why This Happens

### Boot Sequence:
```
1. Kernel brings up eno1 hardware
2. systemd-networkd.service starts → configures 192.168.0.11
3. NetworkManager.service starts → "Oh, eno1 exists! Let me manage it!"
4. NetworkManager runs DHCP → gets 192.168.0.14
5. Both services now "own" eno1
```

### Why Previous Fix Didn't Persist:

The `fix-network-config.sh` script:
- ✅ Fixed `/etc/network/interfaces` (removed ifupdown DHCP)
- ✅ Fixed TCP keepalive in `/etc/sysctl.conf`
- ✅ Released DHCP lease with `dhclient -r`

But it **didn't address NetworkManager** because NetworkManager wasn't obviously active at the time. After reboot:
- systemd-networkd applied its config (correct)
- NetworkManager re-created its "Wired connection 1" (conflict)

---

## The Solution

**Option 1: Disable NetworkManager for eno1** ⭐ **RECOMMENDED**

This keeps NetworkManager running (useful for laptops with WiFi) but tells it to ignore `eno1`.

**Run the script:**
```bash
scp disable-networkmanager-eno1.sh gerald@latium.local:~/
ssh gerald@latium.local "sudo ./disable-networkmanager-eno1.sh"
```

**What it does:**
1. Deletes NetworkManager's "Wired connection 1" for eno1
2. Creates `/etc/NetworkManager/conf.d/99-unmanage-eno1.conf`:
   ```ini
   [keyfile]
   unmanaged-devices=interface-name:eno1
   ```
3. Reloads NetworkManager
4. systemd-networkd now has exclusive control

**Result after reboot:** Only 192.168.0.11, no DHCP

---

**Option 2: Completely Disable NetworkManager** (Nuclear option)

If you don't need NetworkManager at all (no WiFi, no GUI network management):

```bash
sudo systemctl disable NetworkManager
sudo systemctl stop NetworkManager
sudo systemctl restart systemd-networkd
```

**Pros:** Simpler, guaranteed no conflicts
**Cons:** Loses GUI network tools, can't easily manage WiFi

---

**Option 3: Disable systemd-networkd, Use Only NetworkManager**

Convert your static IP to NetworkManager:

```bash
# Delete systemd-networkd config
sudo rm /etc/systemd/network/20-wired-static.network
sudo systemctl disable systemd-networkd
sudo systemctl stop systemd-networkd

# Configure NetworkManager for static IP
sudo nmcli connection modify "Wired connection 1" \
  ipv4.method manual \
  ipv4.addresses 192.168.0.11/24 \
  ipv4.gateway 192.168.0.1 \
  ipv4.dns "1.1.1.1 8.8.8.8"
sudo nmcli connection up "Wired connection 1"
```

**Pros:** Single source of truth
**Cons:** Requires reconfiguration, loses systemd-networkd benefits

---

## Recommended Approach

✅ **Use Option 1: Disable NetworkManager for eno1**

This is the cleanest solution because:
- ✅ Keeps both systems installed (flexibility)
- ✅ Clear ownership: systemd-networkd owns eno1
- ✅ NetworkManager available for other interfaces (WiFi, VPN, etc.)
- ✅ Survives reboots (config persists)
- ✅ Easy to verify (nmcli shows eno1 as "unmanaged")

---

## Deployment Steps

### 1. Copy and Run Script:
```bash
scp disable-networkmanager-eno1.sh gerald@latium.local:~/
ssh gerald@latium.local "sudo ./disable-networkmanager-eno1.sh"
```

### 2. Verify Immediately:
```bash
ssh gerald@latium.local "nmcli device status | grep eno1"
# Should show: eno1: unmanaged

ssh gerald@latium.local "ip addr show eno1 | grep 'inet '"
# Should show only: inet 192.168.0.11/24
```

### 3. Test After Reboot:
```bash
ssh gerald@latium.local "sudo reboot"
# Wait 2 minutes
ssh gerald@latium.local "ip addr show eno1 | grep 'inet ' | wc -l"
# Should show: 1 (only one IP)
```

---

## How to Verify Fix

### Check NetworkManager Status:
```bash
nmcli device status | grep eno1
```
**Expected:** `eno1  ethernet  unmanaged  --`

### Check IP Addresses:
```bash
ip addr show eno1 | grep inet
```
**Expected:** Only one line: `inet 192.168.0.11/24`

### Check systemd-networkd Control:
```bash
networkctl status eno1
```
**Expected:**
```
State: routable (configured)
Address: 192.168.0.11
```

### Check for DHCP Processes:
```bash
ps aux | grep dhclient | grep eno1
```
**Expected:** No output (no DHCP client running)

---

## Understanding the Systems

### systemd-networkd:
- **Purpose:** Modern, minimal network configuration
- **Config:** `/etc/systemd/network/*.network`
- **Best for:** Servers, static IPs, simple configs
- **Control:** `networkctl`, `systemctl`

### NetworkManager:
- **Purpose:** Dynamic network management (GUI-friendly)
- **Config:** `/etc/NetworkManager/` + runtime connections
- **Best for:** Laptops, WiFi, VPN, desktop environments
- **Control:** `nmcli`, `nmtui`, GUI applets

### Why Both Exist:
- systemd-networkd: Fast, simple, perfect for servers
- NetworkManager: Feature-rich, desktop-friendly, handles complex scenarios

**On a server:** Usually pick ONE and disable the other
**On a laptop:** NetworkManager is better (WiFi roaming, VPN, etc.)
**Your case:** You have systemd-networkd config, so disable NetworkManager for eno1

---

## Troubleshooting

### If Dual IP Persists After Script:

```bash
# Manually remove DHCP IP
sudo ip addr del 192.168.0.14/24 dev eno1

# Force NetworkManager to forget eno1
sudo nmcli connection show | grep eno1 | awk '{print $1}' | xargs -r sudo nmcli connection delete

# Restart systemd-networkd
sudo systemctl restart systemd-networkd
```

### If Network Stops Working:

```bash
# Emergency: Restart both systems
sudo systemctl restart systemd-networkd
sudo systemctl restart NetworkManager

# Check logs
sudo journalctl -u systemd-networkd -n 50
sudo journalctl -u NetworkManager -n 50
```

### To Revert Changes:

```bash
# Remove unmanaged config
sudo rm /etc/NetworkManager/conf.d/99-unmanage-eno1.conf
sudo systemctl reload NetworkManager
```

---

## Long-Term Stability

After applying the fix, your network will be:

✅ **Single source of truth:** systemd-networkd only
✅ **No DHCP conflicts:** NetworkManager ignores eno1
✅ **Survives reboots:** Config persists in `/etc/NetworkManager/conf.d/`
✅ **Clean routing:** Single IP, single gateway
✅ **No connection drops:** TCP keepalive working properly

Combined with the TCP keepalive fix from `fix-network-config.sh`, you should have:
- ✅ Single IP: 192.168.0.11
- ✅ TCP keepalive: 300s (5 minutes)
- ✅ No NetworkManager interference
- ✅ Stable SSH connections
- ✅ Reliable long-running downloads

---

## Files Involved

| File | Purpose | Status |
|------|---------|--------|
| `/etc/systemd/network/20-wired-static.network` | systemd-networkd config (static IP) | ✅ Correct |
| `/etc/network/interfaces` | ifupdown config | ✅ Fixed (DHCP removed) |
| `/etc/NetworkManager/conf.d/99-unmanage-eno1.conf` | Tell NM to ignore eno1 | ⚠️ Needs creation |
| `/etc/sysctl.conf` | TCP keepalive settings | ✅ Fixed |

---

## Summary

**The culprit:** NetworkManager auto-managing eno1 despite systemd-networkd config

**The fix:** Tell NetworkManager to unmanage eno1

**The result:** Clean, stable, single-IP network configuration

**Run this:**
```bash
scp disable-networkmanager-eno1.sh gerald@latium.local:~/
ssh gerald@latium.local "sudo ./disable-networkmanager-eno1.sh"
```

Then reboot and verify only one IP exists. Problem solved permanently! 🎯
