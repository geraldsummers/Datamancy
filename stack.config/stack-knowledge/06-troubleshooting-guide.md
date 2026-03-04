# Datamancy Stack Troubleshooting Guide

## Service Won't Start

### Symptom: Container exits immediately

**Check logs**:
```bash
docker compose logs service-name
```

**Common causes**:

1. **Missing environment variable**
   ```
   Error: POSTGRES_PASSWORD is not set
   ```
   **Fix**: Check `.credentials` file has the variable

2. **Port already in use**
   ```
   Error: bind: address already in use
   ```
   **Fix**: Check what's using the port
   ```bash
   sudo netstat -tlnp | grep :PORT
   docker compose ps | grep PORT
   ```

3. **Volume permission issues**
   ```
   Error: Permission denied: '/data'
   ```
   **Fix**: Check volume ownership
   ```bash
   docker volume inspect volume_name
   ls -la /mnt/path/to/volume
   ```

4. **Dependency not ready**
   ```
   Error: could not connect to postgres
   ```
   **Fix**: Check dependency health
   ```bash
   docker compose ps postgres
   docker compose logs postgres
   ```

### Symptom: Container in restart loop

**Check restart count**:
```bash
docker compose ps
# Look for "Restarting (1)" or high restart counts
```

**Common causes**:

1. **Health check failing**
   ```bash
   docker inspect container-name | grep -A 10 Health
   ```

2. **Configuration error causing immediate crash**
   - Check logs for specific error
   - Verify config file syntax

3. **Resource exhaustion**
   ```bash
   docker stats
   # Check CPU/Memory usage
   ```

## Can't Access Service via Browser

### Symptom: Connection refused or timeout

**Diagnostic steps**:

1. **Check if service is running**
   ```bash
   docker compose ps service-name
   # Should show "Up" status
   ```

2. **Check if port is exposed**
   ```bash
   docker compose port service-name internal-port
   # Should show host:port mapping
   ```

3. **Test internal connectivity**
   ```bash
   docker exec another-container curl http://service-name:port
   ```

4. **Check Caddy configuration**
   ```bash
   docker compose logs caddy | grep service-name
   # Look for routing errors
   ```

5. **Check Authelia authentication**
   ```bash
   docker compose logs authelia | grep "access denied"
   ```

### Symptom: 401 Unauthorized

**Cause**: Authelia authentication issue

**Diagnostic**:
```bash
# Check Authelia logs
docker compose logs authelia | tail -50

# Check if user exists in LDAP
docker exec ldap ldapsearch -x -b "dc=datamancy,dc=net" "(uid=username)"
```

**Fix**:
- Reset password in LDAP
- Check Authelia configuration for the service
- Clear browser cookies

### Symptom: 502 Bad Gateway

**Cause**: Caddy can reach service but service isn't responding

**Diagnostic**:
```bash
# Check if service is healthy
docker compose ps service-name

# Check service logs
docker compose logs service-name | tail -50

# Test service directly
docker exec caddy curl http://service-name:port/health
```

**Fix**:
- Restart the service
- Check service configuration
- Verify service health check

## Database Connection Issues

### PostgreSQL

**Symptom**: Can't connect to database

**Diagnostic**:
```bash
# Check if PostgreSQL is running
docker compose ps postgres

# Check logs for errors
docker compose logs postgres | grep ERROR

# Test connection
docker exec postgres psql -U postgres -c "SELECT version();"
```

**Common issues**:

1. **Wrong credentials**
   ```bash
   # Verify password in .credentials
   grep POSTGRES_ .credentials

   # Test with correct password
   docker exec postgres psql -U username -d dbname
   ```

2. **Database doesn't exist**
   ```bash
   # List databases
   docker exec postgres psql -U postgres -c "\l"

   # Create database
   docker exec postgres psql -U postgres -c "CREATE DATABASE dbname;"
   ```

3. **User doesn't exist**
   ```bash
   # List users
   docker exec postgres psql -U postgres -c "\du"

   # Create user
   docker exec postgres psql -U postgres -c "CREATE USER username WITH PASSWORD 'password';"
   docker exec postgres psql -U postgres -c "GRANT ALL ON DATABASE dbname TO username;"
   ```

4. **Not on postgres network**
   ```bash
   # Check service networks
   docker inspect service-container | grep -A 10 Networks

   # Should include "postgres" network
   ```

### Qdrant

**Symptom**: Can't connect to Qdrant

**Diagnostic**:
```bash
# Check if Qdrant is running
docker compose ps qdrant

# Test connection
curl http://localhost:6333/collections
```

**Common issues**:

1. **Service not on qdrant network**
   - Check docker-compose.yml for `networks: - qdrant`

2. **Collection doesn't exist**
   ```bash
   # List collections
   curl http://qdrant:6333/collections

   # Create collection (if needed)
   curl -X PUT http://qdrant:6333/collections/collection_name \
     -H "Content-Type: application/json" \
     -d '{"vectors": {"size": 1024, "distance": "Cosine"}}'
   ```

## LLM/AI Service Issues

### LiteLLM Not Responding

**Diagnostic**:
```bash
# Check if LiteLLM is running
docker compose ps litellm

# Check logs
docker compose logs litellm | tail -100

# Test health
curl http://litellm:4000/health
```

**Common issues**:

1. **Model not loaded**
   ```
   Error: Model not found
   ```
   **Fix**: Check vLLM service is running
   ```bash
   docker compose ps vllm
   docker compose logs vllm
   ```

2. **Out of memory**
   ```
   Error: CUDA out of memory
   ```
   **Fix**: Restart vLLM or reduce model load
   ```bash
   docker compose restart vllm
   ```

### Agent Tool Server Issues

**Diagnostic**:
```bash
# Check if agent-tool-server is running
docker compose ps agent-tool-server

# Check logs
docker compose logs agent-tool-server | tail -100

# Test health
curl http://agent-tool-server:8081/health

# List available tools
curl http://agent-tool-server:8081/tools
```

**Common issues**:

1. **Tool execution fails**
   - Check logs for specific error
   - Verify tool has required dependencies
   - Check permissions for Docker socket access (docker_ps, docker_container_list, etc.)

2. **Database tool fails**
   - Verify database connection
   - Check user permissions
   - Review query for SQL errors

### Search Service Issues

**Diagnostic**:
```bash
# Check if search-service is running
docker compose ps search-service

# Check logs
docker compose logs search-service | tail -100

# Test health
curl http://search-service:8098/health

# Test search
curl -X POST http://search-service:8098/search \
  -H "Content-Type: application/json" \
  -d '{"query":"test","mode":"hybrid","limit":5}'
```

**Common issues**:

1. **Empty search results**
   - Check if collections have data
   ```bash
   curl http://qdrant:6333/collections/collection_name
   ```
   - Verify data was ingested by pipeline

2. **Search timeout**
   - Check Qdrant performance
   - Reduce `limit` parameter
   - Check if Qdrant is under load

## Network Issues

### Services Can't Communicate

**Diagnostic**:
```bash
# Check networks
docker network ls | grep datamancy

# Inspect network
docker network inspect datamancy_postgres

# Check service is on correct network
docker inspect service-container | grep -A 10 Networks
```

**Fix**:
- Add service to required networks in docker-compose.yml
- Restart services after network changes

### DNS Resolution Fails

**Symptom**: `Could not resolve host: service-name`

**Diagnostic**:
```bash
# Test DNS from inside container
docker exec container-name nslookup service-name
docker exec container-name ping service-name
```

**Fix**:
- Use Docker Compose service names (not container names)
- Ensure services are on same network
- Restart Docker daemon if DNS is completely broken
  ```bash
  sudo systemctl restart docker
  ```

## Performance Issues

### High CPU Usage

**Diagnostic**:
```bash
# Check container stats
docker stats

# Check which process is using CPU
docker exec container-name top
```

**Common culprits**:
- vLLM during model inference
- Pipeline during data ingestion
- Postgres during complex queries

**Fix**:
- Add resource limits in docker-compose.yml
- Scale back operations (e.g., reduce pipeline batch size)
- Optimize queries

### High Memory Usage

**Diagnostic**:
```bash
docker stats
# Check memory column
```

**Common culprits**:
- vLLM (models are large)
- PostgreSQL (large datasets)
- Qdrant (vector indices)

**Fix**:
- Add memory limits
- Restart services to clear memory leaks
- Scale services if on multi-node setup

### Slow Service Response

**Diagnostic**:
```bash
# Check service logs for slow queries
docker compose logs service-name | grep -i slow

# Check if database is the bottleneck
docker compose logs postgres | grep duration

# Check Qdrant query times
curl http://qdrant:6333/collections/collection_name
```

**Fix**:
- Add database indices
- Optimize queries
- Add caching (Valkey)
- Scale horizontally

## Data Issues

### Data Loss After Restart

**Cause**: Using container filesystem instead of volumes

**Diagnostic**:
```bash
# Check if service has volumes
docker inspect service-container | grep -A 10 Mounts

# Should show Type: volume, not tmpfs or none
```

**Fix**:
- Add volume to docker-compose.yml
- Migrate data before restart

### Volume Full

**Diagnostic**:
```bash
# Check disk usage
df -h

# Check volume size
docker system df -v
```

**Fix**:
- Clean up old data
- Increase volume size
- Move to larger disk

## Authentication Issues (Authelia/LDAP)

### Can't Log In

**Diagnostic**:
```bash
# Check Authelia logs
docker compose logs authelia | grep username

# Check LDAP logs
docker compose logs ldap | grep username

# Verify user exists
docker exec ldap ldapsearch -x -b "dc=datamancy,dc=net" "(uid=username)"
```

**Fix**:
1. **Reset password**
   ```bash
   docker exec ldap ldappasswd -x -D "cn=admin,dc=datamancy,dc=net" \
     -W -S "uid=username,ou=people,dc=datamancy,dc=net"
   ```

2. **Unlock account** (if locked due to failed attempts)
   ```bash
   # Check Authelia config for account unlock procedure
   docker compose logs authelia | grep "account locked"
   ```

3. **Clear cookies and try again**

### LDAP Connection Failed

**Diagnostic**:
```bash
# Check LDAP is running
docker compose ps ldap

# Test LDAP connection
docker exec ldap ldapsearch -x -b "dc=datamancy,dc=net"
```

**Fix**:
- Restart LDAP
- Check LDAP configuration in `configs/ldap/`
- Verify LDAP network connectivity

## Emergency Procedures

### Complete Stack Restart
```bash
cd ~/datamancy
docker compose down
docker compose up -d
```

### Restart Single Service
```bash
docker compose restart service-name
```

### View Real-Time Logs
```bash
docker compose logs -f service-name
```

### Check All Service Health
```bash
docker compose ps
# Look for "unhealthy" or "restarting" status
```

### Force Recreate Service
```bash
docker compose up -d --force-recreate service-name
```

### Clean Docker System (Careful!)
```bash
# Remove stopped containers
docker container prune -f

# Remove unused images
docker image prune -a -f

# Remove unused volumes (DATA LOSS WARNING!)
docker volume prune -f

# Remove unused networks
docker network prune -f
```

## Getting Help

### Collect Diagnostic Info
```bash
# Service status
docker compose ps > diagnostics.txt

# Logs for all services
docker compose logs --tail=100 >> diagnostics.txt

# System resources
docker stats --no-stream >> diagnostics.txt

# Network info
docker network ls >> diagnostics.txt

# Volume info
docker volume ls >> diagnostics.txt
```

### Useful Commands
```bash
# Check Docker daemon
sudo systemctl status docker

# Check disk space
df -h

# Check memory
free -h

# Check open files limit
ulimit -n

# Check network connectivity
ping -c 3 google.com
```
