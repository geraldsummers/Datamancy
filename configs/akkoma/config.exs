import Config

# Akkoma/Pleroma configuration
# This file is loaded as prod.secret.exs and must set base_url FIRST

# CRITICAL: Set base_url for uploads BEFORE any other config to prevent fatal crash
config :pleroma, Pleroma.Upload,
  uploader: Pleroma.Uploaders.Local,
  filters: [Pleroma.Upload.Filter.Dedupe],
  link_name: false,
  base_url: System.get_env("AKKOMA_UPLOAD_BASE_URL") || "https://akkoma.project-saturn.com/media"

config :pleroma, Pleroma.Uploaders.Local,
  uploads: System.get_env("UPLOADS_DIR") || "/var/lib/akkoma/uploads"

domain = System.get_env("INSTANCE_HOST") || "akkoma.project-saturn.com"

config :pleroma, Pleroma.Web.Endpoint,
  url: [host: domain, scheme: "https", port: 443],
  http: [ip: {0, 0, 0, 0}, port: 4000]

config :pleroma, :instance,
  name: System.get_env("INSTANCE_NAME") || "Project Saturn Social",
  email: "admin@project-saturn.com",
  notify_email: "admin@project-saturn.com",
  limit: 5000,
  registrations_open: false

config :pleroma, Pleroma.Repo,
  adapter: Ecto.Adapters.Postgres,
  username: System.get_env("DATABASE_USER") || "akkoma",
  password: System.get_env("DATABASE_PASSWORD"),
  database: System.get_env("DATABASE_NAME") || "akkoma",
  hostname: System.get_env("DATABASE_HOST") || "postgres",
  pool_size: 10

config :web_push_encryption, :vapid_details,
  subject: "mailto:admin@project-saturn.com",
  public_key: System.get_env("VAPID_PUBLIC_KEY") || "",
  private_key: System.get_env("VAPID_PRIVATE_KEY") || ""
