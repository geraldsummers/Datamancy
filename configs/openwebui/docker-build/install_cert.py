#!/usr/bin/env python3
import certifi
import os

# Read the Caddy CA certificate
with open('/tmp/caddy-ca.crt', 'r') as cert_file:
    cert_data = cert_file.read()

# Get the certifi bundle path
ca_bundle = certifi.where()

# Append the Caddy CA to the bundle
with open(ca_bundle, 'a') as bundle_file:
    bundle_file.write('\n# Caddy Internal CA\n')
    bundle_file.write(cert_data)

print(f'Added Caddy CA to {ca_bundle}')
print(f'Bundle size: {os.path.getsize(ca_bundle)} bytes')
