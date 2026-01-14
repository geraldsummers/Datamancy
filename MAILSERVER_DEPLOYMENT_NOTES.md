# Mailserver Deployment Notes

## Runtime Configuration Required

The following configuration changes must be applied to the mailserver **after deployment** to enable proper email delivery.

### 1. Rspamd DKIM Signing Configuration

**File:** `/etc/rspamd/local.d/dkim_signing.conf` (inside mailserver container)

```conf
# Enable DKIM signing
enabled = true;

# Domain configuration
domain {
  datamancy.net {
    selectors [
      {
        path = "/var/lib/rspamd/dkim/datamancy.net.mail.key";
        selector = "mail";
      }
    ]
  }
}

# Sign settings
sign_authenticated = true;
sign_local = true;
use_domain = "header";
allow_hdrfrom_mismatch = true;
allow_username_mismatch = true;
```

**Apply via:**
```bash
docker compose exec mailserver bash -c 'cat > /etc/rspamd/local.d/dkim_signing.conf <<EOF
[paste config above]
EOF'
docker compose exec mailserver supervisorctl restart rspamd
```

### 2. Generate DKIM Key

**Generate Rspamd-format DKIM key:**
```bash
docker compose exec mailserver bash -c 'mkdir -p /var/lib/rspamd/dkim && rspamadm dkim_keygen -s mail -b 2048 -d datamancy.net -k /var/lib/rspamd/dkim/datamancy.net.mail.key'
```

**Fix permissions:**
```bash
docker compose exec mailserver chown _rspamd:_rspamd /var/lib/rspamd/dkim/datamancy.net.mail.key
```

**Extract public key for DNS:**
```bash
docker compose exec mailserver rspamadm dkim_keygen -s mail -b 2048 -d datamancy.net -k /var/lib/rspamd/dkim/datamancy.net.mail.key
```

Add the output as a TXT record: `mail._domainkey.datamancy.net`

### 3. Postfix Milter Configuration

**Enable Rspamd for non-SMTP mail (sendmail):**
```bash
docker compose exec mailserver postconf -e 'non_smtpd_milters = $dkim_milter $rspamd_milter'
docker compose exec mailserver postfix reload
```

**Verify configuration:**
```bash
docker compose exec mailserver postconf non_smtpd_milters smtpd_milters
```

Expected output:
```
non_smtpd_milters = $dkim_milter $rspamd_milter
smtpd_milters = $dkim_milter $dmarc_milter $rspamd_milter
```

### 4. Fix Virtual Domain Lookup (Optional)

**Remove broken LDAP domain lookup:**
```bash
docker compose exec mailserver postconf -e 'virtual_mailbox_domains = /etc/postfix/vhost'
docker compose exec mailserver postfix reload
```

This removes the LDAP domain lookup that has an empty `query_filter` causing protocol errors.

## DNS Records Required

### SPF Record
```
datamancy.net. IN TXT "v=spf1 ip4:125.253.33.105 -all"
```

### DKIM Record
```
mail._domainkey.datamancy.net. IN TXT "v=DKIM1; k=rsa; p=[public key from step 2]"
```

### DMARC Record
```
_dmarc.datamancy.net. IN TXT "v=DMARC1; p=quarantine; rua=mailto:postmaster@datamancy.net"
```

### MX Record
```
datamancy.net. IN MX 10 mail.datamancy.net.
```

### A Record
```
mail.datamancy.net. IN A 125.253.33.105
```

### PTR Record (Reverse DNS) - **CRITICAL FOR DELIVERABILITY**
Contact ISP (Exetel) to set:
```
105.33.253.125.in-addr.arpa. IN PTR mail.datamancy.net.
```

Currently points to: `125-253-33-105.ip4.exetel.com.au`

## Verification

### Test Email Delivery
```bash
docker compose exec mailserver sendmail -f sysadmin@datamancy.net test@gmail.com <<EOF
From: sysadmin@datamancy.net
Subject: Test Email

This is a test.
EOF
```

### Check Mail Queue
```bash
docker compose exec mailserver postqueue -p
```

### Check Logs
```bash
docker compose logs mailserver --tail 50
```

### Verify DKIM Signing
Send a test email and check "Show Original" in Gmail. Look for:
- `DKIM-Signature:` header
- `Authentication-Results: ... dkim=pass`
- `spf=pass`
- `dmarc=pass`

## Known Issues

### OpenDKIM vs Rspamd
Both OpenDKIM and Rspamd are enabled. Rspamd is handling DKIM signing.
Consider disabling OpenDKIM to reduce warnings.

### LDAP Domain Lookup
The LDAP domain lookup (`ldap-domains.cf`) has an empty `query_filter` causing warnings.
Mail still works via `/etc/postfix/vhost` file.

## Deployment Checklist

After running `deploy.sh`:

1. ✅ Apply Rspamd DKIM configuration
2. ✅ Generate DKIM key (or restore from backup)
3. ✅ Fix DKIM key permissions
4. ✅ Update DNS with DKIM public key
5. ✅ Configure Postfix milters for sendmail
6. ✅ Fix virtual domain lookup (optional)
7. ✅ Restart Rspamd and Postfix
8. ✅ Send test email
9. ✅ Verify email lands in inbox (not spam)
10. ⚠️ Request PTR record change from ISP

## Success Criteria

- ✅ SPF = PASS
- ✅ DMARC = PASS
- ✅ DKIM = PASS (after fixes applied)
- ✅ Email lands in Gmail inbox
- ✅ TLS 1.3 encryption
- ⚠️ Reverse DNS matches (pending ISP change)
