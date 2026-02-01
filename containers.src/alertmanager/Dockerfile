# Multi-stage build to add envsubst to prom/alertmanager
# Stage 1: Create a minimal envsubst with all dependencies
FROM alpine:3.22 AS envsubst-builder
RUN apk add --no-cache gettext

# Find all shared library dependencies
RUN mkdir -p /envsubst-root/usr/bin /envsubst-root/lib && \
    cp /usr/bin/envsubst /envsubst-root/usr/bin/ && \
    ldd /usr/bin/envsubst | grep '=>' | awk '{print $3}' | xargs -I {} cp {} /envsubst-root/lib/ || true && \
    ldd /usr/bin/envsubst | grep '/lib/' | grep -v '=>' | awk '{print $1}' | xargs -I {} cp {} /envsubst-root/lib/ || true

# Stage 2: Copy envsubst and dependencies into alertmanager
FROM prom/alertmanager:v0.28.1

USER root

# Copy envsubst binary and its dependencies
COPY --from=envsubst-builder /envsubst-root/usr/bin/envsubst /usr/local/bin/envsubst
COPY --from=envsubst-builder /envsubst-root/lib/* /usr/local/lib/

# Update library path and verify envsubst works
ENV LD_LIBRARY_PATH=/usr/local/lib:$LD_LIBRARY_PATH
RUN chmod +x /usr/local/bin/envsubst && \
    /usr/local/bin/envsubst --version

USER nobody

# Entrypoint will be mounted from configs at runtime
