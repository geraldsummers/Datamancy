# Test Runner Dockerfile
# Provenance: Playwright official images
# Purpose: Self-contained test runner with CA trust

FROM mcr.microsoft.com/playwright:v1.44.0-jammy

WORKDIR /tests

# Copy CA certificate and trust it
COPY ../certs/ca.crt /usr/local/share/ca-certificates/datamancy-ca.crt
RUN update-ca-certificates

# Set Node to trust system certificates
ENV NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/datamancy-ca.crt
ENV NODE_TLS_REJECT_UNAUTHORIZED=0

# Copy test files
COPY package*.json ./
RUN npm ci

COPY . .

# Run tests by default
CMD ["npm", "test"]
