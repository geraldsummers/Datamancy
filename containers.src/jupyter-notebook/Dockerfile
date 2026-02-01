FROM jupyter/minimal-notebook:latest

USER root

# Install Node.js for JavaScript kernel (ijavascript) with build tools
RUN apt-get update && \
    apt-get install -y curl gnupg build-essential python3 && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install Java/Kotlin dependencies
RUN apt-get update && \
    apt-get install -y openjdk-17-jdk wget unzip && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install Kotlin compiler
RUN cd /opt && \
    wget -q https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip && \
    unzip kotlin-compiler-2.1.0.zip && \
    rm kotlin-compiler-2.1.0.zip && \
    ln -s /opt/kotlinc/bin/kotlin /usr/local/bin/kotlin && \
    ln -s /opt/kotlinc/bin/kotlinc /usr/local/bin/kotlinc

# Install JavaScript kernel (ijavascript) - as root for system-wide installation
RUN npm install -g --unsafe-perm ijavascript && \
    ijsinstall --install=global

USER ${NB_UID}

# Install Kotlin kernel
RUN pip install --no-cache-dir kotlin-jupyter-kernel && \
    python -m kotlin_kernel add-kernel

USER root

# Install LM agent programming libraries and AI frameworks in stages to avoid timeout
# Stage 1: Core libraries
RUN pip install --no-cache-dir \
    'openai>=1.0.0' \
    'python-dotenv>=1.0.0' \
    'httpx>=0.27.0' \
    'pydantic>=2.0.0' \
    'tiktoken>=0.7.0'

# Stage 2: LangChain
RUN pip install --no-cache-dir \
    'langchain>=0.3.0' \
    'langchain-openai>=0.2.0' \
    'langchain-community>=0.3.0' \
    'langgraph>=0.2.0'

# Stage 3: LlamaIndex
RUN pip install --no-cache-dir \
    'llama-index>=0.11.0' \
    'llama-index-llms-openai>=0.2.0' \
    'llama-index-embeddings-openai>=0.2.0'

# Stage 4: AutoGen
RUN pip install --no-cache-dir \
    'autogen-agentchat>=0.4.0' \
    'autogen-ext[openai]>=0.4.0'

# Stage 5: Vector/ML libraries (lightweight versions)
RUN pip install --no-cache-dir \
    'chromadb>=0.5.0' \
    'faiss-cpu>=1.8.0'

# Stage 6: Jupyter AI
RUN pip install --no-cache-dir 'jupyter-ai>=2.0.0'

# Copy startup configuration script
COPY --chown=${NB_UID}:${NB_GID} src/jupyter-notebook/startup-config.sh /usr/local/bin/startup-config.sh
RUN chmod +x /usr/local/bin/startup-config.sh

# Verify kernels are installed
RUN jupyter kernelspec list

# Configure startup hook
RUN mkdir -p /usr/local/bin/start-notebook.d && \
    echo '#!/bin/bash' > /usr/local/bin/start-notebook.d/configure-lm-agents.sh && \
    echo '/usr/local/bin/startup-config.sh' >> /usr/local/bin/start-notebook.d/configure-lm-agents.sh && \
    chmod +x /usr/local/bin/start-notebook.d/configure-lm-agents.sh

USER ${NB_UID}
WORKDIR /home/jovyan
