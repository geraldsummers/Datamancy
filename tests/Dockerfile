FROM mcr.microsoft.com/playwright:v1.40.0-jammy

WORKDIR /tests

# Install dependencies first
COPY package*.json tsconfig.json ./
RUN npm install

# Install Playwright browsers
RUN npx playwright install chromium

# Copy test files
COPY . .

CMD ["sleep", "infinity"]
