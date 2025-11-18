# LangGraph GUI - Build from source
FROM node:20-alpine AS builder

WORKDIR /app

# Clone the repository
RUN apk add --no-cache git && \
    git clone --depth 1 https://github.com/LangGraph-GUI/LangGraph-GUI.git .

# Build frontend
WORKDIR /app/frontend
RUN npm install && npm run build

# Runtime stage - serve with static server
FROM node:20-alpine

WORKDIR /app

# Install serve for static hosting
RUN npm install -g serve

# Copy built frontend
COPY --from=builder /app/frontend/.svelte-kit/output /app/dist

EXPOSE 3000

CMD ["serve", "-s", "/app/dist", "-l", "3000"]
