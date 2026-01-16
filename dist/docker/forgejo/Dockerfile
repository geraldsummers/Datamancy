# Custom Forgejo image with baked-in Datamancy repository
FROM codeberg.org/forgejo/forgejo:9.0

USER root

# Install git and dependencies
RUN apk add --no-cache git bash sqlite

# Copy the Datamancy source (excluding dist/, .gradle/, etc.)
COPY --chown=git:git . /tmp/datamancy-source/

# Create initialization script that will run on first start
COPY --chown=git:git docker/forgejo/init-datamancy-repo.sh /usr/local/bin/init-datamancy-repo.sh
RUN chmod +x /usr/local/bin/init-datamancy-repo.sh

# Set up s6 oneshot service to run our init script
RUN mkdir -p /etc/s6/datamancy-init && \
    echo '#!/bin/sh' > /etc/s6/datamancy-init/run && \
    echo '/usr/local/bin/init-datamancy-repo.sh' >> /etc/s6/datamancy-init/run && \
    echo 'touch /etc/s6/datamancy-init/down' >> /etc/s6/datamancy-init/run && \
    chmod +x /etc/s6/datamancy-init/run && \
    touch /etc/s6/datamancy-init/down

USER git
