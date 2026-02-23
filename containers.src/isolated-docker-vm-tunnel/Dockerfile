FROM alpine:latest

RUN apk add --no-cache \
    openssh-client \
    socat \
    wget

# Create entrypoint script
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
