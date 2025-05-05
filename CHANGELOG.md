# Coder Toolbox Plugin Changelog

## Unreleased

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
