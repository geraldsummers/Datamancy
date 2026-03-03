# Page snapshot

```yaml
- generic [active] [ref=e1]:
  - navigation [ref=e2]:
    - generic [ref=e3]:
      - link "JupyterHub logo" [ref=e5] [cursor=pointer]:
        - /url: /hub/
        - img "JupyterHub logo" [ref=e6]
      - generic [ref=e7]:
        - list [ref=e8]:
          - listitem [ref=e9]:
            - link "Home" [ref=e10] [cursor=pointer]:
              - /url: /hub/home
          - listitem [ref=e11]:
            - link "Token" [ref=e12] [cursor=pointer]:
              - /url: /hub/token
        - list [ref=e13]:
          - listitem [ref=e14]:
            - button "Toggle dark mode" [ref=e15] [cursor=pointer]
          - listitem [ref=e17]:
            - generic [ref=e18]:
              - text: plmm9xavm3nfz
              - button "Logout" [ref=e19] [cursor=pointer]: Logout
  - generic [ref=e21]:
    - generic [ref=e23]:
      - paragraph [ref=e24]: Your server is starting up.
      - paragraph [ref=e25]: You will be redirected automatically when it's ready for you.
      - progressbar [ref=e27]:
        - generic [ref=e28]: 50% Complete
      - paragraph [ref=e29]: Spawning server...
    - group [ref=e32]:
      - generic "Event log" [ref=e33] [cursor=pointer]
```