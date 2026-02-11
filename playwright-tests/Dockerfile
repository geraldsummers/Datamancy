FROM mcr.microsoft.com/playwright:v1.48.0-jammy

# Set working directory
WORKDIR /app

# Copy package files
COPY package.json package-lock.json* ./

# Install dependencies
RUN npm install

# Copy source code
COPY . .

# Run TypeScript compiler to validate
RUN npm run build

# Default command runs all tests
CMD ["npm", "test"]
