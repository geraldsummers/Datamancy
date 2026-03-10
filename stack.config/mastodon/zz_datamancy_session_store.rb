# frozen_string_literal: true

# Ensure OIDC state survives cross-subdomain redirects.
Rails.application.config.session_store :cookie_store,
                                        key: '_mastodon_session',
                                        secure: true,
                                        same_site: :none
