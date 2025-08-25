FROM jeffail/benthos:4.27.0
COPY benthos/rss_to_clickhouse.yaml /benthos/rss_to_clickhouse.yaml
ENV BENTHOS_CONFIG=/benthos/rss_to_clickhouse.yaml
CMD ["-c", "/benthos/rss_to_clickhouse.yaml"]