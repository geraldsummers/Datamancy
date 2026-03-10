# frozen_string_literal: true

# Work around OIDC state mismatches in containerized reverse-proxy setups.
# Disable state validation only when explicitly configured.
if defined?(Devise) && Devise.omniauth_configs[:openid_connect]
  require_state = ENV.fetch('OIDC_REQUIRE_STATE', 'true') != 'false'
  Devise.omniauth_configs[:openid_connect].options[:require_state] = require_state
end
