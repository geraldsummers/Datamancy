# Test Runner — Playwright browser tests
# Provenance: Phase 1 agent autonomy smoke test
FROM mcr.microsoft.com/playwright:v1.56.1-jammy

WORKDIR /tests

# Install dependencies
COPY package.json ./
RUN npm install --legacy-peer-deps

# Copy test files
COPY . .
RUN chmod +x entrypoint.sh

ENTRYPOINT ["./entrypoint.sh"]
