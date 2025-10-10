# Datastar test

## Run

```bash
clj -A:dev
```
## Aims

What is the correct implementation of app/with-sse-broadcast?

I would like "Update all tabs" to cache its own connection and broadcast the content to all cached connections ie all open tabs with an app instance.

Open the app in multiple browser tabs to test.

