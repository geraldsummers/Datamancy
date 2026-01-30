# Custom Forgejo Runner with required tools pre-installed
FROM code.forgejo.org/forgejo/runner:3.5.1

# Install dependencies as root
USER root
RUN apk add --no-cache curl jq netcat-openbsd

# Switch back to the default user
USER 1000

# Keep the default entrypoint
