FROM debian:trixie-slim

# Install dependencies
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    wget \
    unzip \
    openjdk-21-jdk-headless \
    ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Install Kotlin 2.0.20
WORKDIR /tmp
RUN wget https://github.com/JetBrains/kotlin/releases/download/v2.0.20/kotlin-compiler-2.0.20.zip && \
    mkdir -p /opt/kotlin-2.0.20 && \
    unzip kotlin-compiler-2.0.20.zip -d /opt/kotlin-2.0.20 && \
    rm kotlin-compiler-2.0.20.zip

# Set up environment variables
ENV KOTLIN_HOME=/opt/kotlin-2.0.20/kotlinc
ENV PATH=$PATH:$KOTLIN_HOME/bin

# Verify installation
RUN kotlinc -version

WORKDIR /root

CMD ["/bin/bash"]

