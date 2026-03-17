#!/usr/bin/env bash
set -euo pipefail

api_username="${MASTODON_API_USERNAME:-${STACK_ADMIN_USER:-sysadmin}}"
api_email="${MASTODON_API_EMAIL:-${STACK_ADMIN_EMAIL:-admin@datamancy.net}}"
api_password="${MASTODON_API_PASSWORD:-${STACK_ADMIN_PASSWORD:-}}"

if [ -z "${api_password}" ]; then
  echo "[mastodon-api-user] MASTODON_API_PASSWORD/STACK_ADMIN_PASSWORD is empty, skipping local API user bootstrap"
  exit 0
fi

export DATAMANCY_API_USERNAME="${api_username}"
export DATAMANCY_API_EMAIL="${api_email}"
export DATAMANCY_API_PASSWORD="${api_password}"

bundle exec rails runner <<'RUBY'
username = ENV.fetch('DATAMANCY_API_USERNAME').downcase
email = ENV.fetch('DATAMANCY_API_EMAIL').downcase
password = ENV.fetch('DATAMANCY_API_PASSWORD')

raise 'DATAMANCY_API_PASSWORD is empty' if password.empty?

account = Account.find_by(username: username, domain: nil)
unless account
  account = Account.new(username: username)
  account.display_name = username
  account.save!
end

user = User.find_by(email: email)
unless user
  user = User.new(email: email)
end

user.account ||= account
user.password = password
user.password_confirmation = password
user.approved = true if user.respond_to?(:approved=)
user.confirmed_at ||= Time.now.utc
user.save!

puts "[mastodon-api-user] ensured local API user #{username} (#{email})"
RUBY
