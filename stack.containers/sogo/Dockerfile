FROM easytechdrp/sogo:5.12.2

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        sope4.9-gdl1-postgresql \
        gnustep-dl2-postgresql-adaptor \
    && rm -rf /var/lib/apt/lists/*
