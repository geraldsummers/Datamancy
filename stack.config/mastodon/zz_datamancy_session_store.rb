# frozen_string_literal: true

# Ensure OIDC state survives cross-subdomain redirects.
cookie_domain = ENV['SESSION_COOKIE_DOMAIN']
cookie_domain = nil if cookie_domain.nil? || cookie_domain.strip.empty?

Rails.application.config.session_store :cookie_store,
                                        key: '_mastodon_session',
                                        secure: true,
                                        same_site: :none,
                                        domain: cookie_domain
