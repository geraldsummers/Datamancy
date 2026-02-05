#!/bin/bash
set -e
echo "Generating DKIM keys..."
setup config dkim
echo "Copying DKIM config to runtime location..."
cp /tmp/docker-mailserver/opendkim/KeyTable /etc/opendkim/
cp /tmp/docker-mailserver/opendkim/SigningTable /etc/opendkim/
cp -r /tmp/docker-mailserver/opendkim/keys/* /etc/opendkim/keys/
echo "Adding localhost to TrustedHosts..."
cat > /etc/opendkim/TrustedHosts <<EOF
127.0.0.1
::1
172.16.0.0/12
EOF
echo "Fixing key permissions..."
chown -R opendkim:opendkim /etc/opendkim/keys
chmod 600 /etc/opendkim/keys/*/mail.private
echo "Restarting OpenDKIM..."
supervisorctl restart opendkim
echo "DKIM setup complete!"
