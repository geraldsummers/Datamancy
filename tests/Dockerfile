# Lightweight browser automation image with Chrome + Puppeteer preinstalled
FROM ghcr.io/puppeteer/puppeteer:latest

# Workdir only; test files will be bind-mounted at runtime
WORKDIR /tests

# Ensure CA store exists for optional update-ca-certificates in entry command
USER root
RUN mkdir -p /usr/local/share/ca-certificates
