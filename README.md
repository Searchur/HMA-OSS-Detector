# HMA OSS Detector

Cross-checks current-user application UID directories in cgroups against Android package queries. Only the first UID parent identified from `/proc/self/cgroup` is scanned; if no UID parent can be identified, only `/sys/fs/cgroup/` is used. No alternate directories are scanned. The app scans automatically when opened.

Requires Android 12 (API 31) or later. The app declares `QUERY_ALL_PACKAGES` to query ordinary installed apps; no runtime permission prompt is needed.

## Results

The screen shows two tables:

- **Directory statistics**: `UIDs` is the number of application UID directories found; `Named` counts UIDs whose package query returns at least one name; `Empty` counts UIDs whose package query returns no names. An inaccessible probe channel is marked `Unavailable`.
- **Query timings**: `Avg µs`, `Min µs`, and `Max µs` show the arithmetic mean, fastest query, and slowest query in microseconds. `None` samples UID candidates with neither an observed directory nor a returned package name. `Named` combines queries for observed UIDs with package names. Each observed `Empty` UID has its own `UID <number>` row.

Each scan uses five sampling rounds, each in a new process. Each observed UID is queried once per round to avoid measuring repeated process-local cache hits. A round tries at most 32 unobserved UID candidates and keeps up to eight with empty package queries for the `None` group. Directory counts and the UID rows in the timing table reflect the last round; timing statistics combine the rounds. Timings are diagnostic data and do not change the detection results.

`UIDs` counts observed directories, including both `Named` and `Empty` UIDs. Changing a hiding rule does not necessarily remove an existing UID directory. For example, if ten observed UIDs initially have empty queries and five later return package names, `UIDs` can remain ten while `Named` becomes five, `Empty` becomes five, and the timing table shows five UID rows. Each scan replaces the previous result; it does not accumulate UID rows or timing samples across scans. If the package queries still return no names after a rule change, those UIDs remain `Empty`; the app cannot infer a visibility change that is absent from the query results.

Only ordinary application UIDs belonging to the current Android user are included. Other users, isolated processes, and SDK sandbox UIDs are excluded. Each UID with an empty package query appears once in the timing table. `None` describes the available observations; it does not prove that no app is installed for that UID.

Starting a hidden app can create an observable UID directory even when its package name is filtered. An app without an observable directory is outside this method's coverage. Directory cleanup and package visibility depend on the device, so an empty query is an observation rather than proof of a particular hiding module.

## Releases

Every push to `main` builds and publishes a signed APK. The release version increases by exactly `0.1` (`1.1`, `1.2`, `1.3`, and so on), and the build number increases by `1`. The release tag is the release version.

Build timestamps use UTC and the format `yyyyMMddHHmm`:

- Release title: `<timestamp>-<version>(<build>)`, for example `202610110433-1.1(16)`.
- APK filename and Assets display name: `HMA OSS Detector-<timestamp>-<version>(<build>).apk`, for example `HMA OSS Detector-202610110433-1.1(16).apk`.

The workflow sets an explicit asset display label to preserve spaces and parentheses in the Assets list. GitHub may normalize the filename used in its download URL.

Download published APKs from [Releases](https://github.com/Searchur/HMAOSSDetector/releases).

## Local build

Use JDK 25 and Android SDK 37:

```bash
./gradlew :app:assembleRelease
```

Local release builds are signed with the local debug certificate by default, so the APK can be installed for testing. This certificate is intended for local testing; an APK signed with it cannot update an installed release that uses a different certificate.

To use your release key locally, copy `keystore.properties.example` to `keystore.properties` in the repository root and fill in the keystore path, passwords, and key alias. Use the same signing key as the published release to install local builds as updates. `keystore.properties` and keystore files are excluded from Git. An incomplete signing configuration fails the build.

The GitHub Actions workflow builds with `-PunsignedRelease=true` and then signs the APK using its configured release secrets before publishing.

Local builds do not increment versions or publish releases. The release workflow updates the version constants and commits them back to the repository after a push to `main`.
