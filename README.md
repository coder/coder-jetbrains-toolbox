# Coder Toolbox plugin

[!["Join us onDiscord"](https://img.shields.io/badge/join-us%20on%20Discord-gray.svg?longCache=true&logo=discord&colorB=purple)](https://discord.gg/coder)
[![Twitter Follow](https://img.shields.io/twitter/follow/CoderHQ?label=%40CoderHQ&style=social)](https://twitter.com/coderhq)
[![Coder Toolbox Plugin Build](https://github.com/coder/coder-jetbrains-toolbox/actions/workflows/build.yml/badge.svg)](https://github.com/coder/coder-jetbrains-toolbox/actions/workflows/build.yml)

Connects your JetBrains IDE to Coder workspaces

## Getting Started

To install this plugin using JetBrains Toolbox, follow the steps below.

1. Install [JetBrains Toolbox](https://www.jetbrains.com/toolbox-app/). Make sure it's the `2.6.0.40632` release or
   above.
2. Launch the Toolbox app and sign in with your JetBrains account (if needed).

### Install Coder plugin via URI

You can quickly install the plugin using this JetBrains hyperlink:

👉 <jetbrains://gateway/com.coder.toolbox>

This will open JetBrains Toolbox and prompt you to install the Coder Toolbox plugin automatically.

Alternatively, you can paste `jetbrains://gateway/com.coder.toolbox` into a browser.

### Manual install

There are two ways Coder Toolbox plugin can be installed. The first option is to manually download the plugin
artifact from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/26968-coder/versions)
or from [Coder's Github Release page](https://github.com/coder/coder-jetbrains-toolbox/releases).

The next step is to copy the zip content to one of the following locations, depending on your OS:

* Windows: `%LocalAppData%/JetBrains/Toolbox/plugins/com.coder.toolbox`
* macOS: `~/Library/Caches/JetBrains/Toolbox/plugins/com.coder.toolbox`
* Linux: `~/.local/share/JetBrains/Toolbox/plugins/com.coder.toolbox`

Alternatively, you can install it using the _Gradle_ tasks included in the project:

```shell

./gradlew cleanAll build copyPlugin
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

| Query param      | 	Description                                                                 | Mandatory |
|------------------|------------------------------------------------------------------------------|-----------|
| url              | 	Your Coder deployment URL (encoded)                                         | Yes       |
| token            | 	Coder authentication token                                                  | Yes       |
| workspace        | 	Name of the Coder workspace to connect to.                                  | Yes       |
| agent_name       | 	The name of the agent with the workspace                                    | No        |
| ide_product_code | 	JetBrains IDE product code (e.g., GO for GoLand, RR for Rider)              | No        |
| ide_build_number | 	Specific build number of the JetBrains IDE to install on the workspace      | No        |
| folder           | 	Absolute path to the project folder to open in the remote IDE (URL-encoded) | No        |

> [!NOTE]
> If only a single agent is available, specifying an agent name is optional. However, if multiple agents exist, you must
> provide the
> agent name. Note that this version of the Coder Toolbox plugin does not automatically start agents if they
> are offline, so please ensure the selected agent is running before proceeding.

If `ide_product_code` and `ide_build_number` is missing, Toolbox will only open and highlight the workspace environment
page. Coder Toolbox will attempt to start the workspace if it’s not already running; however, for the most reliable
experience, it’s recommended to ensure the workspace is running prior to initiating the connection.

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

This section explains how to set up a local proxy and verify that
the plugin’s REST client works correctly when routed through it.

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
5. Before authenticating to the Coder deployment we need to tell the plugin where can we find mitmproxy
   certificates. In Coder's Settings page, set the `TLS CA path` to `~/.mitmproxy/mitmproxy-ca-cert.pem`

> [!NOTE]
> Coder Toolbox plugin handles only HTTP/HTTPS proxy authentication.
> SOCKS5 proxy authentication is currently not supported due to limitations
> described
>
in: https://youtrack.jetbrains.com/issue/TBX-14532/Missing-proxy-authentication-settings#focus=Comments-27-12265861.0-0

### Mitmproxy returns 502 Bad Gateway to the client

When running traffic through mitmproxy, you may encounter 502 Bad Gateway errors that mention HTTP/2 protocol error: *
*Received header value surrounded by whitespace**.
This happens because some upstream servers (including dev.coder.com) send back headers such as Content-Security-Policy
with leading or trailing spaces.
While browsers and many HTTP clients accept these headers, mitmproxy enforces the stricter HTTP/2 and HTTP/1.1 RFCs,
which forbid whitespace around header values.
As a result, mitmproxy rejects the response and surfaces a 502 to the client.

The workaround is to disable HTTP/2 in mitmproxy and force HTTP/1.1 on both the client and upstream sides. This avoids
the strict header validation path and allows
mitmproxy to pass responses through unchanged. You can do this by starting mitmproxy with:

```bash
mitmproxy --set http2=false --set upstream_http_version=HTTP/1.1
```

This ensures coder toolbox http client ↔ mitmproxy ↔ server connections all run over HTTP/1.1, preventing the whitespace
error.

## Debugging and Reporting issues

Enabling debug logging is essential for diagnosing issues with the Toolbox plugin, especially when SSH
connections to the remote environment fail — it provides detailed output that includes SSH negotiation
and command execution, which is not visible at the default log level.

If you encounter a problem with Coder's JetBrains Toolbox plugin, follow the steps below to gather more
information and help us diagnose and resolve it quickly.

### Enable Debug Logging

To help with troubleshooting or to gain more insight into the behavior of the plugin and the SSH connection to
the workspace, you can increase the log level to _DEBUG_.

Steps to enable debug logging:

1. Open Toolbox

2. Navigate to the Toolbox App Menu (hexagonal menu icon) > Settings > Advanced.

3. In the screen that appears, select _DEBUG_ for the `Log level:` section.

4. Hit the back button at the top.

There is no need to restart Toolbox, as it will begin logging at the __DEBUG__ level right away.

> [!WARNING]
> Toolbox does not persist log level configuration between restarts.

#### Viewing the Logs

Once enabled, debug logs will be written to the Toolbox log files. You can access logs directly
via Toolbox App Menu > About > Show log files.

Alternatively, you can generate a ZIP file using the Workspace action menu, available either on the main
Workspaces page in Coder or within the individual workspace view, under the option labeled _Collect logs_.

### HTTP Request Logging

The Coder Toolbox plugin includes comprehensive HTTP request logging capabilities to help diagnose API communication
issues with Coder deployments.
This feature allows you to monitor all HTTP requests and responses made by the plugin.

#### Configuring HTTP Logging

You can configure HTTP logging verbosity through the Coder Settings page:

1. Navigate to the Coder Workspaces page
2. Click on the deployment action menu (three dots)
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

The Coder Settings allows users to control CLI download behavior, SSH configuration, TLS parameters, and data
storage paths. The options can be configured from the plugin's main Workspaces page > deployment action menu > Settings.

### CLI related settings

- `Binary source` specifies the source URL or relative path from which the Coder CLI should be downloaded.
  If a relative path is provided, it is resolved against the deployment domain.

- `Enable downloads` allows automatic downloading of the CLI if the current version is missing or outdated.

- `Binary directory` specifies the directory where CLI binaries are stored. If omitted, it defaults to the data
  directory.

- `Enable binary directory fallback` if enabled, falls back to the data directory when the specified binary
  directory is not writable.

- `Data directory` directory where plugin-specific data such as session tokens and binaries are stored if not
  overridden by the binary directory setting.

- `Header command` command that outputs additional HTTP headers. Each line of output must be in the format key=value.
  The environment variable CODER_URL will be available to the command process.

- `lastDeploymentURL` the last Coder deployment URL that Coder Toolbox successfully authenticated to.

- `workspaceViewUrl` specifies the dashboard page full URL where users can view details about a workspace.
  Helpful for customers that have their own in-house dashboards. Defaults to the Coder deployment workspace page.
  This setting supports `$workspaceOwner` and `$workspaceName` as placeholders.

- `workspaceCreateUrl` specifies the dashboard page full URL where users can create new workspaces.
  Helpful for customers that have their own in-house dashboards. Defaults to the Coder deployment templates page.
  This setting supports `$workspaceOwner` as placeholder with the replacing value being the username that logged in.

### TLS settings

The following options control the secure communication behavior of the plugin with Coder deployment and its available
API.

- `TLS cert path` path to a client certificate file for TLS authentication with Coder deployment.
  The certificate should be in X.509 PEM format.

- `TLS key path` path to the private key corresponding to the TLS certificate from above.
  The certificate should be in X.509 PEM format.

- `TLS CA path` the path of a file containing certificates for an alternate certificate authority used to verify TLS
  certs returned by the Coder deployment. The file should be in X.509 PEM format. This option can also be used to verify
  proxy certificates.

- `TLS alternate hostname` overrides the hostname used in TLS verification. This is useful when the hostname
  used to connect to the Coder deployment does not match the hostname in the TLS certificate.

### SSH settings

The following options control the SSH behavior of the Coder CLI.

- `Disable autostart` adds the --disable-autostart flag to the SSH proxy command, preventing the CLI from keeping
  workspaces constantly active.

- `Enable SSH wildcard config` enables or disables wildcard entries in the SSH configuration, which allow generic
  rules for matching multiple workspaces.

- `SSH proxy log directory` directory where SSH proxy logs are written. Useful for debugging SSH connection issues.

- `SSH network metrics directory` directory where network information used by the SSH proxy is stored.

- `Extra SSH options` additional options appended to the SSH configuration. Can be used to customize the behavior of
  SSH connections.

### Saving Changes

Changes made in the settings page are saved by clicking the Save button. Some changes, like toggling SSH wildcard
support, may trigger regeneration of SSH configurations.

### Security considerations

> [!IMPORTANT]
> Token authentication is required when TLS certificates are not configured.

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
