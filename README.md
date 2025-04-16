# Coder Toolbox plugin

[!["Join us onDiscord"](https://img.shields.io/badge/join-us%20on%20Discord-gray.svg?longCache=true&logo=discord&colorB=purple)](https://discord.gg/coder)
[![Twitter Follow](https://img.shields.io/twitter/follow/CoderHQ?label=%40CoderHQ&style=social)](https://twitter.com/coderhq)
[![Coder Toolbox Plugin Build](https://github.com/coder/coder-jetbrains-toolbox/actions/workflows/build.yml/badge.svg)](https://github.com/coder/coder-jetbrains-toolbox/actions/workflows/build.yml)

Connects your JetBrains IDE to Coder workspaces

## Getting Started

To install this plugin using JetBrains Toolbox, follow the steps below.

1. Install [JetBrains Toolbox](https://www.jetbrains.com/toolbox-app/). Make sure it's the `2.6.0.40284` release or
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
jetbrains://gateway/com.coder.toolbox?url=https%3A%2F%2Fdev.coder.com&token=zeoX4SbSpP-j2qGpajkdwxR9jBdcekXS2&workspace=bobiverse-bob&agent=dev&ide_product_code=GO&ide_build_number=241.23774.119&folder=%2Fhome%2Fcoder%2Fworkspace%2Fhello-world-rs

jetbrains://gateway/com.coder.toolbox?url=https%3A%2F%2Fj5gj2r1so5nbi.pit-1.try.coder.app%2F&token=gqEirOoI1U-FfCQ6uj8iOLtybBIk99rr8&workspace=bobiverse-riker&agent=dev&ide_product_code=RR&ide_build_number=243.26053.17&folder=%2Fhome%2Fcoder%2Fworkspace%2Fhello-world-rs
```

### URI Breakdown

```text
jetbrains://gateway/com.coder.toolbox
  ?url=http(s)://<your-coder-deployment>
  &token=<auth-token>
  &workspace=<workspace-name>
  &agent_id=<agent--id>
  &ide_product_code=<IDE-code>
  &ide_build_number=<IDE-build>
  &folder=<absolute-path-to-a-project-folder>
```

| Query param      | 	Description                                                                 | Mandatory |
|------------------|------------------------------------------------------------------------------|-----------|
| url              | 	Your Coder deployment URL (encoded)                                         | Yes       |
| token            | 	Coder authentication token                                                  | Yes       |
| workspace        | 	Name of the Coder workspace to connect to.                                  | Yes       |
| agent_id         | 	ID of the agent associated with the workspace                               | No        |
| ide_product_code | 	JetBrains IDE product code (e.g., GO for GoLand, RR for Rider)              | No        |
| ide_build_number | 	Specific build number of the JetBrains IDE to install on the workspace      | No        |
| folder           | 	Absolute path to the project folder to open in the remote IDE (URL-encoded) | No        |

If only a single agent is available, specifying an agent ID is optional. However, if multiple agents exist,
you must provide either the ID to target a specific one. Note that this version of the Coder Toolbox plugin
does not automatically start agents if they are offline, so please ensure the selected agent is running before
proceeding.

If `ide_product_code` and `ide_build_number` is missing, Toolbox will only open and highlight the workspace environment
page. Coder Toolbox will attempt to start the workspace if it’s not already running; however, for the most reliable
experience, it’s recommended to ensure the workspace is running prior to initiating the connection.

## Configuring and Testing workspace polling with HTTP & SOCKS5 Proxy

This section explains how to set up a local proxy (without authentication which is not yet supported) and verify that
the plugin’s REST client works correctly when routed through it.

We’ll use [mitmproxy](https://mitmproxy.org/) for this — it can act as both an HTTP and SOCKS5 proxy with SSL
interception.

### Install mitmproxy

1. Follow the [mitmproxy Install Guide](https://docs.mitmproxy.org/stable/overview-installation/) steps for your OS.
2. Start the proxy:

```bash

mitmweb --ssl-insecure --set stream_large_bodies="10m"
 ```

### Configure Proxy

mitmproxy can do HTTP and SOCKS5 proxying. To configure one or the other:

1. Open http://127.0.0.1:8081 in browser;
2. Navigate to `Options -> Edit Options`
3. Update the `Mode` field to `regular` in order to activate HTTP/HTTPS or to `socks5`
4. Proxy authentication can be enabled by updating the `proxyauth` to `username:password`

### Configure Proxy in Toolbox

1. Start Toolbox
2. From Toolbox hexagonal menu icon go to `Settings -> Proxy`
3. There are two options, to use system proxy settings or to manually configure the proxy details
4. If we go manually, add `127.0.0.1` to the host and port `8080` for HTTP/HTTPS or `1080` for SOCKS5.
5. Before authenticating to the Coder deployment we need to tell the plugin where can we find mitmproxy
   certificates. In Coder's Settings page, set the `TLS CA path` to `~/.mitmproxy/mitmproxy-ca-cert.pem`

## Releasing

1. Check that the changelog lists all the important changes.
2. Update the gradle.properties version.
3. Publish the resulting draft release after validating it.
4. Merge the resulting changelog PR.
