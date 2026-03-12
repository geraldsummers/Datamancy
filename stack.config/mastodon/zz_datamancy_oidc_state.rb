# frozen_string_literal: true

# Work around OIDC state mismatches in containerized reverse-proxy setups.
# Disable state validation only when explicitly configured.
if defined?(Rails)
  Rails.application.config.to_prepare do
    next unless defined?(Devise) && Devise.omniauth_configs[:openid_connect]

    require_state = ENV.fetch('OIDC_REQUIRE_STATE', 'true') != 'false'
    Devise.omniauth_configs[:openid_connect].options[:require_state] = require_state
    Devise.omniauth_configs[:openid_connect].options[:send_state] = require_state

    next if require_state

    # OmniAuth::Strategies::OpenIDConnect validates state when send_state is true,
    # so disable sending and force validation off for reverse-proxy setups.
    begin
      require 'omniauth_openid_connect'
    rescue LoadError
      # Ignore if the strategy isn't available yet; the override will retry
      # on the next prepare cycle.
      next
    end

    OmniAuth::Strategies::OpenIDConnect.class_eval do
      def valid_state?(*)
        true
      end
    end
  end
end
