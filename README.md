# URL Shortener

URL Shortener built in Clojure with a JSON API and a browser based HTML form for creating short links.

## Features

- **Shorten a URL** - generate a short, random 6 character code that redirects to the original URL.
- **Redirection to a short link** - clicking on the link in the browser redirects the browser to the original URL.
- **Two ways to create links**:
  - With JSON API (`POST /api/shorten`) for programmatic use
  - With HTML form (`GET /`, `POST /form/shorten`) for use directly in a browser
- **Input validation** - rejects blank, missing, or malformed URLs before they're stored.
- **Collision handling** - automatically retries a set number of times if the generated code already exists in the database.
- **Error handling** - malformed JSON, invalid URLs, and duplicate codes are all handled without crashing the app.
- * Environment-based configuration - server port and base URL can be set via `PORT` and `BASE_URL` environment variables.
- * Tested - a full test suite covers the database layer, request handlers, and routing.

## Tech stack

- Clojure
- Ring
- reitit
- next.jdbc
- SQLite
- Cheshire
- Hiccup

## Project structure

- **`core.clj`** creates the database table if one doesn't already exist and starts an embedded Jetty server, port is configurable via the PORT environment variable, and registers a shutdown hook so the server stops cleanly.
- **`db.clj`** handles all SQLite interaction: creating the `links` table, inserting new links, and looking links up by their short code. The datasource is passed in as a parameter throughout so a different database can be substituted.
- **`handlers.clj`** generates unique short codes with automatic retry on collision, validates submitted URLs, and builds both JSON and HTML responses using Hiccup for Html generation. Database errors and malformed/oversized request bodies are caught and turned into clean error responses.
- **`routes.clj`** wires HTTP methods and URL paths to the handler functions using reitit. The form '/form/shorten' route has `ring.middleware.params` applied to it, so the HTML form's submission can be read as ':form-params'. This middleware is scoped to just that route so it doesn't interfere with the JSON API, which sends its data differently.

## Configuration

The app reads two optional environment variables:

- **PORT** - the port the server listens on, defaults on 3000,
- **BASE-URL** - the base url used when building short links, defaults to https://localhost:3000/.

## Running it

> **Note:** starting the server will block the terminal (or REPL prompt) it's running in. This is expected, not a crash. The server needs to keep running to handle requests, so no further commands can be typed in that same window. Open a separate terminal window to send requests or visit the site, and press Ctrl+C in the original window when you want to stop the server.

**Starting it from Command Prompt:**
1. Open Command Prompt.
2. From the project root, start the app with:
   ```
    clj -M -m url-shortener.core  
   ```
3. Visit `http://localhost:3000` in a browser.
