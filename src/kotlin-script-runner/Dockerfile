FROM eclipse-temurin:17-jdk-jammy

# Install Kotlin compiler
RUN apt-get update && \
    apt-get install -y curl unzip zip && \
    curl -s "https://get.sdkman.io" | bash && \
    bash -c "source /root/.sdkman/bin/sdkman-init.sh && sdk install kotlin" && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Make kotlin available in PATH
ENV PATH="/root/.sdkman/candidates/kotlin/current/bin:${PATH}"

WORKDIR /scripts

CMD ["bash"]
