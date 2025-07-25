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
