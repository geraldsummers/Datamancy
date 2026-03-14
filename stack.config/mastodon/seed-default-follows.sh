#!/usr/bin/env bash
set -euo pipefail

echo "Waiting for Redis availability before follow seeding..."
for i in $(seq 1 30); do
  if (echo > /dev/tcp/valkey/6379) >/dev/null 2>&1; then
    echo "Redis is reachable"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "Redis is not reachable after retries; skipping follow seeding."
    exit 0
  fi
  sleep 2
done

echo "Applying default follows to local users..."
mapfile -t users < <(bundle exec rails runner "puts Account.where(domain: nil).where.not(username: 'mastodon.internal').pluck(:username)")
if [ -n "${MASTODON_ADMIN_USERNAME:-}" ]; then
  users+=("${MASTODON_ADMIN_USERNAME}")
fi
mapfile -t users < <(printf "%s\n" "${users[@]}" | awk 'NF && !seen[$0]++')
if [ "${#users[@]}" -eq 0 ]; then
  echo "No local Mastodon users found yet; skipping default follows."
  exit 0
fi

mapfile -t follow_accounts <<'EOF'
wikimediafoundation@wikimedia.social
internetarchive@mastodon.archive.org
creativecommons@mastodon.social
openstreetmap@en.osm.town
ProPublica@newsie.social
edyong209@mastodon.xyz
marynmck@mastodon.social
briankrebs@infosec.exchange
NASA@mstdn.social
ourworldindata@mas.to
AdamMGrant@mastodon.social
calnewport@mastodon.social
b0rk@jvns.ca
simon@fedi.simonwillison.net
prusaresearch@mastodon.social
VoronDesign@fosstodon.org
natgeo@mastodon.social
philosophybites@mastodon.social
tomscott@mastodon.social
standupmaths@mastodon.social
financialtimes@mastodon.social
EOF

for user in "${users[@]}"; do
  echo "Will seed follows for $user"
done

users_csv="$(IFS=,; echo "${users[*]}")"
follow_accounts_csv="$(IFS=,; echo "${follow_accounts[*]}")"
SEED_USERS="$users_csv" SEED_FOLLOWS="$follow_accounts_csv" bundle exec rails runner '
  users = ENV.fetch("SEED_USERS", "").split(",").reject(&:empty?)
  follows = ENV.fetch("SEED_FOLLOWS", "").split(",").reject(&:empty?)
  local_accounts = Account.where(domain: nil, username: users).index_by(&:username)
  users.each do |username|
    source = local_accounts[username]
    unless source
      puts "skip user #{username}: not found"
      next
    end
    follows.each do |handle|
      attempts = 0
      begin
        target = ResolveAccountService.new.call(handle, skip_cache: true)
        if target.nil?
          puts "warn #{username}: could not resolve #{handle}"
          next
        end
        if source.following?(target)
          puts "ok #{username}: already follows #{handle}"
          next
        end
        FollowService.new.call(source, target, bypass_limit: true)
        puts "ok #{username}: followed #{handle}"
      rescue => e
        attempts += 1
        transient = e.is_a?(ActiveRecord::ConnectionNotEstablished) ||
                    e.message.include?("database system is shutting down") ||
                    e.message.include?("Redis::CannotConnectError")
        if transient && attempts < 8
          puts "retry #{username}: #{handle} after transient error (#{e.class})"
          sleep 3
          retry
        end
        puts "warn #{username}: follow #{handle} failed: #{e.class} #{e.message}"
      end
    end
  end
'
