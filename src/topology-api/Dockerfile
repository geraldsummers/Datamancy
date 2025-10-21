FROM node:20-alpine

WORKDIR /app

# Copy package files
COPY package.json ./

# Install dependencies
RUN npm install --production

# Copy application code
COPY server.js ./

# Expose port
EXPOSE 3100

# Start server
CMD ["node", "server.js"]
