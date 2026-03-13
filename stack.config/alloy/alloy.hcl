logging {
  level  = "info"
  format = "logfmt"
}

discovery.docker "containers" {
  host = "tcp://docker-socket-proxy:2375"
}

discovery.relabel "containers" {
  targets = discovery.docker.containers.targets

  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container"
  }

  rule {
    source_labels = ["__meta_docker_container_image"]
    target_label  = "image"
  }

  rule {
    source_labels = ["container"]
    regex         = ".*"
    action        = "keep"
  }

  rule {
    source_labels = ["container"]
    regex         = "^$"
    action        = "drop"
  }
}

loki.source.docker "containers" {
  host       = "tcp://docker-socket-proxy:2375"
  targets    = discovery.relabel.containers.output
  forward_to = [loki.write.local.receiver]
}

loki.write "local" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"
  }
}
