# Mailserver Configuration

## Post-Deployment Setup

After deploying the mailserver for the first time, you need to run the DKIM setup script:

```bash
docker exec mailserver /tmp/docker-mailserver/setup-dkim.sh
```

This script will:
1. Generate DKIM keys for your domain
2. Copy keys to the correct location for OpenDKIM
3. Configure TrustedHosts to allow localhost signing
4. Fix file permissions
5. Restart OpenDKIM

## DKIM DNS Record

After running the setup script, extract your DKIM public key:

```bash
docker exec mailserver cat /tmp/docker-mailserver/opendkim/keys/datamancy.net/mail.txt
```

Add the TXT record to your DNS:
- Hostname: `mail._domainkey.yourdomain.com`
- Type: `TXT`
- Value: The public key from the command above

## Dovecot LDAP Authentication

The `dovecot-ldap.conf.ext` file configures LDAP authentication for email users.

**Key Configuration:**
- Uses static UID/GID (5000) for all virtual mailboxes
- This is required because docker-mailserver uses a single system user for all email
- Mailbox locations are based on email local part (admin@domain.com → /var/mail/admin/)

## Disabling Amavis

If amavis crashes (common issue), disable it temporarily:

```bash
docker exec mailserver postconf -e 'content_filter ='
docker exec mailserver postfix reload
```

This disables virus/spam scanning but allows email delivery to work.

## Testing Email Delivery

Send a test email:

```bash
docker exec mailserver swaks \
  --to admin@yourdomain.com \
  --from test@yourdomain.com \
  --server localhost:25 \
  --header "Subject: Test" \
  --body "Test message"
```

Check delivery logs:

```bash
docker exec mailserver tail -f /var/log/mail/mail.log
```

## Common Issues

### "User is missing UID" error
- **Cause:** dovecot-ldap.conf.ext not configured with static UID/GID
- **Fix:** Ensure `user_attrs` includes `=uid=5000,=gid=5000`

### "DKIM: no signature data"
- **Cause:** OpenDKIM can't find keys or localhost not in TrustedHosts
- **Fix:** Run setup-dkim.sh script

### "Amavis connection refused"
- **Cause:** Amavis service crashlooping
- **Fix:** Disable content_filter as documented above
