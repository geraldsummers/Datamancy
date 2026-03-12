# frozen_string_literal: true

# Work around OIDC state mismatches in containerized reverse-proxy setups.
# Disable state validation only when explicitly configured.
if defined?(Rails)
  Rails.application.config.to_prepare do
    require_state = ENV.fetch('OIDC_REQUIRE_STATE', 'true') != 'false'
    if defined?(Devise) && Devise.omniauth_configs[:openid_connect]
      Devise.omniauth_configs[:openid_connect].options[:require_state] = require_state
      Devise.omniauth_configs[:openid_connect].options[:send_state] = require_state
    end

    next if require_state

    # Force OpenID Connect strategy defaults to skip state when disabled.
    OmniAuth::Strategies::OpenIDConnect.default_options[:send_state] = false
    OmniAuth::Strategies::OpenIDConnect.default_options[:require_state] = false

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
      unless method_defined?(:datamancy_original_callback_phase)
        alias_method :datamancy_original_callback_phase, :callback_phase
      end

      def callback_phase
        options.send_state = false
        options.require_state = false
        datamancy_original_callback_phase
      end

      def valid_state?(*)
        true
      end
    end
  end
end
