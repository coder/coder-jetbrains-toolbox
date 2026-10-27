# Coder Toolbox plugin

[!["Join us onDiscord"](https://img.shields.io/badge/join-us%20on%20Discord-gray.svg?longCache=true&logo=discord&colorB=purple)](https://discord.gg/coder)
[![Twitter Follow](https://img.shields.io/twitter/follow/CoderHQ?label=%40CoderHQ&style=social)](https://twitter.com/coderhq)
[![Coder Toolbox Plugin Build](https://github.com/coder/coder-jetbrains-toolbox/actions/workflows/build.yml/badge.svg)](https://github.com/coder/coder-jetbrains-toolbox/actions/workflows/build.yml)

Connects your JetBrains IDE to Coder workspaces

## Getting Started

To install this plugin using JetBrains Toolbox, follow the steps below.

1. Install [JetBrains Toolbox](https://www.jetbrains.com/toolbox-app/) **3.9 or newer**. Earlier versions are not
   supported.
2. Launch the Toolbox app and sign in with your JetBrains account (if needed).

### Install Coder plugin via URI

You can quickly install the plugin using this JetBrains hyperlink:

👉 <jetbrains://gateway/com.coder.toolbox>

This will open JetBrains Toolbox and prompt you to install the Coder Toolbox plugin automatically.

Alternatively, you can paste `jetbrains://gateway/com.coder.toolbox` into a browser.

### Manual install

There are two ways Coder Toolbox plugin can be installed. The first option is to manually download the plugin artifact
from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/26968-coder/versions)
or from [Coder's Github Release page](https://github.com/coder/coder-jetbrains-toolbox/releases).

The next step is to copy the zip content to one of the following locations, depending on your OS:

* Windows: `%LocalAppData%/JetBrains/Toolbox/cache/plugins/com.coder.toolbox`
* macOS: `~/Library/Caches/JetBrains/Toolbox/plugins/com.coder.toolbox`
* Linux: `~/.local/share/JetBrains/Toolbox/plugins/com.coder.toolbox`

Alternatively, you can install it using the _Gradle_ tasks included in the project:

```shell

./gradlew cleanAll build installPlugin
```

Make sure Toolbox is closed before running the command.

## Connect to a Coder Workspace via JetBrains Toolbox URI

You can use specially crafted JetBrains Gateway URIs to automatically:

1. Open Toolbox

2. Install the Coder Toolbox plugin (if not already installed)

3. Connect to a specific Coder deployment using a URL and a token.

4. Select a running workspace

5. Install a specified JetBrains IDE on that Workspace

6. Open a project folder directly in the remote IDE

### Example URIs

```text
jetbrains://gateway/com.coder.toolbox?url=https%3A%2F%2Fdev.coder.com&token=zeoX4SbSpP-j2qGpajkdwxR9jBdcekXS2&workspace=bobiverse-bob&agent_name=dev&ide_product_code=GO&ide_build_number=241.23774.119&folder=%2Fhome%2Fcoder%2Fworkspace%2Fhello-world-rs

jetbrains://gateway/coder?url=https%3A%2F%2Fj5gj2r1so5nbi.pit-1.try.coder.app%2F&token=gqEirOoI1U-FfCQ6uj8iOLtybBIk99rr8&workspace=bobiverse-riker&agent_name=dev&ide_product_code=RR&ide_build_number=243.26053.17&folder=%2Fhome%2Fcoder%2Fworkspace%2Fhello-world-rs
```

### URI Breakdown

```text
jetbrains://gateway/com.coder.toolbox
  ?url=http(s)://<your-coder-deployment>
  &token=<auth-token>
  &workspace=<workspace-name>
  &owner=<workspace-owner>
  &agent_name=<agent-name>
  &ide_product_code=<IDE-code>
  &ide_build_number=<IDE-build>
  &folder=<absolute-path-to-a-project-folder>
```

Starting from Toolbox 2.7, you can use `coder` as a shortcut in place of the full plugin ID. The URI can be simplified
as:

```text
jetbrains://gateway/coder?url=http(s)://<your-coder-deployment>
```

| Query param      | 	Description                                                                                       | Mandatory |
|------------------|---------------------------------------------------------------------------------------------------|-----------|
| url              | 	Your Coder deployment URL (encoded)                                                               | Yes       |
| token            | 	Coder authentication token                                                                        | Yes       |
| workspace        | 	Name of the Coder workspace to connect to.                                                        | Yes       |
| owner            | 	Owner username, UUID, or `me` used to disambiguate the workspace lookup.                          | No        |
| agent_name       | 	The name of the agent with the workspace                                                          | No        |
| ide_product_code | 	JetBrains IDE product code (e.g., GO for GoLand, RR for Rider)                                    | No        |
| ide_build_number | 	Specific build number or placeholder (see below) of the JetBrains IDE to install on the workspace | No        |
| folder           | 	Absolute path to the project folder to open in the remote IDE (URL-encoded)                       | No        |

When `owner` is present, the URI handler sends `owner:<owner>` as the workspace lookup query and then exact-matches the
`workspace` value in the returned list. This lookup is independent of the workspace list filtering controls in the
header. When `owner` is omitted, the URI handler sends no workspace lookup query.

> [!NOTE]
> If only a single agent is available, specifying an agent name is optional. However, if multiple agents exist, you must
> provide the
> agent name. Note that this version of the Coder Toolbox plugin does not automatically start agents if they
> are offline, so please ensure the selected agent is running before proceeding.

If `ide_product_code` and `ide_build_number` is missing, Toolbox will only open and highlight the workspace environment
page. Coder Toolbox will attempt to start the workspace if it’s not already running; however, for the most reliable
experience, it’s recommended to ensure the workspace is running prior to initiating the connection.

### Resolving IDE and Build Number

When handling URIs, the Coder Toolbox plugin can resolve the IDE and its build number in several ways. This allows for
flexible and automated IDE management.

The `ide_build_number` parameter supports the following values:

- `latest_eap`:  Finds the latest available Early Access Program (EAP) version of the specified `ide_product_code`.
    - **Fallback**: If no EAP version is found, it falls back to the latest available build (release or EAP). If no
      builds are available at all, an error is shown.
- `latest_release`: Finds the latest available stable release version of the specified `ide_product_code`.
    - **Fallback**: If no release version is found, it falls back to the latest available build (release or EAP). If no
      builds are available at all, an error is shown.
- `latest_installed`: Uses the latest installed version of the IDE on the Coder workspace.
    - **Fallback**: If no IDE is installed, it falls back to the latest available version for installation. If no builds
      are available for installation, an error is shown.
- A specific build number (e.g., `241.14494.240`):  The plugin will look for and use this exact build number.
    - **Fallback**: If the specific build number is not found among installed or available IDEs, an error is shown.

The plugin first checks for installed IDEs on the remote workspace, then queries for all available (but not yet
installed) IDEs. Based on the `ide_build_number` hint, it will either pick an already installed IDE or install a new
one.

### Offline Mode

The Coder Toolbox plugin supports an offline mode, which allows it to function without an internet connection. This is
particularly useful in environments with restricted network access.

To enable offline mode, the JetBrains Toolbox must be launched with the `--offline-mode` flag. In this mode, the plugin
relies on local JSON files (`release.json` and `eap.json`) that you must provide in the plugin's data directory. The
plugin does **not** cache these files automatically.

While online, the plugin fetches the latest IDE feeds from JetBrains' data services. In offline mode, it bypasses these
network requests and uses the local files instead.

#### Offline Mode File Schema and Location

The feed files must be placed in the plugin's data directory, which varies by operating system:

- **macOS**: `~/Library/Application Support/coder-toolbox/`
- **Linux**: `~/.local/share/coder-toolbox/`
- **Windows**: `%LOCALAPPDATA%/coder-toolbox/`

Two files are used for the feeds:

- `release.json`: Contains the stable release versions of JetBrains IDEs.
- `eap.json`: Contains the Early Access Program (EAP) versions.

You can obtain these files by downloading them from the following URLs:

- **Release feed
  **: [https://data.services.jetbrains.com/products?type=release](https://data.services.jetbrains.com/products?type=release)
- **EAP feed
  **: [https://data.services.jetbrains.com/products?type=eap](https://data.services.jetbrains.com/products?type=eap)

The schema for these JSON files is a list of `IdeProduct` objects. Each `IdeProduct` contains a list of `IdeRelease`
objects.

**Example `release.json` entry:**

```json
[
  {
    "code": "RR",
    "intellijProductCode": "RR",
    "name": "RustRover",
    "releases": [
      {
        "build": "241.14494.240",
        "version": "2024.1",
        "type": "release",
        "date": "2024-04-03"
      }
    ]
  }
]
```

**Example `eap.json` entry:**

```json
[
  {
    "code": "RR",
    "intellijProductCode": "RR",
    "name": "RustRover",
    "releases": [
      {
        "build": "241.14494.120",
        "version": "2024.1 EAP 5",
        "type": "eap",
        "date": "2024-03-15"
      }
    ]
  }
]
```

By providing these files, the plugin can resolve and launch the correct IDE version even when it cannot access the
internet.

## GPG Signature Verification

The Coder Toolbox plugin starting with version *0.5.0* implements a comprehensive GPG signature verification system to
ensure the authenticity and integrity of downloaded Coder CLI binaries. This security feature helps protect users from
running potentially malicious or tampered binaries.

### How It Works

1. **Binary Download**: When connecting to a Coder deployment, the plugin downloads the appropriate Coder CLI binary for
   the user's operating system and architecture from the deployment's `/bin/` endpoint.

2. **Signature Download**: After downloading the binary, the plugin attempts to download the corresponding `.asc`
   signature file from the same location. The signature file is named according to the binary (e.g.,
   `coder-linux-amd64.asc` for `coder-linux-amd64`).

3. **Fallback Signature Sources**: If the signature is not available from the deployment, the plugin can optionally fall
   back to downloading signatures from `releases.coder.com`. This is controlled by the `fallbackOnCoderForSignatures`
   setting.

4. **GPG Verification**: The plugin uses the BouncyCastle library to verify the detached GPG signature against the
   downloaded binary using Coder's trusted public key.

5. **User Interaction**: If signature verification fails or signatures are unavailable, the plugin presents security
   warnings to users, allowing them to accept the risk and continue or abort the operation.

### Verification Process

The verification process involves several components:

- **`GPGVerifier`**: Handles the core GPG signature verification logic using BouncyCastle
- **`VerificationResult`**: Represents the outcome of verification (Valid, Invalid, Failed, SignatureNotFound)
- **`CoderDownloadService`**: Manages downloading both binaries and their signatures
- **`CoderCLIManager`**: Orchestrates the download and verification workflow

### Configuration Options

Users can control signature verification behavior through plugin settings:

- **`disableSignatureVerification`**: When enabled, skips all signature verification. This is useful for clients running
  custom CLI builds, or customers with old deployment versions that don't have a signature published on
  `releases.coder.com`.
- **`fallbackOnCoderForSignatures`**: When enabled, allows downloading signatures from `releases.coder.com` if not
  available from the deployment.

### Security Considerations

- The plugin embeds Coder's trusted public key in the plugin resources
- Verification uses detached signatures, which are more secure than attached signatures
- Users are warned about security risks when verification fails
- The system gracefully handles cases where signatures are unavailable
- All verification failures are logged for debugging purposes

### Error Handling

The system handles various failure scenarios:

- **Missing signatures**: Prompts user to accept risk or abort
- **Invalid signatures**: Warns user about potential tampering and prompts user to accept risk or abort
- **Verification failures**: Prompts user to accept risk or abort

This signature verification system ensures that users can trust the Coder CLI binaries they download through the plugin,
protecting against supply chain attacks and ensuring binary integrity.

## Configuring and Testing workspace polling with HTTP & SOCKS5 Proxy

This section explains how to set up a local proxy and verify that the plugin’s REST client works correctly when routed
through it.

We’ll use [mitmproxy](https://mitmproxy.org/) for this — it can act as both an HTTP and SOCKS5 proxy with SSL
interception.

### Install mitmproxy

1. Follow the [mitmproxy Install Guide](https://docs.mitmproxy.org/stable/overview-installation/) steps for your OS.
2. Start the proxy:

```bash
mitmweb --ssl-insecure --set stream_large_bodies="10m"
 ```

### Configure Mitmproxy

mitmproxy can do HTTP and SOCKS5 proxying. To configure one or the other:

1. Open http://127.0.0.1:8081 in browser;
2. Navigate to `Options -> Edit Options`
3. Update the `Mode` field to `regular` in order to activate HTTP/HTTPS or to `socks5`
4. Proxy authentication can be enabled by updating the `proxyauth` to `username:password`
5. Alternatively you can run the following commands:

```bash
mitmweb --ssl-insecure --set stream_large_bodies="10m" --mode regular --proxyauth proxyUsername:proxyPassword
mitmweb --ssl-insecure --set stream_large_bodies="10m" --mode socks5
```

### Configure Proxy in Toolbox

1. Start Toolbox
2. From Toolbox hexagonal menu icon go to `Settings -> Proxy`
3. There are two options, to use system proxy settings or to manually configure the proxy details.
4. If we go manually, add `127.0.0.1` to the host and port `8080` for HTTP/HTTPS or `1080` for SOCKS5.
5. Before authenticating to the Coder deployment we need to tell the plugin where can we find mitmproxy certificates. In
   Coder's Settings page, set the `TLS CA path` to `~/.mitmproxy/mitmproxy-ca-cert.pem`

> [!NOTE]
> Coder Toolbox plugin handles only HTTP/HTTPS proxy authentication.
> SOCKS5 proxy authentication is currently not supported due to limitations
> described
>
in: https://youtrack.jetbrains.com/issue/TBX-14532/Missing-proxy-authentication-settings#focus=Comments-27-12265861.0-0

### Mitmproxy returns 502 Bad Gateway to the client

When running traffic through mitmproxy, you may encounter 502 Bad Gateway errors that mention HTTP/2 protocol error: *
*Received header value surrounded by whitespace**. This happens because some upstream servers (including dev.coder.com)
send back headers such as Content-Security-Policy with leading or trailing spaces. While browsers and many HTTP clients
accept these headers, mitmproxy enforces the stricter HTTP/2 and HTTP/1.1 RFCs, which forbid whitespace around header
values. As a result, mitmproxy rejects the response and surfaces a 502 to the client.

The workaround is to disable HTTP/2 in mitmproxy and force HTTP/1.1 on both the client and upstream sides. This avoids
the strict header validation path and allows mitmproxy to pass responses through unchanged. You can do this by starting
mitmproxy with:

```bash
mitmproxy --set http2=false --set upstream_http_version=HTTP/1.1
```

This ensures coder toolbox http client ↔ mitmproxy ↔ server connections all run over HTTP/1.1, preventing the whitespace
error.

## Debugging and Reporting issues

The plugin's flight recorder keeps recent messages below Toolbox's log level in memory and replays them at connection
diagnostic checkpoints and when collecting a Coder support bundle. This can preserve the context leading up to an issue
without enabling debug logging beforehand. Enable debug logging when you need continuous detailed output, including
messages from Toolbox itself.

If you encounter a problem with Coder's JetBrains Toolbox plugin, follow the steps below to gather more information and
help us diagnose and resolve it quickly.

### Flight recorder

The flight recorder is enabled by default with a capacity of **1000 log entries** from the Coder plugin logger. You can
configure the capacity up to **10000 entries** using **Connection log buffer size**. Messages that Toolbox accepts at
its current log level are logged normally; messages it filters out are captured in a fixed capacity array ring buffer.
When the buffer fills, each new entry replaces the oldest entry.

Each entry preserves its original log level, message text, and exception details when present. Its UTC timestamp marks
when the prepared entry is accepted into the buffer, after any lazy message evaluation and exception formatting.
Entries are replayed and evicted in that acceptance order. The limit counts entries, not lines or bytes: a multiline
message or a message with a stack trace occupies one entry, so memory usage depends on message size. Capture covers
messages sent through the Coder plugin logger; CLI SSH file logs are collected separately as described in
[Coder Support Bundles](#coder-support-bundles).

There is one shared buffer per running plugin instance, so a replay can contain context from several workspaces.
Messages retain any `client_session_id` recorded with them. Environment-specific replays add the triggering
environment's current session ID only to the replay header and footer; entry chunks keep their original session IDs,
and entries without an ID remain unscoped. Provider-wide replays do not add a session prefix. The buffer remains in
memory until replayed or discarded. Entries that have not been replayed are lost when the plugin instance ends or
Toolbox exits.

#### Configuring the buffer

Open the **Coder main page** (the page listing your environments), click the **action menu on the right side of the
page header**, and select **Settings**. Under **General**, change **Connection log buffer size** and click **Save**.

| Setting                    | Plugin settings key       | Default        | Valid values                                 |
|----------------------------|---------------------------|----------------|----------------------------------------------|
| Connection log buffer size | `connectionLogBufferSize` | `1000` entries | Whole numbers from `0` to `10000`, inclusive |

Changes take effect without restarting Toolbox:

- Reducing the capacity keeps the newest entries that fit and discards older entries.
- Increasing the capacity preserves all retained entries and allows more entries to be recorded.
- Setting the capacity to `0` clears the buffer and disables recording and replay. Normal Toolbox logging continues.
- The settings page rejects invalid input. An absent, malformed, or out-of-range stored value falls back to `1000`.

#### Toolbox log levels and replay levels

The recorder follows Toolbox's effective log level, including `--debug` and `--trace` launch flags. Changes to the
Toolbox log level apply live. Each replay call uses a level Toolbox currently persists, while the original level of
each entry stays in the message text.

| Toolbox effective level | Logged normally          | New entries captured in memory | Replay level                                    |
|-------------------------|--------------------------|--------------------------------|-------------------------------------------------|
| ERROR                   | ERROR                    | WARN, INFO, DEBUG, TRACE       | ERROR                                           |
| WARN                    | ERROR, WARN              | INFO, DEBUG, TRACE             | WARN                                            |
| INFO                    | ERROR, WARN, INFO        | DEBUG, TRACE                   | INFO                                            |
| DEBUG                   | ERROR, WARN, INFO, DEBUG | TRACE                          | INFO                                            |
| TRACE                   | All levels               | None                           | INFO for entries retained from an earlier level |
| OFF                     | None                     | None                           | No replay                                       |

With Toolbox logging set to **OFF**, neither recording nor replay runs. Existing buffered entries remain available
after logging is re-enabled, unless the capacity is set to `0` or the plugin instance ends in the meantime.

The plugin's **HTTP logging level** is independent of this setting. For example, the recorder cannot capture HTTP
headers or bodies when the HTTP logging setting does not generate those messages. See
[HTTP Request Logging](#http-request-logging).

#### Automatic replay triggers

The following events flush the recorder. The reason appears in the replay header and footer; `<id>` is the environment
ID, normally `workspace.agent`.

| Event                                                                              | Replay reason                                                                     |
|------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| An environment changes from reachable to unreachable                               | `environment_status_changed <id>`                                                 |
| The workspace filter changes                                                       | `workspace_filter_changed`                                                        |
| A workspace restart, stop, or deletion is requested                                | `workspace_restarting <id>`, `workspace_stopping <id>`, `workspace_deleting <id>` |
| An SSH disconnect request times out before workspace stop                          | `ssh_disconnect_timeout <id>`                                                     |
| Toolbox invokes the SSH disconnect callback                                        | `ssh_disconnected <id>`                                                           |
| An environment's SSH session is removed during disposal or after manual disconnect | `ssh_session_removed <id>`, `ssh_session_removed_after_manual_disconnect <id>`    |
| Logout begins, CLI logout fails, or the provider closes                            | `logout`, `cli_logout_failed`, `provider_closed`                                  |
| The Coder session expires or access token refresh fails                            | `coder_session_expired`, `token_refresh_failed`                                   |
| Unsafe deployment-controlled workspace, owner, or agent identifiers are reported   | `invalid_deployment_data`                                                         |
| A workspace/deployment action fails                                                | `action_failed <action description>`                                              |
| Workspace log collection starts Coder support bundle export                        | `support_bundle`                                                                  |

Lifecycle flushes run immediately after their triggering log message, so that message is included if Toolbox filters
it. Support bundle export flushes before collecting the bundle. These checkpoints also include normal state changes
and user actions; a replay block alone does not mean that a connection failed.

#### Reading replayed logs

A flush replays entries from oldest to newest and removes each batch after it is logged. An empty buffer produces no
header, entries, or footer. A completed flush makes one call for the header, one call per chunk of up to **100
entries**,
and one call for the footer. For example, 250 entries produce five Toolbox log calls.

Example message text, before Toolbox adds its own timestamp and metadata:

```text
[buffered] replaying 2 buffered entries (support_bundle)
[buffered] 2026-10-22T06:10:01.002Z DEBUG Connecting to demo.agent
[buffered] retry scheduled
[buffered] 2026-10-22T06:10:03.415Z TRACE Waiting for agent
[buffered] end of buffered logs (support_bundle)
```

The `retry scheduled` line is a continuation of the first entry. Every continuation line gets a bare `[buffered] `
prefix. Entry timestamps use ISO 8601 in UTC with millisecond precision and show when the prepared entry was accepted
into the buffer. Toolbox adds its own timestamp for the replay time, which can use a different time zone.

Each chunk is one multiline Toolbox record. Only its first line receives Toolbox's usual timestamp, severity, and
other metadata; subsequent lines contain the buffered message text. Original severity labels such as `DEBUG` and
`TRACE` therefore remain visible even when the Toolbox record itself is logged as INFO, WARN, or ERROR.

Capture and export are best-effort because Toolbox schedules its own log writes and the effective level can change
between recording and writing a message. Successfully replayed entries are not repeated by later flushes. If logging
is switched to OFF during replay, the remaining entries stay buffered until another flush can run.

### Enable Debug Logging

To help with troubleshooting or to gain more insight into the behavior of the plugin and the SSH connection to the
workspace, you can increase the log level to _DEBUG_.

Steps to enable debug logging:

1. Open Toolbox

2. Navigate to the Toolbox App Menu (hexagonal menu icon) > Settings > Advanced.

3. In the screen that appears, select _DEBUG_ for the `Log level:` section.

4. Hit the back button at the top.

There is no need to restart Toolbox, as it will begin logging at the __DEBUG__ level right away.

> [!WARNING]
> Toolbox does not persist log level configuration between restarts.

### Viewing the Logs

Normal plugin logs and replayed flight recorder entries are written through Toolbox's logger into the **Toolbox App
log files**. Search for `[buffered]` to find replay blocks and their reasons. The recorder does not create a separate
log file, and the **SSH proxy log directory** and plugin **Data directory** settings do not change where replayed
entries are written.

Open **Toolbox App Menu > Settings > About > Show log files** to locate the files. The standard directories are:

| Operating system | Toolbox App log directory                |
|------------------|------------------------------------------|
| Windows          | `%LOCALAPPDATA%\JetBrains\Toolbox\logs\` |
| macOS            | `~/Library/Logs/JetBrains/Toolbox/`      |
| Linux            | `~/.local/share/JetBrains/Toolbox/logs/` |

These locations are documented in
the [JetBrains Toolbox App FAQ](https://www.jetbrains.com/help/toolbox-app/frequently-asked-questions.html).

Alternatively, use the workspace environment's _Collect logs_ action to generate a ZIP file with Toolbox diagnostics
and a Coder support bundle. See [Coder Support Bundles](#coder-support-bundles) below for the navigation steps and
collection requirements.

### Coder Support Bundles

The plugin includes a Coder support bundle **only when you use the workspace environment's _Collect logs_ action**
in JetBrains Toolbox.

> [!IMPORTANT]
> When reporting an issue, we recommend using this action so the log archive includes both Toolbox diagnostics and a
> Coder support bundle, giving the support team more information to investigate.

To collect logs with a Coder support bundle:

1. Open JetBrains Toolbox and navigate to the Coder plugin's **Workspaces** page.
2. Find the workspace environment you want to troubleshoot and open **that environment's action menu**.
   You can also open the workspace view and use its environment action menu there.
3. Select **Collect logs** to generate the Toolbox log archive, including the Coder support bundle when collection
   succeeds.

> [!NOTE]
> The general Toolbox **Settings > About > Collect logs and diagnostic data** action does **not** generate a Coder
> support bundle or explicitly flush the flight recorder. The **Collect logs** action in an individual environment's
> action menu requests both. Previously replayed entries remain available in the Toolbox App log files.

The plugin runs `coder support bundle` using the deployment's existing CLI login and targets the selected workspace
and its agent, when available. It places `coder-support.zip` inside the environment's diagnostic directory in the
Toolbox log archive. Collection does not start a stopped workspace.

Before running the Coder CLI, the plugin flushes the flight recorder with reason `support_bundle` and the current
session ID when available. Replayed entries go into the Toolbox App logs collected with the diagnostics; the recorder
does not write a separate file into `coder-support.zip`. This replay is requested even if Coder bundle generation
subsequently fails. Keep Toolbox logging enabled when collecting logs, since OFF disables replay.

Recent local CLI SSH logs are added in place to `coder-support.zip` under `toolbox-logs/ssh/`. If the bundle could
not be generated or is not a valid archive, the logs are written to a separate `coder-ssh.zip` in the diagnostic
directory instead, using the same layout.

For CLI versions **2.4.0 and newer**, the plugin passes `--log-dir <directory> -v` to the SSH ProxyCommand, enabling
verbose file logging even on CLIs that do not log by default. The directory is the configured SSH log directory,
then `CODER_SSH_LOG_DIR`, then `ssh-logs/` inside the deployment's plugin data directory. The plugin does not infer
the CLI's default state directory, so logs written there by earlier setups are not collected. CLI versions older than
2.4.0, or whose version is not recognized, receive neither logging flag and skip cleanup. Their SSH logs go wherever
the CLI writes them by default, so collection only finds logs if you point the configured SSH log directory or
`CODER_SSH_LOG_DIR` at that location.

Only regular files directly inside the SSH log directory named `coder-ssh*-<pid>.log` are eligible, where `<pid>`
contains only digits. Collection skips subdirectories, symbolic links, and other filenames, including bare PID names
such as `12345.log` and rotated `.log.<number>` files. These local logs can include sessions for other workspaces
that share the log directory.

The following fixed limits apply and are not configurable:

- **Age:** include files modified within the last **3 days (72 hours)**, based on the file's last-modified time.
  The identified active SSH session's log for the selected workspace/agent is included regardless of age.
  If the active log cannot be identified, the cutoff applies to every file.
- **Size:** copy only the last **50 MiB (52,428,800 bytes) per file**, including the active log. This is a limit on
  each file's uncompressed contents, not on the total archive size.

These limits apply only to the archived copies; the original log files are unchanged. Missing or unreadable SSH
logs do not prevent collecting the remaining diagnostics.

On each connection attempt with a supported CLI, background cleanup keeps the newest **20** `coder-ssh*-<pid>.log`
files by modification time and deletes other matching regular files only if they are older than **7 days**, so more
than 20 files can remain when the extra ones are newer. Cleanup does not recurse or delete symlinks. Collection itself
never deletes source logs.
CLI 2.38+ also applies its own 50-file pruning in this directory.

The bundle can contain deployment health and configuration, network diagnostics, workspace build logs, template
source, and agent diagnostics. Available information depends on your permissions and workspace connectivity.
Review the bundle before sharing, following
[Coder's support-bundle guidance](https://coder.com/docs/support/support-bundle).

Support bundles require **Coder server and CLI 2.10.0 or newer**, which introduced
[`coder support bundle`](https://coder.com/changelog/coder-v2-10-0). Collection runs until the CLI finishes or you
cancel
log collection. If the CLI is unavailable or unsupported, or authentication or connectivity fails, the plugin removes
any partial bundle and attempts to include
`coder-support-error.txt` instead. Local SSH logs and Toolbox's other diagnostics remain available.

### HTTP Request Logging

The Coder Toolbox plugin includes comprehensive HTTP request logging capabilities to help diagnose API communication
issues with Coder deployments. This feature allows you to monitor all HTTP requests and responses made by the plugin.

#### Configuring HTTP Logging

You can configure HTTP logging verbosity through the Coder Settings page:

1. Open the Coder main page, which lists your environments
2. Click the action menu (three dots) on the right side of the page header
3. Select "Settings"
4. Find the "HTTP logging level" dropdown

#### Available Logging Levels

The plugin supports four levels of HTTP logging verbosity:

- **None**: No HTTP request/response logging (default)
- **Basic**: Logs HTTP method, URL, and response status code
- **Headers**: Logs basic information plus sanitized request and response headers
- **Body**: Logs headers plus request and response body content

#### Log Output Format

HTTP logs follow this format:

```
request --> GET https://your-coder-deployment.com/api/v2/users/me
User-Agent: Coder Toolbox/1.0.0 (darwin; amd64)
Coder-Session-Token: <redacted>

response <-- 200 https://your-coder-deployment.com/api/v2/users/me
Content-Type: application/json
Content-Length: 245

{"id":"12345678-1234-1234-1234-123456789012","username":"coder","email":"coder@example.com"}
```

#### Use Cases

HTTP logging is particularly useful for:

- **API Debugging**: Diagnosing issues with Coder API communication
- **Authentication Problems**: Troubleshooting token or certificate authentication issues
- **Network Issues**: Identifying connectivity problems with Coder deployments
- **Performance Analysis**: Monitoring request/response times and payload sizes

#### Troubleshooting with HTTP Logs

When reporting issues, include HTTP logs to help diagnose:

1. **Authentication Failures**: Check for 401/403 responses and token headers
2. **Network Connectivity**: Look for connection timeouts or DNS resolution issues
3. **API Compatibility**: Verify request/response formats match expected API versions
4. **Proxy Issues**: Monitor proxy authentication and routing problems

## Coder Settings

The Coder Settings allows users to control CLI download behavior, SSH configuration, TLS parameters, and data storage
paths. On the Coder main page (the page listing your environments), click the action menu on the right side of the
page header and select **Settings**.

### Workspace list filtering

The expandable header on the Coder Workspaces page mirrors the workspace filtering in the Coder web dashboard. The first
row is a free-form search field; the second row holds three dropdowns: **Filters** (presets), **Template**, and
**Status**.

- The search field holds
  a [Coder workspace filter query](https://coder.com/docs/user-guides/workspace-management#workspace-filtering)
  and is sent to the server verbatim as the `q` parameter. The server parses and validates it.
- The list defaults to `owner:me` (your own workspaces) on each load. Nothing is persisted between sessions.
- The dropdowns are shortcuts that edit the search text, exactly like the dashboard:
    - **Filters** replaces the whole query with the chosen preset: `My workspaces` (`owner:me`), `All workspaces`
      (empty), `Running workspaces` (`status:running`), `Failed workspaces` (`status:failed`),
      `Outdated workspaces` (`outdated:true`), `Shared workspaces` (`shared:true`), and `Dormant workspaces`
      (`dormant:true`). When the search text matches no preset, the dropdown shows `Custom`.
    - **Template** sets or replaces the `template:` term.
    - **Status** sets or replaces the `status:` term.
- The dropdowns and the search text stay in sync: editing the text updates the dropdowns, and selecting a dropdown value
  updates the text. Because each key appears at most once, the dropdowns never produce a duplicate-key query.
- If the server rejects the query (for example a duplicate key, or a malformed term), the validation message is shown in
  a secondary label after the dropdowns.

Examples of what is sent as the `q` parameter:

| Search field              | Sent as `q`               |
|---------------------------|---------------------------|
| empty                     | no `q` parameter          |
| `owner:me` (default)      | `owner:me`                |
| `owner:me name:bobiverse` | `owner:me name:bobiverse` |
| `template:"Heaven 3"`     | `template:"Heaven 3"`     |
| `status:running`          | `status:running`          |

The Template dropdown is populated from the deployment's templates, fetched each time the header becomes visible.
Changing the search or a dropdown refreshes the workspace list and regenerates SSH configuration from the refreshed
workspace set. With wildcard SSH enabled, the generated block uses the deployment wildcard host pattern. With wildcard
SSH disabled, the generated block contains entries for the currently resolved workspace/agent pairs. Agents are resolved
only for running workspaces, so stopped workspaces have no SSH entries until they are started and picked up by the next
workspace refresh.

SSH hostnames include the workspace owner so workspaces with the same name owned by different users remain distinct.
With wildcard SSH enabled, the SSH config contains a deployment-wide `Host coder-jetbrains-toolbox-<host>--*` entry, and
the plugin connects through hostnames shaped like
`coder-jetbrains-toolbox-<host>--<owner>--<workspace>.<agent>`. With wildcard SSH disabled, the SSH config writes one
explicit entry per resolved workspace/agent using
`Host coder-jetbrains-toolbox--<owner>--<workspace>.<agent>--<host>`, and its proxy command targets
`<owner>/<workspace>.<agent>`.

### Connection logging settings

- `Connection log buffer size` (`connectionLogBufferSize`) sets the maximum number of log entries below Toolbox's
  log level to keep in memory for connection diagnostics. It defaults to `1000` and accepts whole numbers from `0`
  to `10000`; `0` clears the buffer and disables buffering. Invalid stored values fall back to `1000`. Saving a new
  value resizes the buffer without a restart, keeping the newest entries that fit. See
  [Flight recorder](#flight-recorder) for capture, automatic replay, log levels, and output details.

### CLI related settings

- `Binary source` specifies the source URL or relative path from which the Coder CLI should be downloaded. If a relative
  path is provided, it is resolved against the deployment domain.

- `Enable downloads` allows automatic downloading of the CLI if the current version is missing or outdated. Enabled by
  default.

- `Binary destination` specifies where the CLI binary is placed. This can be a path to an existing executable (used
  as-is) or a base directory (the CLI is placed under a host-specific subdirectory). If blank, the data directory is
  used. Supports `~` and `$HOME` expansion.

- `Data directory` directory where deployment-specific data such as session tokens and CLI binaries
  are stored. Each deployment gets a host-specific subdirectory (e.g. `coder.example.com`). Supports `~` and `$HOME`
  expansion. When keyring-backed CLI storage is enabled, the session token is no longer persisted in this directory.

- `Header command` command that outputs additional HTTP headers. Each line of output must be in the format key=value.
  When this setting is left blank, the `CODER_HEADER_COMMAND` environment variable is used instead, if set.

- `Store CLI session in OS keyring when supported (CLI >= 2.29.0)` is enabled by default on macOS and Windows.
  An unset or `true` `useKeyring` setting uses the OS keyring; `false` opts out and stores the CLI session in the
  plugin's deployment-specific data directory. Linux and older CLIs use file storage. Changes apply on the next
  sign-in or plugin restart.

- `lastDeploymentURL` the last Coder deployment URL that Coder Toolbox successfully authenticated to.

- `workspaceViewUrl` specifies the dashboard page full URL where users can view details about a workspace. Helpful for
  customers that have their own in-house dashboards. Defaults to the Coder deployment workspace page. This setting
  supports `$workspaceOwner` and `$workspaceName` as placeholders.

- `workspaceCreateUrl` specifies the dashboard page full URL where users can create new workspaces. Helpful for
  customers that have their own in-house dashboards. Defaults to the Coder deployment templates page. This setting
  supports `$workspaceOwner` as placeholder with the replacing value being the username that logged in.

#### How CLI resolution works

When connecting to a deployment the plugin ensures a compatible CLI binary is available. The binary location is resolved
as follows:

- If **binary destination** points to an existing executable file, it is used as-is.
- If **binary destination** is set but is not an executable file, it is treated as a base directory and the CLI is
  placed under a host-specific subdirectory (e.g.
  `<binary destination>/coder.example.com/<default-cli-name>`).
- If **binary destination** is not set, the data directory is used instead.

Once the binary location is resolved:

1. If a CLI already exists there and its version matches the deployment, it is used immediately.
2. Otherwise, if **downloads are enabled**, the plugin downloads the matching version to that location. Any download
   error is reported to the user.
3. If **downloads are disabled** and the CLI exists but its version does not match, the stale CLI is used with a
   warning. If no CLI exists at all, an error is raised.

#### How keyring-backed CLI login works

Toolbox passes session tokens to `coder login` through `CODER_SESSION_TOKEN` and uses `--use-token-as-session` so
REST requests and CLI commands share the same token. The token is not passed in command-line arguments.

On macOS and Windows with Coder CLI `2.29.0` or newer, Toolbox enables the OS keyring by default. It passes
`--use-keyring=true` and the deployment URL, and omits the plugin-specific `--global-config` directory. After a
successful keyring login, Toolbox removes any old plaintext session file from that deployment's plugin data directory.
A keyring write failure is reported as a login failure; Toolbox does not retry the write using file storage.

To opt out, uncheck **Store CLI session in OS keyring when supported (CLI >= 2.29.0)** or set `useKeyring` to `false`.
Toolbox then uses its deployment-specific `--global-config` directory and passes `--use-keyring=false` to CLIs that
support the flag. Linux also uses this file storage. On older CLIs, Toolbox omits the unsupported flag and warns at
login on macOS and Windows when the requested keyring storage is unavailable. Certificate-based authentication
continues to use the plugin-specific CLI configuration without storing a session token.

Saving the setting does not change the current session's credential backend or rewrite its SSH commands to use a
new backend. The change takes effect on the next sign-in or plugin restart, when Toolbox stores the token and
regenerates SSH configuration. Workspace starts, SSH connections, support bundles, token refreshes, and logout use
the active session's storage choice consistently.

The OS keyring entry is shared with the Coder CLI and VS Code extension for the same deployment. Explicitly logging
out of Toolbox runs `coder logout --yes`, which revokes the session and removes its stored credential, and clears
Toolbox's saved API token and OAuth credentials. Other clients using that shared session will need to sign in again.
If CLI cleanup fails, Toolbox still signs out and displays a warning. Closing the plugin alone preserves credentials.

Credential commands and header commands have a 60-second timeout. Workspace starts and SSH sessions are not subject
to that limit. Timeout and cancellation cleanup is best-effort for child processes: a command that forks and exits
before its children are observed can leave orphan processes running. Workspace start progress is reported one
complete line at a time, including carriage-return updates.

Toolbox retains its own credentials in the Toolbox secret store for automatic sign-in. Importing a token created by
an independent terminal login is tracked separately in DEVEX-403.

### TLS settings

The following options control the secure communication behavior of the plugin with Coder deployment and its available
API.

- `TLS cert path` path to a client certificate file for TLS authentication with Coder deployment. The certificate should
  be in X.509 PEM format.

- `TLS key path` path to the private key corresponding to the TLS certificate from above. The certificate should be in
  X.509 PEM format.

- `TLS CA path` the path of a file containing certificates for an alternate certificate authority used to verify TLS
  certs returned by the Coder deployment. The file should be in X.509 PEM format. This option can also be used to verify
  proxy certificates.

- `TLS alternate hostname` overrides the hostname used in TLS verification. This is useful when the hostname used to
  connect to the Coder deployment does not match the hostname in the TLS certificate.

### SSH settings

The following options control the SSH behavior of the Coder CLI.

- `Disable autostart` adds the --disable-autostart flag to the SSH proxy command, preventing the CLI from keeping
  workspaces constantly active.

- `Enable SSH wildcard config` enables or disables wildcard entries in the SSH configuration, which allow generic rules
  for matching multiple workspaces.

- `SSH config path` is the SSH configuration file managed by the plugin and used for Toolbox connections. It defaults to
  `~/.ssh/config`; choose a separate writable file to keep the primary SSH configuration read-only.

- `SSH connnection timeout (seconds)` controls how long the SSH client will wait while trying to establish a TCP
  connection to the remote host before giving up. Defaults to 0 seconds which means it uses the system’s TCP timeout
  settings instead.

- `SSH proxy log directory` directory where SSH proxy logs are written. Useful for debugging SSH connection issues.

- `SSH network metrics directory` directory where network information used by the SSH proxy is stored.

- `Extra SSH options` additional options appended to the SSH configuration. Can be used to customize the behavior of SSH
  connections.

### Saving Changes

Changes made in the settings page are saved by clicking the Save button. Some changes, like toggling SSH wildcard
support, may trigger regeneration of SSH configurations.

### Security considerations

> [!IMPORTANT]
> Token authentication is required when TLS certificates are not configured.

When the plugin logs the Coder CLI in with a session token, it passes that token through the
`CODER_SESSION_TOKEN` environment variable instead of `--token`. This reduces the chances of the token showing up in
process listings, shell history, or command-line audit logs.

## Releasing

1. Check that the changelog lists all the important changes.
2. Update the `gradle.properties` version.
3. Publish the resulting draft release after validating it.
4. Merge the resulting changelog PR.
5. **Compliance Reminder for auto-approval**  
   JetBrains enabled auto-approval for the plugin, so we need to ensure we continue to meet the following requirements:
    - do **not** use Kotlin experimental APIs.
    - do **not** add any lambdas, handlers, or class handles to Java runtime hooks.
    - do **not** create threads manually (including via libraries). If you must, ensure they are properly cleaned up in
      the plugin's `CoderRemoteProvider#close()` method.
    - do **not** bundle libraries that are already provided by Toolbox.
    - do **not** perform any ill-intentioned actions.
