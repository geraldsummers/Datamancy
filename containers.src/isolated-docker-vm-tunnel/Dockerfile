FROM alpine:latest

RUN apk add --no-cache \
    openssh-client \
    socat \
    avahi \
    avahi-tools \
    dbus

# Create Avahi service file for mDNS advertisement
RUN mkdir -p /etc/avahi/services

COPY labware.service /etc/avahi/services/labware.service

# Create entrypoint script
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
