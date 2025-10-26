# LibreChat

**Service:** LibreChat
**Phase:** 5 - AI Tools
**Profile:** `ai`
**Hostname:** `librechat.stack.local`
**Port:** 3080

## Purpose

LibreChat provides a web-based chat interface for AI conversations, similar to ChatGPT. Connects to LocalAI backend for local, privacy-first AI chat.

## Configuration

- **Image:** `ghcr.io/danny-avila/librechat:v0.7.5`
- **User:** 1000 (non-root)
- **Database:** MongoDB (`LibreChat` database)
- **Backend:** LocalAI at `http://localai:8080/v1`

## Access

**URL:** https://librechat.stack.local

**First Time Setup:**
1. Visit `https://librechat.stack.local`
2. Register a new account (first user becomes admin)
3. Start chatting with LocalAI models

## Features

- **Multi-model support:** Configured for LocalAI endpoint
- **Conversation history:** Stored in MongoDB
- **User management:** Registration-based accounts
- **Persistent sessions:** JWT-based authentication

## Configuration File

Located at `configs/librechat/librechat.yaml`:

```yaml
version: 1.0.8
cache: false
endpoints:
  custom:
    - name: "LocalAI"
      baseURL: "http://localai:8080/v1"
      models:
        default:
          - "gpt-3.5-turbo"
```

## Environment Variables

**Security (should be regenerated for production):**
- `CREDS_KEY` - Encryption key for user credentials
- `CREDS_IV` - Initialization vector for encryption
- `JWT_SECRET` - JWT token signing secret
- `JWT_REFRESH_SECRET` - Refresh token secret

**Generate new secrets:**
```bash
# Use LibreChat's tool
curl https://www.librechat.ai/toolkit/creds_generator
```

## MongoDB Connection

LibreChat uses MongoDB for:
- User accounts and authentication
- Conversation history
- Preferences and settings

**Connection string:** `mongodb://root:password@mongodb:27017/LibreChat?authSource=admin`

## Troubleshooting

**Can't access UI:**
- Verify container is running: `docker ps | grep librechat`
- Check logs: `docker logs librechat`
- Ensure MongoDB is running and accessible

**Login issues:**
- Clear browser cache and cookies
- Check MongoDB connection in logs
- Verify JWT secrets are set

**No AI responses:**
- Verify LocalAI is running: `docker ps | grep localai`
- Check LocalAI has models loaded
- Review browser console for API errors

**Configuration errors:**
- Validate `librechat.yaml` syntax
- Restart container after config changes: `docker restart librechat`

## Security

- **Authentication:** JWT-based with MongoDB storage
- **Capabilities:** All dropped (Phase 6 hardening)
- **User:** 1000 (non-root)
- **Network:** Frontend (Caddy) + Backend (LocalAI, MongoDB)

## References

- [LibreChat Documentation](https://docs.librechat.ai/)
- [Configuration Guide](https://docs.librechat.ai/install/configuration/)
- [Credentials Generator](https://www.librechat.ai/toolkit/creds_generator)
