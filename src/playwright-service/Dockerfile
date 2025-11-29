FROM mcr.microsoft.com/playwright/python:v1.48.0-jammy

WORKDIR /app

# Install Python dependencies
RUN pip install --no-cache-dir flask playwright gunicorn

# Install Firefox browser
RUN playwright install firefox

COPY app.py .

EXPOSE 3000

CMD ["gunicorn", "--bind", "0.0.0.0:3000", "--workers", "2", "--timeout", "60", "app:app"]
