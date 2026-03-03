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
              - text: plmm9wag53i8v
              - button "Logout" [ref=e19] [cursor=pointer]: Logout
  - generic [ref=e21]:
    - generic [ref=e23]:
      - paragraph [ref=e24]: Your server is starting up.
      - paragraph [ref=e25]: You will be redirected automatically when it's ready for you.
      - progressbar [ref=e27]:
        - generic [ref=e28]: 100% Complete
      - paragraph [ref=e29]: "Spawn failed: 404 Client Error for http://docker-socket-proxy:2375/v1.53/containers/36663a447665544f66902afc21468b8b9150407701e713460d202250cc67bde1/start: Not Found (\"failed to set up container networking: network datamancy-stack_litellm not found\")"
    - group [ref=e32]:
      - generic "Event log" [ref=e33] [cursor=pointer]
      - generic [ref=e34]:
        - generic [ref=e35]: Server requested
        - generic [ref=e36]: Spawning server...
        - generic [ref=e37]: "Spawn failed: 404 Client Error for http://docker-socket-proxy:2375/v1.53/containers/36663a447665544f66902afc21468b8b9150407701e713460d202250cc67bde1/start: Not Found (\"failed to set up container networking: network datamancy-stack_litellm not found\")"
```