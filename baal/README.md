## Baal
False idols await.

To setup: 
- Change the url in `baal.conf`#`ktor.deployment.baal`
- Provide psql details in `r2dbc.json` (check `r2dbc-sample.json`)
- Run either `gradlew :baal:shadowJar` for a raw jar (Java 11), or `gradlew :baal:buildImage` for a docker image
- Set up a reverse proxy for the url in `baal.conf` (You will want HTTPS!)

Note: Unlike regular cors-mechanics, Baal is configured to forward *all* requests to Blaseball, **including actions such as POSTing**. Cookies are configured to allow for a login state to persist.

I **highly** recommend either only running this on an internal host, **or** setting up an authentication check on the reverse proxy!!