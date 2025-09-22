# Coder Toolbox Plugin Changelog

## Unreleased

## 0.6.5 - 2025-09-16

### Fixed

- token is no longer required when authentication is done via certificates
- errors while running actions are now reported

## 0.6.4 - 2025-09-03

### Added

- improved diagnose support

### Fixed

- NPE during error reporting
- relaxed `Content-Type` checks while downloading the CLI

## 0.6.3 - 2025-08-25

### Added

- progress reporting while handling URIs

### Changed

- workspaces status is now refresh every time Coder Toolbox becomes visible

### Fixed

- support for downloading the CLI when proxy is configured

## 0.6.2 - 2025-08-14

### Changed

- content-type is now enforced when downloading the CLI to accept only binary responses

## 0.6.1 - 2025-08-11

### Added

- support for skipping CLI signature verification

### Changed

- URL validation is stricter in the connection screen and URI protocol handler
- support for verbose logging a sanitized version of the REST API request and responses

### Fixed

- remote IDE reconnects automatically after plugin upgrade

## 0.6.0 - 2025-07-25

### Changed

- improved workflow when network connection is flaky

## 0.5.2 - 2025-07-22

### Fixed

- fix class cast exception during signature verification
- the correct CLI signature for Windows is now downloaded

## 0.5.1 - 2025-07-21

### Added

- support for certificate based authentication

## 0.5.0 - 2025-07-17

### Added

- support for matching workspace agent in the URI via the agent name
- support for checking if CLI is signed

### Removed

- dropped support for `agent_id` as a URI parameter

## 0.4.0 - 2025-07-08

### Added

- support for basic authentication for HTTP/HTTPS proxy
- support for Toolbox 2.7 release

### Changed

- improved message while loading the workspace

### Fixed

- URI protocol handler is now able to switch to the Coder provider even if the last opened provider was something else

## 0.3.2 - 2025-06-25

### Changed

- the logos and icons now match the new branding

## 0.3.1 - 2025-06-19

### Added

- visual text progress during Coder CLI downloading

### Changed

- the plugin will now remember the SSH connection state for each workspace, and it will try to automatically
  establish it after an expired token was refreshed.

### Fixed

- `Stop` action is now available for running workspaces that have an out of date template.
- outdated and stopped workspaces are now updated and started when handling URI
- show errors when the Toolbox is visible again after being minimized.
- URI handling now installs the exact build number if it is available for the workspace.

## 0.3.0 - 2025-06-10

### Added

- support for Toolbox 2.6.3 with improved URI handling

## 0.2.3 - 2025-05-26

### Changed

- improved workspace status reporting (icon and colors) when it is failed, stopping, deleting, stopped or when we are
  establishing the SSH connection.

### Fixed

- url on the main page is now refreshed when switching between multiple deployments (via logout/login or URI handling)
- tokens are now remembered after switching between multiple deployments

## 0.2.2 - 2025-05-21

- render network status in the Settings tab, under `Additional environment information` section.
- quick action for creating new workspaces from the web dashboard.

### Fixed

- `Open web terminal` action is no longer displayed when the workspace is stopped.
- URL links can now be opened in Windows

## 0.2.1 - 2025-05-05

### Changed

- ssh configuration is simplified, background hostnames have been discarded.

### Fixed

- rendering glitches when a Workspace is stopped while SSH connection is alive
- misleading message saying that there are no workspaces rendered during manual authentication
- Coder Settings can now be accessed from the authentication wizard

## 0.2.0 - 2025-04-24

### Added

- support for using proxies. Proxy authentication is not yet supported.

### Changed

- connections to the workspace are no longer established automatically after agent started with error.

### Fixed

- SSH connection will no longer fail with newer Coder deployments due to misconfiguration of hostname and proxy command.

## 0.1.5 - 2025-04-14

### Fixed

- login screen is shown instead of an empty list of workspaces when token expired

### Changed

- improved error handling during workspace polling

## 0.1.4 - 2025-04-11

### Fixed

- SSH connection to a Workspace is no longer established only once
- authorization wizard automatically goes to a previous screen when an error is encountered during connection to Coder
  deployment

### Changed

- action buttons on the token input step were swapped to achieve better keyboard navigation
- URI `project_path` query parameter was renamed to `folder`

## 0.1.3 - 2025-04-09

### Fixed

- Toolbox remembers the authentication page that was last visible on the screen

## 0.1.2 - 2025-04-04

### Fixed

- after log out, user is redirected back to the initial log in screen

## 0.1.1 - 2025-04-03

### Fixed

- SSH config is regenerated correctly when Workspaces are added or removed
- only one confirmation dialog is shown when removing a Workspace

## 0.1.0 - 2025-04-01

### Added

- initial support for JetBrains Toolbox 2.6.0.38311 with the possibility to manage the workspaces - i.e. start, stop,
  update and delete actions and also quick shortcuts to templates, web terminal and dashboard.
- support for light & dark themes
