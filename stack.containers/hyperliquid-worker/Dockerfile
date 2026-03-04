FROM python:3.11-slim

WORKDIR /app

RUN pip install flask hyperliquid-python-sdk requests

COPY app/ /app/

EXPOSE 8082

CMD ["python", "main.py"]
