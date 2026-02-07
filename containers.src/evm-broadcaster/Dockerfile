FROM python:3.11-slim

WORKDIR /app

RUN pip install flask web3 requests

COPY app/ /app/

EXPOSE 8081

CMD ["python", "main.py"]
