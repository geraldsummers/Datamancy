# Page snapshot

```yaml
- generic [ref=e2]:
  - generic [ref=e6]:
    - banner [ref=e8]:
      - link "Bitwarden logo" [ref=e9] [cursor=pointer]:
        - /url: "#/"
        - generic "Bitwarden logo" [ref=e10]:
          - img "Vaultwarden" [ref=e11]
    - main [ref=e16]:
      - generic [ref=e19]:
        - generic [ref=e21]:
          - img [ref=e24]
          - heading "Single sign-on" [level=1] [ref=e30]
          - generic [ref=e31]: To log in with your SSO provider, enter your organization's SSO identifier to begin. You may need to enter this SSO identifier when you log in from a new device.
        - generic [ref=e36]:
          - generic [ref=e38]:
            - generic [ref=e39]:
              - generic "SSO identifier" [ref=e40]:
                - generic [ref=e41]: SSO identifier
              - generic [ref=e42]: (required)
            - textbox "SSO identifier (required)" [active] [ref=e45]: datamancy.net
          - button "Continue" [ref=e47] [cursor=pointer]:
            - generic [ref=e48]: Continue
    - img [ref=e49]
    - img [ref=e69]
    - contentinfo [ref=e87]:
      - generic [ref=e88]: Vaultwarden Web
      - generic [ref=e89]: 2026.1.1
      - generic [ref=e90]:
        - text: A modified version of the Bitwarden® Web Vault for Vaultwarden (an unofficial rewrite of the Bitwarden® server).
        - text: Vaultwarden is not associated with the Bitwarden® project nor Bitwarden Inc.
  - generic:
    - status
```