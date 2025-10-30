FROM mcr.microsoft.com/playwright:v1.56.1-jammy

WORKDIR /tests

# Install dependencies
COPY package*.json ./
RUN npm ci --omit=dev

# Trust custom CA
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*

# Set locale
ENV LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8

CMD ["node", "/tests/webui.e2e.js"]
