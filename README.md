# URL Shortener

A URL shortener built in Clojure with JSON API and a browser based HTML interface. It lets users create links with custom aliases and view click statistics. The app generates a random 6 character code for each link but also allows users to choose their own alias. Clicking a short link redirects to the original URL and records the visit.
The app supports two ways to create a link:
- With JSON API using a POST request 
- Through HTML form at '/'
App includes input validation so blank, missing or malformed URLs and aliases are rejected before saving to database. If random generated code already exists in the database, it retries set amount of times. Errors like malformed JSON, URLs, taken aliases or duplicate codes are handled cleanly without causing the app to crash. The app has a full test suite covering the database layer, request handlers and routing logic.

## Tech stack

- Clojure
- Ring
- reitit
- next.jdbc
- SQLite
- Cheshire
- Hiccup

## Project structure

Project is made of 4 main namespaces:
- core.clj creates the database tables if they don't already exist and starts an embedded Jetty server. A shutdown hook is added so that server stops cleanly.
- db.clj is responsible for all SQLite interaction. It creates links and clicks tables, inserts links, records clicks, looks up links by short code and calculates statistic for each link. The datasource is passed into the database functions, which makes it possible to use a different database when testing.
- handlers.clj makes short codes or accepts custom aliases, with automatic retry if a random code collides. It validates submitted URLs and aliases, records clicks on redirect and builds JSON and HTML responses with Hiccup. 
- routes.clj connects HTTP methods and URL paths to the handler functions using reitit. 

## Custom aliases

When shortening a URL you can optionally provide an alias. This alias acts as the short code instead of a randomly generated code. It can be supplied as a JSON field or a form field. An alias must be between 3 and 20 characters long, made of only letters and digits. It also cannot be a reserved word like 'api' or 'form'. If the alias is already taken, the request will fail with a 409 status code.

## Configuration

Configuration settings such as server port and base URL can be set through PORT and BASE_URL environment variables.
- Port sets the port the server listens on. By default, it's set to 3000.
- BASE_URL defines the base URL used when creating links. By default, it's `http://localhost:3000/`.

## Running it

1. Open Command Prompt.
2. From the project root, start the app with:
   ```
    clj -M -m url-shortener.core  
   ```
3. Visit `http://localhost:3000` in a browser.

## Running the tests

1. Open Command Prompt.
2. From the project root, start the app with:
     ```
        clj -M:test  
       ```
This will run a full test suite with an isolated temporary SQLite database so tests never touch original database. 