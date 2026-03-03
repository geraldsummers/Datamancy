# Page snapshot

```yaml
- generic [ref=e2]:
  - heading [level=1] [ref=e4]:
    - link [ref=e5] [cursor=pointer]:
      - /url: /
      - img [ref=e6]
  - generic [ref=e8]:
    - strong [ref=e10]: Could not authenticate you from OpenIDConnect because “Invalid client”.
    - generic [ref=e11]:
      - heading "Log in with" [level=4] [ref=e12]
      - link "Authelia" [ref=e14] [cursor=pointer]:
        - /url: /auth/auth/openid_connect
    - list [ref=e16]:
      - listitem [ref=e17]:
        - link "Sign up" [ref=e18] [cursor=pointer]:
          - /url: https://joinmastodon.org/#getting-started
      - listitem [ref=e19]:
        - link "Forgot your password?" [ref=e20] [cursor=pointer]:
          - /url: /auth/password/new
      - listitem [ref=e21]:
        - link "Didn't receive a confirmation link?" [ref=e22] [cursor=pointer]:
          - /url: /auth/confirmation/new
```