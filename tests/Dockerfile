FROM mcr.microsoft.com/playwright:v1.56.1-jammy

# Trust custom CA
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*

# Install dependencies globally
COPY package*.json /tmp/
RUN cd /tmp && npm install && \
    mkdir -p /usr/local/lib/node_modules && \
    cp -r /tmp/node_modules/* /usr/local/lib/node_modules/

# Set working directory
WORKDIR /tests

# Set locale and node path
ENV LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8 \
    NODE_PATH=/usr/local/lib/node_modules

CMD ["node", "/tests/runners/test-multi.js"]
