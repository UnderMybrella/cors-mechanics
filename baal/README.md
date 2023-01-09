## Baal
False idols await.

To setup: 
- Change the url in `baal.conf`#`ktor.deployment.baal`
- Provide psql details in `r2dbc.json` (check `r2dbc-sample.json`)
- Run either `gradlew :baal:shadowJar` for a raw jar (Java 11), or `gradlew :baal:buildImage` for a docker image
- Set up a reverse proxy for the url in `baal.conf` (You will want HTTPS!)
- 