# Multi-stage build to add envsubst to prom/alertmanager
# Stage 1: Get envsubst from Alpine
FROM alpine:3.22 AS envsubst-builder
RUN apk add --no-cache gettext

# Stage 2: Copy envsubst into alertmanager
FROM prom/alertmanager:v0.28.1

USER root

# Copy envsubst binary from builder stage
COPY --from=envsubst-builder /usr/bin/envsubst /usr/local/bin/envsubst

# Verify envsubst is executable
RUN chmod +x /usr/local/bin/envsubst && \
    /usr/local/bin/envsubst --help >/dev/null 2>&1

USER nobody

# Entrypoint will be mounted from configs at runtime
