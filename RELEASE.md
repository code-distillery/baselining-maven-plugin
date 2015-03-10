# How to perform a release?

## Prerequisites
1. settings.xml needs to define credentials for  repository `sonatype-nexus-staging`
2. valid gnugp config to sign release

## Performing a release
1. run `mvn release:prepare`; micro version numbers of releases are always even, SNAPSHOTS odd.
2. run `mvn release:perform`
3. log in to https://oss.sonatype.org
4. go to `Staging Repositories` and look for the correct one (bottom of the list)
5. close staging repository
6. release (and drop) staging repository
