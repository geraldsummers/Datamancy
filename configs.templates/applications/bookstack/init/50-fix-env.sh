#!/bin/bash
# Fix BookStack .env file with correct database credentials
ENV_FILE="/app/www/.env"

if [ -f "$ENV_FILE" ]; then
    echo "Updating BookStack .env file..."

    # Update DB_HOST
    if [ -n "$DB_HOST" ]; then
        sed -i "s|^DB_HOST=.*|DB_HOST=$DB_HOST|" "$ENV_FILE"
    fi

    # Update DB_USERNAME
    if [ -n "$DB_USER" ]; then
        sed -i "s|^DB_USERNAME=.*|DB_USERNAME=$DB_USER|" "$ENV_FILE"
    fi

    # Update DB_PASSWORD
    if [ -n "$DB_PASS" ]; then
        sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$DB_PASS|" "$ENV_FILE"
    fi

    # Update DB_DATABASE
    if [ -n "$DB_DATABASE" ]; then
        sed -i "s|^DB_DATABASE=.*|DB_DATABASE=$DB_DATABASE|" "$ENV_FILE"
    fi

    echo "BookStack .env file updated successfully"
else
    echo "BookStack .env file not found at $ENV_FILE"
fi
