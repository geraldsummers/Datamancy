# Docker Compose Fundamentals

## Service Structure

A Docker Compose service definition consists of several key components:

### Basic Service Definition
```yaml
services:
  service-name:
    image: organization/image:tag
    container_name: unique-container-name
    restart: unless-stopped
    networks:
      - network-name
    volumes:
      - volume-name:/path/in/container
    environment:
      VARIABLE_NAME: ${VARIABLE_NAME}
    ports:
      - "host_port:container_port"
```

### Image vs Build
- Use `image:` for pre-built images
- Use `build:` with context and Dockerfile for custom builds
- Always specify tags, never use `:latest` in production

### Container Naming
- Use descriptive, unique `container_name` for easy management
- Follow pattern: `service-purpose` or `project-service`
- Avoid special characters except hyphens

## Networks

### Network Types
- **Bridge Networks**: Default, isolated communication between containers
- **Host Network**: Container shares host networking (use sparingly)
- **Custom Networks**: Named networks for service grouping

### Best Practices
```yaml
networks:
  frontend:
    name: myapp_frontend
  backend:
    name: myapp_backend

services:
  web:
    networks:
      - frontend
      - backend
  db:
    networks:
      - backend
```

**Why?** Separate frontend/backend networks provide security isolation.

## Volumes

### Volume Types
1. **Named Volumes**: Docker-managed, persistent
   ```yaml
   volumes:
     - db_data:/var/lib/postgresql/data
   ```

2. **Bind Mounts**: Host directory mounts
   ```yaml
   volumes:
     - ./config:/app/config:ro
   ```

3. **tmpfs**: In-memory, ephemeral
   ```yaml
   tmpfs:
     - /tmp
   ```

### Database Volumes
**Always use named volumes for databases** to persist data across container restarts.

```yaml
services:
  postgres:
    image: postgres:15
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
    driver: local
```

## Environment Variables

### Security Best Practices
**Never hardcode secrets in compose files.**

❌ Bad:
```yaml
environment:
  POSTGRES_PASSWORD: mysecretpassword123
```

✅ Good:
```yaml
environment:
  POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
```

### Variable Substitution
- Use `.env` file for development
- Use environment variables in production
- Reference: `${VAR_NAME}` or `${VAR_NAME:-default}`

## Dependencies

### depends_on
```yaml
services:
  web:
    depends_on:
      - db
  db:
    image: postgres:15
```

**Limitation**: Only waits for container start, not readiness.

### Health Checks
Use health checks for true readiness:

```yaml
services:
  db:
    image: postgres:15
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
```

## Port Mappings

### Format
```yaml
ports:
  - "host_port:container_port"
  - "127.0.0.1:8080:80"  # Bind to localhost only
```

### Common Mistakes
❌ Port conflicts: Multiple services mapping to same host port
```yaml
# This will fail!
web1:
  ports:
    - "8080:80"
web2:
  ports:
    - "8080:8080"  # Conflict!
```

✅ Use different host ports:
```yaml
web1:
  ports:
    - "8080:80"
web2:
  ports:
    - "8081:8080"
```

## Restart Policies

- `no`: Never restart (default)
- `always`: Always restart
- `unless-stopped`: Restart unless manually stopped
- `on-failure`: Restart only on error

**Production**: Use `unless-stopped` for most services.

## Labels

Used for container metadata and service discovery:

```yaml
labels:
  - "traefik.enable=true"
  - "com.datamancy.service=web"
```

## Resource Limits

```yaml
services:
  web:
    deploy:
      resources:
        limits:
          cpus: '2.0'
          memory: 2G
        reservations:
          cpus: '1.0'
          memory: 1G
```

## Common Patterns

### Web Application Stack
```yaml
services:
  web:
    image: nginx:latest
    depends_on:
      - app
    networks:
      - frontend

  app:
    image: myapp:latest
    depends_on:
      - db
    networks:
      - frontend
      - backend

  db:
    image: postgres:15
    networks:
      - backend
    volumes:
      - db_data:/var/lib/postgresql/data
```

### Microservices Pattern
- Each service in its own service definition
- Shared networks for communication
- Separate data volumes per service
- Health checks for dependencies
