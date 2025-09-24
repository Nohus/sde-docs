---
title: Static Data Export
---
# Static Data Export (SDE)

The [SDE](https://developers.eveonline.com/docs/services/static-data/) is an export of static game data provided by CCP.

This website documents the schema of the [JSONL](https://jsonlines.org) files it is available in. It is generated and published automatically whenever a new version of the SDE is released, so it is always up to date.

{% include-markdown "built.md" %}

## Enhanced SDE

The enhanced version of the SDE files, which is published above, contains the following additional information not available in the original files:

- `mapStars.jsonl` has a `name` field added to all items containing the star name.
- `mapPlanets.jsonl` has a `name` field added to all items containing the planet name.
- `mapMoons.jsonl` has a `name` field added to all items containing the moon name.
- `mapAsteroidBelts.jsonl` has a `name` field added to all items containing the asteroid belt name.
- `mapStargates.jsonl` has a `name` field added to all items containing the stargate name.
- `npcStations.jsonl` has a `name` field added to all items containing the station name.

The enhanced version is generated automatically whenever a new version of the SDE is released.

!!! info "Disclaimer"

    This is an unofficial website maintained by Nohus. It is not affiliated with CCP Games.
